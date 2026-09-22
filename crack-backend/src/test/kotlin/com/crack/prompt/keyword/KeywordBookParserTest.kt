package com.crack.prompt.keyword

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeywordBookParserTest {

    @Test
    fun `DESIGN 형식을 파일 순서대로 파싱한다`() {
        val md = """
            |# 키워드북
            |머리말은 무시한다.
            |
            |## 천마신교
            |키워드: 천마신교, 마교, 신교
            |천마를 교주로 모시는 집단.
            |정파와 적대한다.
            |
            |## 무림맹
            |키워드：무림맹 ，맹주、 정파 ,, 무림맹
            |정파 문파들의 연합.
            |### 조직
            |- 맹주 아래 장로회가 있다.
            |""".trimMargin()

        val entries = KeywordBookParser.parse(md)

        assertEquals(
            listOf(
                KeywordBookEntry(
                    "천마신교",
                    listOf("천마신교", "마교", "신교"),
                    "천마를 교주로 모시는 집단.\n정파와 적대한다.",
                ),
                KeywordBookEntry(
                    "무림맹",
                    listOf("무림맹", "맹주", "정파"),
                    "정파 문파들의 연합.\n### 조직\n- 맹주 아래 장로회가 있다.",
                ),
            ),
            entries,
        )
    }

    @Test
    fun `키워드 줄이 없으면 제목을 키로 쓰고 본문 전체를 내용으로 한다`() {
        val md = """
            |## 설월
            |
            |백발의 검객.
            |키워드: 이 줄은 첫 줄이 아니라 내용이다
            |
            |## 빈 키워드
            |키워드:
            |내용은 있다.
            |""".trimMargin()

        assertEquals(
            listOf(
                KeywordBookEntry("설월", listOf("설월"), "백발의 검객.\n키워드: 이 줄은 첫 줄이 아니라 내용이다"),
                KeywordBookEntry("빈 키워드", listOf("빈 키워드"), "내용은 있다."),
            ),
            KeywordBookParser.parse(md),
        )
    }

    @Test
    fun `내용이 비어 있는 항목은 건너뛴다`() {
        val md = """
            |## 키워드만
            |키워드: 가나, 다라
            |
            |## 완전히 빈 항목
            |
            |## 정상
            |키워드: 정상
            |내용
            |""".trimMargin()

        assertEquals(
            listOf(KeywordBookEntry("정상", listOf("정상"), "내용")),
            KeywordBookParser.parse(md),
        )
    }

    @Test
    fun `같은 제목은 첫 항목만 남긴다`() {
        val md = """
            |## 마교
            |키워드: 마교
            |첫 번째
            |
            |## 마교
            |키워드: 마교, 신교
            |두 번째
            |""".trimMargin()

        assertEquals(listOf(KeywordBookEntry("마교", listOf("마교"), "첫 번째")), KeywordBookParser.parse(md))
    }

    @Test
    fun `CRLF와 코드 펜스를 처리한다`() {
        val md = "## 가\r\n키워드: 가나, 다라\r\n```\r\n## 펜스 안 제목\r\n```\r\n끝\r\n"

        val entries = KeywordBookParser.parse(md)

        assertEquals(1, entries.size)
        assertEquals(listOf("가나", "다라"), entries[0].keys)
        assertEquals("```\n## 펜스 안 제목\n```\n끝", entries[0].content)
    }

    @Test
    fun `빈 문서와 머리말만 있는 문서는 빈 목록이다`() {
        assertEquals(emptyList<KeywordBookEntry>(), KeywordBookParser.parse(""))
        assertEquals(emptyList<KeywordBookEntry>(), KeywordBookParser.parse("# 키워드북\n\n설명만 있다.\n"))
    }

    @Test
    fun `파싱 결과를 매처에 넘기면 우선순위대로 매칭된다`() {
        val md = """
            |## 천마신교
            |키워드: 천마신교, 마교
            |내용1
            |
            |## 무림맹
            |키워드: 무림맹, 정파
            |내용2
            |
            |## 사파
            |키워드: 사파
            |내용3
            |""".trimMargin()
        val book = KeywordBookParser.parse(md)

        val matched = KeywordMatcher().match(book.map { it.toKeywordEntry() }, "사파와 정파, 그리고 마교가 충돌했다.", limit = 2)

        assertEquals(listOf("천마신교", "무림맹"), matched)
    }
}
