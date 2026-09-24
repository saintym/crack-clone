package com.crack.message

import com.crack.global.exception.NotFoundException
import com.crack.message.dto.ResponseTags
import com.crack.message.entity.MessageKind
import com.crack.message.service.MessageExporter
import com.crack.message.service.MessageService
import com.crack.story.repository.StoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Import(MessageTestConfig::class) // MessageServiceTest와 같은 컨텍스트를 재사용한다
@Transactional
class MessageExporterTest {

    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var messageExporter: MessageExporter
    @Autowired lateinit var storyRepository: StoryRepository

    @Test
    fun `턴과 역할 머리글로 마크다운을 만들고 감정 값은 넣지 않는다`() {
        val storyId = storyRepository.createTestStory("눈 내리는 밤").id
        messageService.appendAssistant(storyId, "눈이 내린다.", kind = MessageKind.PROLOGUE)
        messageService.appendUser(storyId, "문을 연다")
        val a = messageService.appendAssistant(storyId, "설월이 서 있다.\n\n\"누구요?\"\n", ResponseTags(emotion = "경계심"))
        messageService.addVariant(a.id, "아무도 없다.", ResponseTags("허탈"), null)

        val md = messageExporter.export(storyId)

        val expected = """
            # 눈 내리는 밤

            ## 턴 0 · ASSISTANT

            눈이 내린다.

            ## 턴 1 · USER

            문을 연다

            ## 턴 1 · ASSISTANT

            아무도 없다.

        """.trimIndent()
        assertEquals(expected, md)
        assertFalse(md.contains("허탈"))
    }

    @Test
    fun `메시지가 없으면 제목만 나온다`() {
        val storyId = storyRepository.createTestStory("빈 스토리").id
        assertEquals("# 빈 스토리\n", messageExporter.export(storyId))
    }

    @Test
    fun `없는 스토리는 NotFoundException`() {
        assertThrows<NotFoundException> { messageExporter.export(999_999L) }
    }
}
