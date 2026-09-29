package com.crack.scenario.imports

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.service.AiGateway
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * 생성 단계에 필요한 프롬프트 (DESIGN.md §11.3).
 *
 * **입력원마다 구현이 다르다.** URL 가져오기는 추출한 페이지를, 질문으로 만들기는
 * 씨앗과 질의응답을 맥락으로 쓴다. 파이프라인은 어느 쪽인지 알 필요가 없다.
 *
 * [system]은 한 번 만들어 모든 단계가 같이 쓴다(프롬프트 접두사를 같게 유지해 캐시를 살린다).
 */
interface ScenarioBuildPrompts {
    fun system(): String
    fun worldStep(): String
    fun charactersStep(names: List<String>): String
    fun protagonistStep(characterNames: List<String>): String
}

/**
 * 생성 한 건의 명세. 입력원이 다른 부분만 여기에 담고 나머지는 [ScenarioBuildPipeline]이 공통으로 한다.
 *
 * @property characterNames 만들 인물의 표시 이름. 순서를 유지한다
 * @property source 모든 문서 맨 아래에 남길 출처 한 줄
 * @property protagonistName 주인공 문서의 이름 줄이 비었을 때 채울 이름
 * @property priorLlmCalls 이 단계에 오기까지 이미 쓴 LLM 호출 수(`done` 이벤트의 합계에 더한다)
 * @property knownSection 인물 문서에 `## 알고 있는 것` 섹션을 보장한다 (T35)
 * @property privateSection 주인공 문서에 `(비공개)` 섹션을 보장한다 (T35)
 * @property images 인물 문서를 쓴 결과(표시 이름 → 파일명)를 받아 `images.md` 본문을 만든다
 * @property onPublished 폴더를 옮기고 DB에 등록한 뒤 한 번 부른다(작업 캐시 정리용)
 */
data class ScenarioBuildSpec(
    val jobId: String,
    val name: String,
    val title: String,
    val characterNames: List<String>,
    val prompts: ScenarioBuildPrompts,
    val source: String,
    val protagonistName: String? = null,
    val provider: String? = null,
    val priorLlmCalls: Int = 0,
    val knownSection: Boolean = false,
    val privateSection: Boolean = false,
    val images: (Map<String, String>) -> String,
    val onPublished: () -> Unit = {},
)

/**
 * 시나리오 문서 한 벌을 만드는 **공용 파이프라인** (DESIGN.md §11.3).
 *
 * 세계관 → 인물 → 주인공·프롤로그 → 이미지 순서로 만들고 임시 폴더에서 **원자적으로** 옮긴다.
 * URL 가져오기(T28)와 질문으로 만들기(T48)가 같은 것을 쓴다. 다른 것은 [ScenarioBuildSpec]뿐이다.
 *
 * 실패해도 예외를 던지지 않고 [ImportEventSink]에 `error`를 보낸다.
 */
