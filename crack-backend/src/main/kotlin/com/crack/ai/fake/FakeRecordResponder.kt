package com.crack.ai.fake

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.provider.FakeResponses
import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.CharacterMemory
import com.crack.memory.docs.EventEntry
import com.crack.memory.docs.MemoryItem
import com.crack.memory.docs.ProtagonistDoc
import com.crack.memory.docs.RelationEntry
import com.crack.memory.docs.StoryState
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 기억 기록(T14)용 결정적 Fake 응답 (DESIGN.md §4, §7.3). CLI와 API 키가 없는 리모트·테스트에서도 기록 파이프라인 전체를 돌리기 위한 것이다.
 *
 * 기동 시 [FakeResponses]에 `RECORD` 응답기로 등록한다. 테스트가 `FakeResponses.reset()`을 부르면 지워지므로 [install]로 다시 등록한다.
 *
 * 어느 관리자인지는 시스템 프롬프트에 적힌 **출력 태그**(§7.3 계약)로 가린다. 응답은 입력 구획(태그)에서 계산한다.
 * - 시나리오: 대화 원문에 이름·별칭이 나온 인물을 `involved`와 `companions`로, 범위를 연대기 한 회차로 낸다.
 * - 캐릭터: 기존 `## 기억`을 이어받아 `주인공` 관계 한 줄을 갱신하고 사건 한 줄을 더한다.
 * - 주인공: 기존 `## 변화 기록`에 새 변화를 합친다(관계는 인물별로 교체).
 * - 압축: 하위 섹션마다 마지막 항목만 남긴다 / 오래된 회차를 한 줄 요약으로 합친다.
 */
@Component
@ConditionalOnProperty(prefix = "crack.ai.fake", name = ["enabled"], havingValue = "true")
class FakeRecordResponder(private val responses: FakeResponses) {

    @PostConstruct
    fun install() = responses.register(AiPurpose.RECORD, ::respond)

    fun respond(request: AiRequest): String {
        val system = request.systemPrompt
        val input = request.messages.lastOrNull()?.content.orEmpty()
        return when {
            "<involved>" in system -> scenario(input)
            "<protagonist_changes>" in system -> character(input)
            "<new_changes>" in system -> protagonist(input)
            "<compressed>" in system -> if (tag(input, "entries") != null) compressChronicle(input) else compressSection(input)
            else -> FakeResponses.DEFAULT_RESPONSE
        }
    }

    private fun scenario(input: String): String {
        val range = tag(input, "range").orEmpty()
        val transcript = content(input, "transcript")
        val rerecorded = content(input, "rerecorded")
        val text = "$transcript\n$rerecorded"
        val involved = characterRefs(input).filter { (name, aliases) -> (listOf(name) + aliases).any { it.length >= 2 && text.contains(it) } }
            .map { it.first }
        val previous = runCatching { StoryState.fromJson(content(input, "state")) }.getOrDefault(StoryState.EMPTY)
        val lastTurn = turns(transcript).maxOrNull() ?: previous.updatedAtTurn
        val state = StoryState(
            companions = involved,
            location = previous.location ?: "알 수 없는 곳",
            time = lastTurn?.let { "t$it 무렵" } ?: previous.time,
        ).toJson().replace(Regex("""\s*\n\s*"""), " ")

        val chronicle = if (range == NONE || range.isBlank()) {
            NONE
        } else {
            buildString {
                append("- ").append(range).append(" 동안 이야기가 진행됨 (fake 기록, ").append(range).append(")")
                involved.forEach { append("\n- ").append(it).append("이(가) 등장함 (").append(range).append(")") }
            }
        }
        val revised = revisedEntries(content(input, "chronicle_recent"), turns(rerecorded))
            .joinToString("") { (number, turn) -> "\n<revised entry=\"$number\">- 고친 대화를 반영함 (fake 기록, t$turn)</revised>" }

        return "<chronicle>\n$chronicle\n</chronicle>\n<state>$state</state>\n<involved>${involved.joinToString(", ").ifEmpty { NONE }}</involved>$revised"
    }

    private fun character(input: String): String {
        val name = tag(input, "character").orEmpty()
        val doc = CharacterDoc(name, content(input, "character_document"))
        val newTurns = turns(content(input, "transcript"))
        val fixedTurns = turns(content(input, "rerecorded"))
        val last = (newTurns + fixedTurns).maxOrNull() ?: 0
        val event = if (newTurns.isNotEmpty()) {
            "t${newTurns.min()}–${newTurns.max()}: ${name}의 기록 (fake)"
        } else {
            "t${fixedTurns.minOrNull() ?: last}: 고친 대화를 반영함 (fake)"
        }
        val current = doc.memory()
        val relation = "주인공: 함께 행동함 (t$last)"
        val memory = CharacterMemory(
            relations = current.relations.filter { it.target != "주인공" } + RelationEntry("주인공", "함께 행동함 (t$last)", MemoryItem(relation)),
            events = current.events + EventEntry(null, null, event, MemoryItem(event)),
            possessions = current.possessions,
        )
        return "<memory>\n${memory.toMarkdown().trim()}\n</memory>\n<protagonist_changes>\n- [관계] $name: 함께 행동함 (t$last)\n</protagonist_changes>"
    }

