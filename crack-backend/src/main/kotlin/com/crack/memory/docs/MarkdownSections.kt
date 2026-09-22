package com.crack.memory.docs

/**
 * 마크다운 문서를 제목(`#`) 단위 섹션으로 다루는 순수 함수 모음.
 *
 * - 섹션은 "제목 줄 시작 ~ 같은 수준 이상(`#` 개수가 같거나 적은) 다음 제목 줄 직전"이다.
 *   `##` 섹션 안의 `###` 하위 제목은 섹션 본문에 포함된다.
 * - 코드 펜스(``` 또는 ~~~) 안의 `#` 줄은 제목으로 보지 않는다.
 * - 교체·삭제·추가는 대상 섹션 구간만 바꾼다. **섹션 밖 텍스트는 한 글자도 건드리지 않는다**
 *   (앞뒤 공백, 줄바꿈 종류(LF/CRLF), 파일 끝 줄바꿈 유무 포함).
 */
object MarkdownSections {

    /**
     * 문서 안의 섹션 하나의 위치.
     *
     * @property headingStart 제목 줄의 시작 오프셋
     * @property bodyStart 제목 줄 다음(줄바꿈 포함) 오프셋. 제목이 파일 끝이면 `text.length`
     * @property end 섹션 끝(다음 제목 줄 시작 또는 `text.length`), 배타적
     */
    data class Section(
        val level: Int,
        val title: String,
        val headingStart: Int,
        val bodyStart: Int,
        val end: Int,
    ) {
        fun heading(text: String): String = text.substring(headingStart, bodyStart)
        fun body(text: String): String = text.substring(bodyStart, end)
        fun full(text: String): String = text.substring(headingStart, end)
    }

    private data class Heading(val level: Int, val title: String, val lineStart: Int, val nextLineStart: Int)

    private val HEADING = Regex("""^(#{1,6})[ \t]+(.*?)[ \t]*$""")
    private val FENCE = Regex("""^[ \t]{0,3}(```|~~~)""")

    /** 문서의 줄바꿈 문자. CRLF가 하나라도 있으면 CRLF, 아니면 LF. */
    fun lineSeparator(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

    /** 코드 펜스 밖의 모든 제목 줄. */
    private fun headings(text: String): List<Heading> {
        val result = mutableListOf<Heading>()
        var inFence = false
        var pos = 0
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos)
            val next = if (nl < 0) text.length else nl + 1
            var line = text.substring(pos, if (nl < 0) text.length else nl).removeSuffix("\r")
            if (pos == 0) line = line.removePrefix("﻿")
            if (FENCE.containsMatchIn(line)) {
                inFence = !inFence
            } else if (!inFence) {
                HEADING.find(line)?.let { m ->
                    val title = m.groupValues[2]
                    if (title.isNotEmpty()) result += Heading(m.groupValues[1].length, title, pos, next)
                }
            }
            pos = next
        }
        return result
    }

    /** 지정한 수준(`##`이면 2)의 섹션 목록을 문서 순서대로 돌려준다. */
    fun sections(text: String, level: Int = 2): List<Section> {
        val hs = headings(text)
        return hs.mapIndexedNotNull { i, h ->
            if (h.level != level) return@mapIndexedNotNull null
            val end = hs.drop(i + 1).firstOrNull { it.level <= level }?.lineStart ?: text.length
            Section(h.level, h.title, h.lineStart, h.nextLineStart, end)
        }
    }

    /** 제목이 [title]인 첫 섹션. 제목은 앞뒤 공백을 무시하고 정확히 비교한다. */
    fun find(text: String, title: String, level: Int = 2): Section? =
        sections(text, level).firstOrNull { it.title.trim() == title.trim() }

    /**
     * 섹션 본문(제목 줄 제외)을 원문 그대로 돌려준다. 없으면 null.
     * 본문 끝의 빈 줄까지 포함되므로 내용만 필요하면 `trim()` 해서 쓴다.
     */
    fun readSection(text: String, title: String, level: Int = 2): String? =
        find(text, title, level)?.body(text)

    /**
     * 섹션 본문을 [newBody]로 교체한다. 섹션이 없으면 문서 끝에 새로 추가한다.
     *
     * - 기존 제목 줄은 그대로 둔다.
     * - [newBody]의 앞뒤 빈 줄은 정리하고, 줄바꿈은 문서의 줄바꿈 문자로 맞춘다.
     * - 원래 섹션 끝에 있던 빈 줄(다음 제목과의 간격)은 유지한다.
     */
    fun replaceSection(text: String, title: String, newBody: String, level: Int = 2): String {
        val section = find(text, title, level) ?: return appendSection(text, title, newBody, level)
        return text.substring(0, section.headingStart) +
            renderReplacement(text, section, newBody) +
            text.substring(section.end)
    }

    /** 문서 끝에 새 섹션을 추가한다. 같은 제목이 이미 있어도 추가한다. */
    fun appendSection(text: String, title: String, body: String, level: Int = 2): String {
        val sep = lineSeparator(text)
        val sb = StringBuilder(text)
        if (text.isNotEmpty()) {
            if (!text.endsWith("\n")) sb.append(sep)
            if (!sb.endsWith("$sep$sep") && sb.toString() != sep) sb.append(sep)
        }
        sb.append(renderSection(level, title, body, sep))
        return sb.toString()
    }

    /** 섹션 구간(제목 줄 포함)을 통째로 지운다. */
    fun removeSection(text: String, section: Section): String =
        text.substring(0, section.headingStart) + text.substring(section.end)

    /** `## 제목` + 본문 + 끝 줄바꿈 형태의 새 섹션 텍스트. */
    fun renderSection(level: Int, title: String, body: String, sep: String = "\n"): String {
        val normalized = normalizeBody(body, sep)
        return "#".repeat(level) + " " + title.trim() + sep + normalized
    }

    /** 본문의 앞뒤 빈 줄을 제거하고 줄바꿈을 [sep]로 통일한다. 비어 있지 않으면 줄바꿈으로 끝난다. */
    fun normalizeBody(body: String, sep: String = "\n"): String {
        val lines = body.replace("\r\n", "\n").split("\n")
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return ""
        val last = lines.indexOfLast { it.isNotBlank() }
        return lines.subList(first, last + 1).joinToString(sep) { it.trimEnd() } + sep
    }

    private fun renderReplacement(text: String, section: Section, newBody: String): String {
        val sep = lineSeparator(text)
        var heading = section.heading(text)
        if (!heading.endsWith("\n")) heading += sep // 제목이 파일 끝에 줄바꿈 없이 있던 경우
        val oldBody = section.body(text)
        val body = normalizeBody(newBody, sep)

        // 원래 본문 끝의 공백 구간(마지막 내용 줄의 줄바꿈 이후)을 다음 섹션과의 간격으로 유지한다.
        val trailing = oldBody.substring(oldBody.trimEnd().length)
        var gap = when {
            trailing.startsWith("\r\n") -> trailing.substring(2)
            trailing.startsWith("\n") -> trailing.substring(1)
            else -> trailing
        }
        if (oldBody.isBlank()) gap = trailing // 빈 본문이면 공백 전체가 간격이다
        val followedByHeading = section.end < text.length
        if (followedByHeading && body.isNotEmpty() && gap.isEmpty()) gap = sep
        if (!followedByHeading && trailing.isEmpty() && body.isNotEmpty() && !text.endsWith("\n")) {
            // 파일 끝에 줄바꿈이 없던 문서는 그대로 줄바꿈 없이 끝낸다
            return heading + body.removeSuffix(sep)
        }
        return heading + body + gap
    }
}
