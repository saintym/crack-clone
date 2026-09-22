package com.crack.migration

import com.crack.message.entity.MessageRole
import com.crack.migration.legacy.LegacyChatParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LegacyChatParserTest {

    @Test
    fun `머리 줄의 역할을 그대로 붙이고 파일 제목은 버린다`() {
        val text = "# 최근 대화\n\n\n## USER\n안녕\n\n## ASSISTANT\n[감정: 기쁨]\n*웃는다*\n\n\"반가워\"\n\n## USER\n둘째 줄\n셋째 줄\n"

        val messages = LegacyChatParser.parse(text)

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), messages.map { it.role })
        assertEquals("안녕", messages[0].content)
        assertEquals("[감정: 기쁨]\n*웃는다*\n\n\"반가워\"", messages[1].content)
        assertEquals("둘째 줄\n셋째 줄", messages[2].content)
    }

    @Test
    fun `빈 메시지와 연속된 같은 역할도 순서대로 돌려준다`() {
        val text = "# 최근 대화\n\n## USER\n하나\n\n## ASSISTANT\n\n\n## USER\n둘\n\n## USER\n셋\n"

        val messages = LegacyChatParser.parse(text)

        assertEquals(4, messages.size)
        assertEquals(listOf("하나", "", "둘", "셋"), messages.map { it.content })
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
    }

    @Test
    fun `CRLF 줄바꿈과 머리 줄 뒤 공백을 허용하고 본문 중간의 비슷한 줄은 머리로 보지 않는다`() {
        val text = "## USER \r\n안녕\r\n## ASSISTANT\r\n### USER 아님\r\n## USER가 한 말\r\n"

        val messages = LegacyChatParser.parse(text)

        assertEquals(2, messages.size)
        assertEquals("안녕", messages[0].content)
        assertEquals("### USER 아님\n## USER가 한 말", messages[1].content)
    }

    @Test
    fun `머리 줄이 없으면 빈 목록`() {
        assertEquals(emptyList<Any>(), LegacyChatParser.parse("# 최근 대화\n\n"))
    }

    @Test
    fun `첫 줄 감정 태그를 떼어 낸다`() {
        val split = LegacyChatParser.splitEmotion("[감정: 경계, 차분함]\n\n*검을 쥔다*")
        assertEquals("경계, 차분함", split.emotion)
        assertEquals("*검을 쥔다*", split.content)

        val spaced = LegacyChatParser.splitEmotion("[ 감정 : 호기심 ]  \n본문")
        assertEquals("호기심", spaced.emotion)
        assertEquals("본문", spaced.content)
    }

    @Test
    fun `첫 줄이 감정 태그가 아니면 그대로 둔다`() {
        val text = "*웃는다* [감정: 기쁨]\n본문"
        val split = LegacyChatParser.splitEmotion(text)
        assertNull(split.emotion)
        assertEquals(text, split.content)
    }
}
