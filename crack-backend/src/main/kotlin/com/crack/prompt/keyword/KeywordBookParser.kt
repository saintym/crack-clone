package com.crack.prompt.keyword

import com.crack.memory.docs.MarkdownSections

/**
 * 키워드북 항목 하나. [id]는 `##` 제목, [content]는 주입할 내용(앞뒤 공백 제거)이다.
 */
data class KeywordBookEntry(val id: String, val keys: List<String>, val content: String) {
    fun toKeywordEntry(): KeywordEntry = KeywordEntry(id, keys)
}

/**
 * `keywords.md`(DESIGN.md §8.3)를 [KeywordBookEntry] 목록으로 파싱한다. 주입은 T17이 한다.
 *
 * ```markdown
 * ## 천마신교
 * 키워드: 천마신교, 마교, 신교
 * (주입할 내용)
 * ```
 *
 * 규칙:
 * - `##` 섹션 하나가 항목 하나다. 파일 순서를 유지한다(위에 있는 항목이 우선).
 *   첫 `##` 앞의 텍스트(`# 키워드북` 같은 머리말)는 무시한다. `###` 하위 제목은 내용에 포함된다.
 *   코드 펜스 안의 `##`는 제목으로 보지 않는다([MarkdownSections] 규칙).
 * - 섹션 본문의 **첫 비어 있지 않은 줄**이 `키워드:`(콜론 앞뒤 공백, 전각 콜론 `：` 허용)로 시작하면 키워드 줄이다.
 *   키는 `,`·`，`·`、`로 나누고 앞뒤 공백을 제거한 뒤 빈 키와 중복을 뺀다.
 * - **키워드 줄이 없으면** 제목을 유일한 키로 쓴다. 이때 본문 전체가 내용이 된다.
 *   키워드 줄은 있는데 키가 하나도 없을 때(`키워드:`)도 제목을 키로 쓴다.
 * - **내용이 비어 있으면** 항목을 건너뛴다(주입할 것이 없는데 동시 발동 수만 차지하므로).
 * - 제목이 같은 항목이 여럿이면 **첫 항목만** 남긴다(id가 곧 매칭 결과라 유일해야 한다).
 */
object KeywordBookParser {

    private val KEYWORD_LINE = Regex("""^\s*키워드\s*[:：](.*)$""")
    private val KEY_SEPARATOR = Regex("""[,，、]""")

    fun parse(text: String): List<KeywordBookEntry> {
        val seen = HashSet<String>()
        val result = mutableListOf<KeywordBookEntry>()
        for (section in MarkdownSections.sections(text, level = 2)) {
            val id = section.title.trim()
            if (id.isEmpty() || id in seen) continue
            val entry = parseSection(id, section.body(text)) ?: continue
            seen += id
            result += entry
        }
        return result
    }

    private fun parseSection(id: String, body: String): KeywordBookEntry? {
        val lines = body.lines().map { it.removeSuffix("\r") }
        val firstIdx = lines.indexOfFirst { it.isNotBlank() }
        if (firstIdx < 0) return null

        val keywordMatch = KEYWORD_LINE.find(lines[firstIdx])
        val (keys, contentLines) = if (keywordMatch != null) {
            parseKeys(keywordMatch.groupValues[1]) to lines.drop(firstIdx + 1)
        } else {
            listOf(id) to lines.drop(firstIdx)
        }

        val content = contentLines.joinToString("\n").trim()
        if (content.isEmpty()) return null
        return KeywordBookEntry(id, keys.ifEmpty { listOf(id) }, content)
    }

    private fun parseKeys(raw: String): List<String> =
        raw.split(KEY_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
