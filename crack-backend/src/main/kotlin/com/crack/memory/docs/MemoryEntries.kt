package com.crack.memory.docs

/**
 * 기억 섹션의 목록 항목 한 줄(과 들여쓴 이어지는 줄).
 *
 * @property text 앞의 `- ` 표시를 뗀 내용. 이어지는 줄은 `\n`으로 이어 붙인다
 * @property turns 항목 끝의 `(t21)`, `(… t21)` 같은 턴 표기에서 읽은 턴 번호들
 */
data class MemoryItem(val text: String, val turns: List<Int> = parseTurnMarks(text)) {
    fun toMarkdown(): String = "- " + text.replace("\n", "\n  ")
}

/** `- 대상: 내용 (t21)` 형태의 관계 항목. 콜론이 없으면 [target]은 빈 문자열이다. */
data class RelationEntry(val target: String, val description: String, val item: MemoryItem) {
    val turns: List<Int> get() = item.turns
}

/** `- t18–21: 내용` 또는 `- t18: 내용` 형태의 사건 항목. 턴 표기가 없으면 턴은 null이다. */
data class EventEntry(val fromTurn: Int?, val toTurn: Int?, val description: String, val item: MemoryItem)

/** `(t21)`, `t18–21` 같은 턴 표기 안의 숫자. */
private val TURN_MARK = Regex("""\bt(\d+)(?:\s*[–—~-]\s*t?(\d+))?""")
private val BULLET = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
private val EVENT_PREFIX = Regex("""^t(\d+)(?:\s*[–—~-]\s*t?(\d+))?\s*[:：]\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
private val RELATION_SPLIT = Regex("""^([^:：\n]{1,40})[:：]\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

internal fun parseTurnMarks(text: String): List<Int> =
    TURN_MARK.findAll(text).flatMap { m ->
        listOfNotNull(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull())
    }.toList()

/** 목록 파싱 헬퍼. 항목이 아닌 문단 줄은 무시하고, 들여쓴 줄은 앞 항목에 이어 붙인다. */
object MemoryEntries {

    fun parseItems(body: String?): List<MemoryItem> {
        if (body.isNullOrBlank()) return emptyList()
        val items = mutableListOf<StringBuilder>()
        for (raw in body.replace("\r\n", "\n").split("\n")) {
            val m = BULLET.find(raw)
            if (m != null && !raw.startsWith("    ") && !raw.startsWith("\t")) {
                items += StringBuilder(m.groupValues[1].trimEnd())
            } else if (raw.isNotBlank() && items.isNotEmpty() && (raw.startsWith(" ") || raw.startsWith("\t"))) {
                items.last().append("\n").append(raw.trim())
            }
        }
        return items.map { MemoryItem(it.toString()) }
    }

    fun parseRelations(body: String?): List<RelationEntry> = parseItems(body).map { item ->
        val m = RELATION_SPLIT.find(item.text)
        if (m != null) RelationEntry(m.groupValues[1].trim(), m.groupValues[2].trim(), item)
        else RelationEntry("", item.text, item)
    }

    fun parseEvents(body: String?): List<EventEntry> = parseItems(body).map { item ->
        val m = EVENT_PREFIX.find(item.text)
        if (m != null) {
            val from = m.groupValues[1].toInt()
            val to = m.groupValues[2].toIntOrNull() ?: from
            EventEntry(from, to, m.groupValues[3].trim(), item)
        } else {
            EventEntry(null, null, item.text, item)
        }
    }

    fun render(items: List<MemoryItem>): String = items.joinToString("\n") { it.toMarkdown() }
}

/** `### 제목` 하위 섹션 본문을 찾는다. 제목이 여러 표기 중 하나면 된다. */
internal fun subsection(body: String?, vararg titles: String): String? {
    if (body == null) return null
    val wanted = titles.map { normalizeTitle(it) }.toSet()
    return MarkdownSections.sections(body, 3).firstOrNull { normalizeTitle(it.title) in wanted }?.body(body)
}

/** `소지품·기술·신체`, `소지품/기술/신체`, `소지품 · 기술 · 신체`를 같은 제목으로 본다. */
internal fun normalizeTitle(title: String): String =
    title.replace(Regex("""[\s·ㆍ・/,]+"""), "")
