package com.crack.memory.record

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.service.AiGateway
import com.crack.memory.docs.AtomicFiles
import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.Chronicle
import com.crack.memory.docs.ChronicleEntry
import com.crack.memory.docs.MarkdownSections
import com.crack.memory.docs.MemoryBudgets
import com.crack.memory.docs.MemoryDocs
import com.crack.memory.docs.ProtagonistDoc
import com.crack.memory.docs.StoryState
import com.crack.message.service.MessageService
import com.crack.story.files.StoryDirs
import com.crack.story.files.StoryFiles
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** 기록 한 회의 고정된 입력 (트리거 시점 값) */
data class RecordJob(
    val recordId: Long,
    val storyId: Long,
    val fromTurn: Int,
    val toTurn: Int,
    val rerecordedTurns: List<Int>,
) {
    val hasNewTurns: Boolean get() = toTurn >= fromTurn

    companion object {
        fun of(record: MemoryRecord) =
            RecordJob(record.id, record.storyId, record.fromTurn, record.toTurn, record.rerecordedTurnList)
    }
}

/**
 * 기록 결과. 아직 파일에 쓰지 않은 상태다(§7.2 ⑤ "결과를 모두 메모리에 모은 뒤").
 *
 * @property storyDir 스토리 폴더
 * @property inputs 읽은 파일(스토리 폴더 기준 상대 경로 → 읽은 내용, 없던 파일은 null). 반영 직전 변경 검사에 쓴다
 * @property outputs 새 내용(상대 경로 → 내용). 읽은 내용과 같으면 반영할 때 건너뛴다
 */
data class RecordPlan(
    val storyDir: Path,
    val inputs: Map<String, String?>,
    val outputs: Map<String, String>,
)

/** 기록할 수 없는 상태(스토리 폴더 없음, 원문 없음 등) */
class RecordPipelineException(message: String) : RuntimeException(message)

/**
 * 기억 기록 파이프라인의 계산 부분 (DESIGN.md §7.2 ①~④). **파일을 쓰지 않는다.**
 * 반영(⑤)은 [MemoryRecordService]가 스토리 잠금 아래에서 한다.
 *
 * 모든 AI 호출은 [AiGateway](purpose = RECORD)를 거친다. 캐릭터 관리자는 [MemoryRecordExecutors.workers]에서 병렬로 돈다.
 */