@Component
class ScenarioBuildPipeline(
    private val gateway: AiGateway,
    private val workspace: ImportWorkspace,
    private val executors: ImportExecutors,
    private val properties: ImportProperties,
    private val scenarioRepository: ScenarioRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(spec: ScenarioBuildSpec, sink: ImportEventSink) {
        val startedAt = Instant.now()
        val calls = AtomicInteger(spec.priorLlmCalls)
        var tempDir: Path? = null // 정리용. 옮긴 뒤에는 null
        var published: Path? = null
        var step = "시작"
        try {
            val system = spec.prompts.system()
            val dir = workspace.createTempDir(spec.jobId)
            tempDir = dir

            // 1. 세계관 · 시나리오 · 키워드북
            step = "세계관"
            sink.step(ImportStepEvent("world", "세계관·시나리오·키워드북", 1, TOTAL_STEPS, 5))
            val worldOut = ask(system, spec.prompts.worldStep(), spec.provider, calls)
            workspace.write(
                dir, "world.md",
                ImportDocs.plain(ImportOutputParser.requireTag(worldOut, "world", step), spec.source, "세계관"),
            )
            workspace.write(
                dir, "scenario.md",
                ImportDocs.plain(ImportOutputParser.requireTag(worldOut, "scenario", step), spec.source, "시나리오"),
            )
            ImportOutputParser.tag(worldOut, "keywords")?.let {
                workspace.write(dir, "keywords.md", ImportDocs.keywords(it, spec.source))
            }

            // 2. 인물 전원
            step = "인물"
            val fileNames = assignFileNames(spec.characterNames)
            val written = writeCharacters(dir, spec, fileNames, system, calls, sink)

            // 3. 주인공 · 첫 메시지
            step = "주인공"
            sink.step(ImportStepEvent("protagonist", "주인공·첫 메시지", 3, TOTAL_STEPS, 80))
            val protagonistOut = ask(system, spec.prompts.protagonistStep(written.keys.toList()), spec.provider, calls)
            val protagonistBody = ImportOutputParser.requireTag(protagonistOut, "protagonist", step)
            workspace.write(
                dir,
                "characters/protagonist.md",
                ImportDocs.protagonist(spec.protagonistName, protagonistBody, spec.source, spec.privateSection),
            )
            ImportOutputParser.tag(protagonistOut, "prologue")?.let {
                // 첫 줄 인물 태그를 인물 파일명으로 맞춘다(T29). written: 화면 이름 → 파일명
                workspace.write(dir, "prologue.md", ImportDocs.prologue(it, spec.source, written))
            }

            // 4. 이미지 카탈로그 (LLM 호출 없음)
            step = "이미지"
            sink.step(ImportStepEvent("images", "이미지 카탈로그", 4, TOTAL_STEPS, 92))
            workspace.write(dir, "images.md", spec.images(written))

            // 5. 폴더를 옮기고 DB에 등록한다 (원자적)
            step = "등록"
            val files = workspace.listFiles(dir)
            published = workspace.publish(dir, spec.name)
            tempDir = null
            val scenario = scenarioRepository.save(Scenario(name = spec.name, title = spec.title))

            spec.onPublished()
            val elapsed = Duration.between(startedAt, Instant.now()).seconds
            log.info(
                "시나리오 생성 완료: name={}, 인물={}명, 파일={}개, LLM={}회, {}초",
                spec.name, written.size, files.size, calls.get(), elapsed,
            )
            sink.done(
                ImportDoneEvent(
                    scenarioId = scenario.id,
                    name = spec.name,
                    title = spec.title,
                    files = files,
                    characterCount = written.size,
                    llmCalls = calls.get(),
                    elapsedSeconds = elapsed,
                ),
            )
        } catch (e: Exception) {
            workspace.deleteQuietly(tempDir)
            workspace.deleteQuietly(published) // DB 등록이 실패했으면 옮긴 폴더도 되돌린다
            log.error("시나리오 생성 실패: name={}, 단계={}", spec.name, step, e)
            sink.error("$step 단계에서 실패했습니다: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 인물 수로 배치 수를 센다. 예상 호출 수·시간 계산에도 쓴다. */
    fun batchCount(characters: Int): Int {
        val size = properties.charactersPerBatch.coerceAtLeast(1)
        return ceil(characters.toDouble() / size).toInt()
    }

    /** 배치가 `concurrency`씩 병렬로 도는 것을 감안한 대략 시간. 한 호출을 30초로 본다. */
    fun estimatedSeconds(batches: Int): Int {
        val waves = ceil(batches.toDouble() / properties.concurrency.coerceAtLeast(1)).toInt()
        return (1 + waves + 1) * 30
    }

    private fun ask(system: String, userMessage: String, provider: String?, calls: AtomicInteger): String {
        calls.incrementAndGet()
        return gateway.chat(
            AiRequest(
                systemPrompt = system,
                messages = listOf(ChatMessage(MessageRole.USER, userMessage)),
                purpose = AiPurpose.CHAT,
            ),
            provider,
        )
    }

    /** 표시 이름 → 파일명(= 이미지 태그 접두사). 쓸 수 없는 이름은 빼고, 중복은 숫자를 붙인다. */
    private fun assignFileNames(names: List<String>): Map<String, String> {
        val used = mutableSetOf("protagonist")
        val result = LinkedHashMap<String, String>()
        for (name in names) {
            if (result.containsKey(name)) continue
            val safe = ImportDocs.safeName(name)
            if (safe == null) {
                log.warn("인물 이름을 파일명으로 쓸 수 없어 건너뜀: '{}'", name)
                continue
            }
            result[name] = ImportDocs.uniqueName(used, safe)
        }
        return result
    }

    /**
     * 인물 문서를 배치로 만든다. 배치 하나가 LLM 1회다.
     * 빠진 인물이 있으면 그 배치를 **한 번** 더 부른다(형식이 흔들려 한두 명이 빠지는 일이 잦다).
     * 그래도 없으면 그 인물만 건너뛴다(전체를 실패시키지 않는다).
     *
     * @return 실제로 문서를 쓴 인물의 표시 이름 → 파일명
     */
    private fun writeCharacters(
        tempDir: Path,
        spec: ScenarioBuildSpec,
        fileNames: Map<String, String>,
        system: String,
        calls: AtomicInteger,
        sink: ImportEventSink,
    ): Map<String, String> {
        val names = fileNames.keys.toList()
        if (names.isEmpty()) {
            sink.step(ImportStepEvent("characters", "인물 문서", 2, TOTAL_STEPS, 25, "인물을 찾지 못했습니다"))
            return emptyMap()
        }
        val batches = names.chunked(properties.charactersPerBatch.coerceAtLeast(1))
        sink.step(ImportStepEvent("characters", "인물 문서", 2, TOTAL_STEPS, 25, "0/${names.size}명"))

        val done = AtomicInteger(0)
        val written = java.util.concurrent.ConcurrentHashMap<String, String>()
        val futures = mutableListOf<Future<*>>()
        for (batch in batches) {
            futures += executors.workers.submit {
                val bodies = askCharacters(system, batch, spec, calls)
                for (displayName in batch) {
                    val body = bodies[displayName] ?: continue
                    val fileName = fileNames.getValue(displayName)
                    workspace.write(
                        tempDir,
                        "characters/$fileName.md",
                        ImportDocs.character(fileName, displayName, body, spec.source, spec.knownSection),
                    )
                    written[displayName] = fileName
                }
                val finished = done.addAndGet(batch.size)
                sink.step(
                    ImportStepEvent(
                        "characters", "인물 문서", 2, TOTAL_STEPS,
                        25 + (50 * finished / names.size),
                        "$finished/${names.size}명",
                    ),
                )
            }
        }
        val errors = mutableListOf<Throwable>()
        futures.forEach { future ->
            try {
                future.get()
            } catch (e: Exception) {
                errors += e.cause ?: e
            }
        }
        // 모든 배치가 실패했으면 생성을 실패시킨다. 일부만 실패했으면 나머지로 계속한다.
        if (written.isEmpty() && errors.isNotEmpty()) throw errors.first().let { it as? Exception ?: RuntimeException(it) }
        if (errors.isNotEmpty()) log.warn("인물 배치 {}개 실패(나머지로 계속한다): {}", errors.size, errors.first().message)

        val missing = names.filterNot { written.containsKey(it) }
        if (missing.isNotEmpty()) log.warn("문서를 만들지 못한 인물 {}명: {}", missing.size, missing)
        // 요청 순서를 유지해서 돌려준다
        return names.mapNotNull { name -> written[name]?.let { name to it } }.toMap(LinkedHashMap())
    }

    /** 배치 하나. 빠진 인물이 있으면 한 번 더 부른다. */
    private fun askCharacters(
        system: String,
        batch: List<String>,
        spec: ScenarioBuildSpec,
        calls: AtomicInteger,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var remaining = batch
        repeat(2) {
            if (remaining.isEmpty()) return result
            val output = ask(system, spec.prompts.charactersStep(remaining), spec.provider, calls)
            val parsed = ImportOutputParser.characters(output)
            for ((name, body) in parsed) {
                val matched = remaining.firstOrNull { it == name }
                    ?: remaining.firstOrNull { it.replace(" ", "") == name.replace(" ", "") }
                    ?: continue
                result[matched] = body
            }
            remaining = remaining.filterNot { result.containsKey(it) }
        }
        return result
    }

    companion object {
        const val TOTAL_STEPS = 4
    }
}
