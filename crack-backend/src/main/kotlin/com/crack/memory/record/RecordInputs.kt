package com.crack.memory.record

import com.crack.memory.docs.ChronicleEntry
import com.crack.memory.docs.StoryState
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.entity.StoryMessage

/**
 * 관리자에게 보내는 유저 메시지(입력) 조립. 구획은 태그로 나눈다([RecordPrompts]의 "## 입력"과 이름이 같아야 한다).
 * 빈 구획은 `없음`으로 채운다.
 */
object RecordInputs {

    const val NONE = "없음"

    /** 인물 목록 항목 */
    data class CharacterRef(val name: String, val aliases: List<String>)

    /** `[t12 유저] …` / `[t12 서술] …`. 프롤로그는 뺀다(기억 기록 대상이 아니다). */
    fun transcript(messages: List<StoryMessage>): String =
        messages.filter { it.kind != MessageKind.PROLOGUE && it.turnNo > 0 }
            .joinToString("\n\n") { m ->
                val who = if (m.role == MessageRole.USER) "유저" else "서술"
                "[t${m.turnNo} $who] ${m.content.trim()}"
            }

    fun rangeLabel(fromTurn: Int, toTurn: Int): String =
        if (toTurn < fromTurn) NONE else if (fromTurn == toTurn) "t$fromTurn" else "t$fromTurn–t$toTurn"

    fun scenario(
        fromTurn: Int,
        toTurn: Int,
        chronicleContext: List<ChronicleEntry>,
        chronicleSummary: String?,
        state: StoryState,
        characters: List<CharacterRef>,
        protagonistName: String,
        transcript: String,
        rerecorded: String,
    ): String = buildString {
        section("range", rangeLabel(fromTurn, toTurn))
        val chronicleText = buildString {
            if (!chronicleSummary.isNullOrBlank()) append("## 장 요약\n").append(chronicleSummary.trim()).append("\n\n")
            chronicleContext.forEach { append(it.toMarkdown().trim()).append("\n\n") }
        }
        section("chronicle_recent", chronicleText)
        section("state", state.copy(updatedAtTurn = null).toJson().replace(Regex("""\s*\n\s*"""), " "))
        section(
            "characters",
            buildString {
                append("- 주인공: ").append(protagonistName).append('\n')
                characters.forEach { c ->
                    append("- ").append(c.name)
                    if (c.aliases.isNotEmpty()) append(" (별칭: ").append(c.aliases.joinToString(", ")).append(')')
                    append('\n')
                }
            },
        )
        section("transcript", transcript)
        section("rerecorded", rerecorded)
    }

    fun character(
        name: String,
        document: String,
        protagonistName: String,
        transcript: String,
        rerecorded: String,
    ): String = buildString {
        section("character", name)
        section("character_document", document)
        section("protagonist", protagonistName)
        section("transcript", transcript)
        section("rerecorded", rerecorded)
    }

    fun protagonist(protagonistName: String, currentChanges: String?, newChanges: List<String>): String = buildString {
        section("protagonist", protagonistName)
        section("current_changes", currentChanges.orEmpty())
        section("new_changes", newChanges.joinToString("\n") { it.trim() })
    }

    fun compressSection(title: String, body: String, limit: Int): String = buildString {
        section("section", title)
        section("limit", limit.toString())
        section("content", body)
    }

    fun compressChronicle(summary: String?, entries: List<ChronicleEntry>, limit: Int): String = buildString {
        section("summary", summary.orEmpty())
        section("entries", entries.joinToString("\n\n") { it.toMarkdown().trim() })
        section("limit", limit.toString())
    }

    private fun StringBuilder.section(tag: String, body: String) {
        if (isNotEmpty()) append('\n')
        append('<').append(tag).append(">\n")
        append(body.trim().ifEmpty { NONE })
        append("\n</").append(tag).append(">\n")
    }
}