@Component
class MemoryRecordPipeline(
    private val storyDirs: StoryDirs,
    private val messageService: MessageService,
    private val aiGateway: AiGateway,
    private val budgets: MemoryBudgets,
    private val properties: MemoryRecordProperties,
    private val executors: MemoryRecordExecutors,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun compute(job: RecordJob): RecordPlan {
        val location = storyDirs.locate(job.storyId)
        val dir = location.dir
        if (location.isLegacy || StoryFiles.readMeta(dir) == null) {
            throw RecordPipelineException("이전되지 않은 스토리는 기억을 기록할 수 없습니다: ${job.storyId}")
        }

        // 읽기 (읽은 내용은 반영 직전 변경 검사에 쓴다)
        val inputs = linkedMapOf<String, String?>()
        fun read(rel: String): String? = AtomicFiles.readStringOrNull(dir.resolve(rel)).also { inputs[rel] = it }

        val chronicle = Chronicle(read(CHRONICLE).takeUnless { it.isNullOrBlank() } ?: Chronicle.EMPTY_TEXT)
        val state = read(STATE)?.let { StoryState.fromJson(it) } ?: StoryState.EMPTY
        val characters = MemoryDocs.readCharacters(dir)
        val protagonistText = read(PROTAGONIST)
        val protagonist = protagonistText?.let { ProtagonistDoc(it) }
        val protagonistName = protagonist?.displayName() ?: DEFAULT_PROTAGONIST_NAME

        val transcript = if (job.hasNewTurns) {
            RecordInputs.transcript(messageService.turnsInRange(job.storyId, job.fromTurn, job.toTurn))
        } else {
            ""
        }
        if (job.hasNewTurns && transcript.isBlank()) {
            throw RecordPipelineException("기록할 대화 원문이 없습니다: 턴 ${job.fromTurn}–${job.toTurn}")
        }
        val rerecorded = RecordInputs.transcript(
            job.rerecordedTurns.flatMap { messageService.turnsInRange(job.storyId, it, it) }
        )

        // ① 시나리오 관리자
        val context = chronicleContext(chronicle, job.rerecordedTurns)
        val scenario = RecordOutputParser.parseScenario(
            ask(
                RecordPrompts.SCENARIO_MANAGER,
                RecordInputs.scenario(
                    job.fromTurn, job.toTurn, context, chronicle.summary, state,
                    characters.map { RecordInputs.CharacterRef(it.name, it.parseAliases()) },
                    protagonistName, transcript, rerecorded,
                ),
            ),
            job.hasNewTurns,
        )
        val involved = resolveInvolved(scenario.involved, characters, protagonist)
        involved.forEach { inputs[characterRel(it.name)] = it.text }

        // ② 캐릭터 관리자 (병렬)
        val characterOutputs = runParallel(involved) { doc ->
            RecordOutputParser.parseCharacter(
                ask(RecordPrompts.CHARACTER_MANAGER, RecordInputs.character(doc.name, doc.text, protagonistName, transcript, rerecorded))
            )
        }
        var newCharacters = involved.zip(characterOutputs).map { (doc, out) -> doc.withMemorySection(out.memory) }

        // ③ 주인공 반영
        val protagonistChanges = characterOutputs.mapNotNull { it.protagonistChanges }.filter { it.isNotBlank() }
        var newProtagonist = protagonist
        if (protagonist != null && protagonistChanges.isNotEmpty()) {
            val changes = RecordOutputParser.parseProtagonist(
                ask(
                    RecordPrompts.PROTAGONIST_MANAGER,
                    RecordInputs.protagonist(protagonistName, protagonist.changesSection?.trim(), protagonistChanges),
                )
            )
            newProtagonist = protagonist.withChangesSection(changes)
        } else if (protagonist == null && protagonistChanges.isNotEmpty()) {
            log.warn("주인공 문서가 없어 주인공 변화를 버린다: storyId={}", job.storyId)
        }

        // 연대기: 고친 회차 교체 → 새 회차 추가
        var newChronicle = chronicle
        scenario.revised.forEach { (number, body) -> newChronicle = reviseEntry(newChronicle, number, body) }
        if (job.hasNewTurns && scenario.chronicle != null) {
            newChronicle = newChronicle.append(ChronicleEntry(newChronicle.nextNumber(), job.fromTurn, job.toTurn, scenario.chronicle))
        }

        // ④ 예산 초과 시 압축
        newCharacters = newCharacters.map { doc ->
            if (!budgets.exceeds(doc)) doc
            else doc.withMemorySection(compressSection(CharacterDoc.MEMORY, doc.memorySection.orEmpty(), budgets.character))
        }
        newProtagonist = newProtagonist?.let { doc ->
            if (!budgets.exceeds(doc)) doc
            else doc.withChangesSection(compressSection(ProtagonistDoc.CHANGES, doc.changesSection.orEmpty(), budgets.protagonist))
        }
        if (budgets.exceeds(newChronicle)) newChronicle = compressChronicle(newChronicle)

        val newState = scenario.state.copy(updatedAtTurn = job.toTurn)

        val outputs = linkedMapOf<String, String>()
        outputs[CHRONICLE] = newChronicle.text
        outputs[STATE] = newState.toJson() + "\n"
        newCharacters.forEach { outputs[characterRel(it.name)] = it.text }
        if (newProtagonist != null) outputs[PROTAGONIST] = newProtagonist.text
        return RecordPlan(dir, inputs, outputs)
    }

    /** 최근 회차 N개 + 고친 턴이 든 회차(파일 순서 유지) */
    private fun chronicleContext(chronicle: Chronicle, rerecordedTurns: List<Int>): List<ChronicleEntry> {
        val latest = chronicle.latestEntries(properties.chronicleContextEntries.coerceAtLeast(0)).map { it.number }.toSet()
        return chronicle.entries().filter { e ->
            e.number in latest || rerecordedTurns.any { it in e.fromTurn..e.toTurn }
        }
    }

    /** 이름이나 별칭이 정확히 같은 인물 문서. 모르는 이름과 주인공은 버린다. */
    private fun resolveInvolved(names: List<String>, characters: List<CharacterDoc>, protagonist: ProtagonistDoc?): List<CharacterDoc> {
        val protagonistNames = setOfNotNull(protagonist?.displayName(), DEFAULT_PROTAGONIST_NAME) + protagonist?.parseAliases().orEmpty()
        return names.mapNotNull { raw ->
            val name = raw.trim()
            if (name in protagonistNames) return@mapNotNull null
            characters.firstOrNull { it.name == name } ?: characters.firstOrNull { name in it.parseAliases() }
                ?: run {
                    log.info("involved에 모르는 인물이 있어 무시한다: {}", name)
                    null
                }
        }.distinctBy { it.name }
    }

    private fun <T, R> runParallel(items: List<T>, task: (T) -> R): List<R> {
        val futures = items.map { item -> CompletableFuture.supplyAsync({ task(item) }, executors.workers) }
        return try {
            futures.map { it.get() }
        } catch (e: ExecutionException) {
            futures.forEach { it.cancel(true) }
            throw (e.cause ?: e)
        } catch (e: CompletionException) {
            futures.forEach { it.cancel(true) }
            throw (e.cause ?: e)
        }
    }

    private fun reviseEntry(chronicle: Chronicle, number: Int, body: String): Chronicle {
        val entry = chronicle.entries().firstOrNull { it.number == number }
        if (entry == null) {
            log.info("revised가 가리키는 회차가 없어 무시한다: {}", number)
            return chronicle
        }
        val section = MarkdownSections.sections(chronicle.text, 2)
            .firstOrNull { ENTRY_NUMBER.find(it.title)?.groupValues?.get(1)?.toInt() == number }
            ?: return chronicle
        return Chronicle(MarkdownSections.replaceSection(chronicle.text, section.title, body))
    }

    private fun compressSection(title: String, body: String, limit: Int): String =
        RecordOutputParser.parseCompressed(
            ask(RecordPrompts.COMPRESS_SECTION, RecordInputs.compressSection(title, body.trim(), limit)),
            heading = title,
        )

    /** 오래된 회차 절반(최소 1개, 최근 1개는 남긴다)을 장 요약으로 합친다. */
    private fun compressChronicle(chronicle: Chronicle): Chronicle {
        val entries = chronicle.entries()
        if (entries.size < 2) return chronicle
        val n = (entries.size / 2).coerceAtLeast(1)
        val summary = RecordOutputParser.parseCompressed(
            ask(RecordPrompts.COMPRESS_CHRONICLE, RecordInputs.compressChronicle(chronicle.summary, entries.take(n), budgets.chronicle)),
            heading = Chronicle.SUMMARY,
        )
        return chronicle.replaceOldestWithSummary(n, summary)
    }

    private fun ask(systemPrompt: String, input: String): String =
        aiGateway.chat(
            AiRequest(
                systemPrompt = systemPrompt,
                messages = listOf(ChatMessage(MessageRole.USER, input)),
                purpose = AiPurpose.RECORD,
            )
        )

    companion object {
        const val CHRONICLE = MemoryDocs.CHRONICLE_FILE
        const val STATE = MemoryDocs.STATE_FILE
        const val PROTAGONIST = MemoryDocs.CHARACTERS_DIR + "/" + ProtagonistDoc.FILE_NAME
        private const val DEFAULT_PROTAGONIST_NAME = "주인공"
        private val ENTRY_NUMBER = Regex("""회차\s*(\d+)""")

        fun characterRel(name: String) = MemoryDocs.CHARACTERS_DIR + "/" + name + ".md"
    }
}