    private fun protagonist(input: String): String {
        val currentBody = content(input, "current_changes").takeUnless { it == NONE }.orEmpty()
        var changes = ProtagonistDoc("## ${ProtagonistDoc.CHANGES}\n$currentBody\n").changes()
        content(input, "new_changes").lines().forEach { line ->
            val m = CHANGE_LINE.find(line.trim()) ?: return@forEach
            val category = m.groupValues[1].trim()
            val text = m.groupValues[2].trim()
            val item = MemoryItem(text)
            changes = when (category) {
                ProtagonistDoc.RELATIONS -> {
                    val target = text.substringBefore(':').trim()
                    changes.copy(relations = changes.relations.filter { it.target != target } + RelationEntry(target, text.substringAfter(':').trim(), item))
                }
                ProtagonistDoc.STATS_SKILLS -> changes.copy(statsAndSkills = changes.statsAndSkills + item)
                ProtagonistDoc.POSSESSIONS -> changes.copy(possessions = changes.possessions + item)
                ProtagonistDoc.BODY -> changes.copy(body = changes.body + item)
                else -> changes
            }
        }
        return "<changes>\n${changes.toMarkdown().trim()}\n</changes>"
    }

    private fun compressSection(input: String): String {
        val lines = content(input, "content").lines()
        val out = mutableListOf<String>()
        var lastItem: String? = null
        for (line in lines) {
            if (line.startsWith("#")) {
                lastItem?.let { out += it }
                lastItem = null
                out += line
            } else if (line.trimStart().startsWith("- ")) {
                lastItem = line
            }
        }
        lastItem?.let { out += it }
        return "<compressed>\n${out.joinToString("\n").ifBlank { "- (fake 압축)" }}\n</compressed>"
    }

    private fun compressChronicle(input: String): String {
        val summary = content(input, "summary").takeUnless { it == NONE }.orEmpty().trim()
        val entries = ENTRY_TITLE.findAll(content(input, "entries")).toList()
        val first = entries.firstOrNull()
        val last = entries.lastOrNull()
        val line = if (first != null && last != null) {
            "- 회차 ${first.groupValues[1]}–${last.groupValues[1]} 요약 (fake 기록, t${first.groupValues[2]}–${last.groupValues[3]})"
        } else {
            "- 오래된 회차 요약 (fake 기록)"
        }
        return "<compressed>\n${listOf(summary, line).filter { it.isNotBlank() }.joinToString("\n")}\n</compressed>"
    }

    // ---- 입력 구획 읽기 ----

    private fun tag(input: String, name: String): String? =
        Regex("""<$name>\n?([\s\S]*?)\n?</$name>""").find(input)?.groupValues?.get(1)?.trim()

    private fun content(input: String, name: String): String = tag(input, name).orEmpty()

    private fun turns(transcript: String): List<Int> =
        TURN_LABEL.findAll(transcript).map { it.groupValues[1].toInt() }.distinct().toList()

    /** `<characters>`의 `- 이름 (별칭: a, b)` 줄. 주인공 줄은 뺀다. */
    private fun characterRefs(input: String): List<Pair<String, List<String>>> =
        content(input, "characters").lines().mapNotNull { raw ->
            val line = raw.trim().removePrefix("-").trim()
            if (line.isEmpty() || line.startsWith("주인공:")) return@mapNotNull null
            val name = line.substringBefore(" (").trim()
            val aliases = Regex("""\(별칭: (.*)\)""").find(line)?.groupValues?.get(1)?.split(",")?.map { it.trim() }.orEmpty()
            name to aliases
        }

    /** 고친 턴이 든 회차 번호와 그 턴 */
    private fun revisedEntries(chronicle: String, fixedTurns: List<Int>): List<Pair<Int, Int>> =
        ENTRY_TITLE.findAll(chronicle).mapNotNull { m ->
            val from = m.groupValues[2].toInt()
            val to = m.groupValues[3].toInt()
            fixedTurns.firstOrNull { it in from..to }?.let { m.groupValues[1].toInt() to it }
        }.toList()

    companion object {
        private const val NONE = "없음"
        private val TURN_LABEL = Regex("""\[t(\d+) """)
        private val CHANGE_LINE = Regex("""^-\s*\[([^\]]+)]\s*(.+)$""")
        private val ENTRY_TITLE = Regex("""## 회차 (\d+) \(턴 (\d+)–(\d+)\)""")
    }
}
