package com.crack.chat

import com.crack.chat.dto.SegmentType
import com.crack.chat.service.MessageParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessageParserTest {

    private lateinit var parser: MessageParser

    @BeforeEach
    fun setUp() {
        parser = MessageParser()
    }

    // --- 감정 추출 ---

    @Test
    fun `단일 감정을 추출한다`() {
        val emotions = parser.extractEmotions("[감정: 기쁨]")

        assertEquals(listOf("기쁨"), emotions)
    }

    @Test
    fun `복수 감정을 추출한다`() {
        val emotions = parser.extractEmotions("[감정: 부끄러움, 설렘]")

        assertEquals(listOf("부끄러움", "설렘"), emotions)
    }

    @Test
    fun `감정 태그가 없으면 빈 리스트를 반환한다`() {
        val emotions = parser.extractEmotions("그냥 대사만 있는 텍스트")

        assertTrue(emotions.isEmpty())
    }

    @Test
    fun `여러 감정 태그에서 모든 감정을 추출한다`() {
        val text = """
            [감정: 놀람]
            *눈을 크게 뜬다*
            "뭐야!"
            [감정: 분노, 당황]
            "화난다고!"
        """.trimIndent()

        val emotions = parser.extractEmotions(text)

        assertEquals(listOf("놀람", "분노", "당황"), emotions)
    }

    // --- 세그먼트 파싱 ---

    @Test
    fun `전형적인 AI 응답을 세그먼트로 파싱한다`() {
        val response = """
            [감정: 부끄러움, 설렘]
            *볼이 살짝 붉어지며 시선을 피한다*
            "그, 그런 말 갑자기 하면... 당황스럽잖아."
            *작은 목소리로 중얼거리며 머리카락을 만지작거린다*
            "...근데, 고마워."
        """.trimIndent()

        val result = parser.parse(response)

        assertEquals(listOf("부끄러움", "설렘"), result.emotions)

        val segments = result.segments
        assertTrue(segments.any { it.type == SegmentType.EMOTION && it.content.contains("부끄러움") })
        assertTrue(segments.any { it.type == SegmentType.ACTION && it.content.contains("볼이 살짝 붉어지며") })
        assertTrue(segments.any { it.type == SegmentType.DIALOGUE && it.content.contains("당황스럽잖아") })
        assertTrue(segments.any { it.type == SegmentType.ACTION && it.content.contains("머리카락을 만지작거린다") })
        assertTrue(segments.any { it.type == SegmentType.DIALOGUE && it.content.contains("고마워") })
    }

    @Test
    fun `대사만 있는 응답을 파싱한다`() {
        val response = "\"안녕하세요!\""

        val result = parser.parse(response)

        assertEquals(1, result.segments.size)
        assertEquals(SegmentType.DIALOGUE, result.segments[0].type)
        assertEquals("안녕하세요!", result.segments[0].content)
    }

    @Test
    fun `행동만 있는 응답을 파싱한다`() {
        val response = "*조용히 고개를 끄덕인다*"

        val result = parser.parse(response)

        assertEquals(1, result.segments.size)
        assertEquals(SegmentType.ACTION, result.segments[0].type)
        assertEquals("조용히 고개를 끄덕인다", result.segments[0].content)
    }

    @Test
    fun `순수 텍스트는 NARRATION으로 파싱한다`() {
        val response = "특별한 형식 없는 텍스트"

        val result = parser.parse(response)

        assertTrue(result.segments.any { it.type == SegmentType.NARRATION })
    }

    @Test
    fun `빈 응답은 빈 세그먼트를 반환한다`() {
        val result = parser.parse("")

        assertTrue(result.segments.isEmpty())
        assertTrue(result.emotions.isEmpty())
    }

    @Test
    fun `raw 필드에 원본 응답이 보존된다`() {
        val original = "[감정: 기쁨]\n*웃으며*\n\"좋아!\""
        val result = parser.parse(original)

        assertEquals(original, result.raw)
    }
}
