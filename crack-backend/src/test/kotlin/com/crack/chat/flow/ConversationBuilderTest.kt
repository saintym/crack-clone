package com.crack.chat.flow

import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole as AiRole
import com.crack.message.dto.MessageView
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.service.MessageService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.LocalDateTime

class ConversationBuilderTest {

    private var seq = 0
    private fun view(role: MessageRole, content: String, kind: MessageKind = MessageKind.NORMAL) = MessageView(
        id = seq.toLong() + 1, seq = seq++, turn = 0, role = role, kind = kind, content = content,
        variantIndex = null, variantCount = 0, edited = false, createdAt = LocalDateTime.now(),
    )

    private val builder = ConversationBuilder(mock<MessageService>())

    @Test
    fun `저장된 메시지를 역할대로 옮기고 연속된 같은 역할은 합친다`() {
        val views = listOf(
            view(MessageRole.ASSISTANT, "프롤로그", MessageKind.PROLOGUE),
            view(MessageRole.USER, "안녕"),
            view(MessageRole.ASSISTANT, "응답"),
            view(MessageRole.ASSISTANT, "이어쓰기", MessageKind.CONTINUATION),
        )

        assertThat(builder.build(views, null)).containsExactly(
            ChatMessage(AiRole.ASSISTANT, "프롤로그"),
            ChatMessage(AiRole.USER, "안녕"),
            ChatMessage(AiRole.ASSISTANT, "응답\n\n이어쓰기"),
        )
    }

    @Test
    fun `마지막이 USER면 지시를 그 앞에 붙인다`() {
        val views = listOf(view(MessageRole.USER, "문을 연다"))

        assertThat(builder.build(views, "더 짧게")).containsExactly(
            ChatMessage(AiRole.USER, "[지시]\n더 짧게\n\n문을 연다"),
        )
    }

    @Test
    fun `마지막이 ASSISTANT면 지시만 담은 USER 메시지를 덧붙인다`() {
        val views = listOf(view(MessageRole.USER, "안녕"), view(MessageRole.ASSISTANT, "응답"))

        val result = builder.build(views, ConversationBuilder.CONTINUE_INSTRUCTION)
        assertThat(result).hasSize(3)
        assertThat(result.last()).isEqualTo(ChatMessage(AiRole.USER, "[지시]\n${ConversationBuilder.CONTINUE_INSTRUCTION}"))
    }

    @Test
    fun `빈 지시는 넣지 않는다`() {
        val views = listOf(view(MessageRole.USER, "안녕"))
        assertThat(builder.build(views, "  ")).containsExactly(ChatMessage(AiRole.USER, "안녕"))
    }

    @Test
    fun `beforeSeq 미만만 넣는다`() {
        val views = listOf(view(MessageRole.USER, "a"), view(MessageRole.ASSISTANT, "b"), view(MessageRole.USER, "c"))
        val service = mock<MessageService> { on { list(7L) } doReturn views }

        assertThat(ConversationBuilder(service).build(7L, beforeSeq = 1)).containsExactly(ChatMessage(AiRole.USER, "a"))
    }

    @Test
    fun `지시 합치기`() {
        assertThat(ConversationBuilder.combine("a", null, " ", "b")).isEqualTo("a\nb")
        assertThat(ConversationBuilder.combine(null, "")).isNull()
    }
}
