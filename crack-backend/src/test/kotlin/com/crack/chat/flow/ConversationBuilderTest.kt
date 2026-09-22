package com.crack.chat.flow

import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole as AiRole
import com.crack.message.dto.MessageView
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.service.MessageService
import com.crack.prompt.config.PromptProperties
import com.crack.prompt.context.RecordedTurnSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.time.LocalDateTime

class ConversationBuilderTest {

    private var seq = 0
    private fun view(role: MessageRole, content: String, kind: MessageKind = MessageKind.NORMAL, turn: Int = 0) = MessageView(
        id = seq.toLong() + 1, seq = seq++, turn = turn, role = role, kind = kind, content = content,
        variantIndex = null, variantCount = 0, edited = false, createdAt = LocalDateTime.now(),
    )

    /** [recorded]가 null이면 RecordedTurnSource 빈이 없는 상태(T14 전). */
    private fun builder(
        service: MessageService = mock(),
        recorded: Int? = null,
        properties: PromptProperties = PromptProperties(),
    ): ConversationBuilder {
        val factory = StaticListableBeanFactory()
        if (recorded != null) factory.addBean("recorded", RecordedTurnSource { recorded })
        return ConversationBuilder(service, properties, factory.getBeanProvider(RecordedTurnSource::class.java))
    }

    private val builder = builder()

    /** 프롤로그(턴 0) + 1..[turns]턴의 USER/ASSISTANT 쌍 */
    private fun conversation(turns: Int): List<MessageView> =
        listOf(view(MessageRole.ASSISTANT, "프롤로그", MessageKind.PROLOGUE, turn = 0)) +
            (1..turns).flatMap { t -> listOf(view(MessageRole.USER, "u$t", turn = t), view(MessageRole.ASSISTANT, "a$t", turn = t)) }

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

        assertThat(builder(service).build(7L, beforeSeq = 1)).containsExactly(ChatMessage(AiRole.USER, "a"))
    }

    // ---- 원문 범위 (DESIGN.md §6.1) ----

    @Test
    fun `기록 전에는 프롤로그부터 전부 넣는다`() {
        val service = mock<MessageService> { on { list(7L) } doReturn conversation(5) }

        val result = builder(service).build(7L)

        assertThat(result.first()).isEqualTo(ChatMessage(AiRole.ASSISTANT, "프롤로그"))
        assertThat(result).hasSize(11)
        assertThat(builder(service).rawWindow(7L, conversation(5))).isEqualTo(RawWindow(0, -2))
    }

    @Test
    fun `기록 후에는 recorded - overlap 턴 초과만 넣고 프롤로그는 뺀다`() {
        val service = mock<MessageService> { on { list(7L) } doReturn conversation(15) }

        val result = builder(service, recorded = 10).build(7L)

        // overlap 2 → 턴 9부터
        assertThat(result.first()).isEqualTo(ChatMessage(AiRole.USER, "u9"))
        assertThat(result.last()).isEqualTo(ChatMessage(AiRole.ASSISTANT, "a15"))
        assertThat(result).hasSize(14)
    }

    @Test
    fun `원문 범위 경계`() {
        // afterTurn = max(recorded - overlap, maxTurn - maxRaw), 기록 후에는 0 이상
        assertThat(ConversationBuilder.rawWindow(0, 5, 2, 30).afterTurn).isEqualTo(-2)
        assertThat(ConversationBuilder.rawWindow(10, 15, 2, 30).afterTurn).isEqualTo(8)
        assertThat(ConversationBuilder.rawWindow(1, 3, 2, 30).afterTurn).isEqualTo(0) // 프롤로그는 첫 기록 이후 뺀다
        assertThat(ConversationBuilder.rawWindow(0, 40, 2, 30).afterTurn).isEqualTo(10) // 상한: 최근 30턴(11..40)
        assertThat(ConversationBuilder.rawWindow(10, 100, 2, 30).afterTurn).isEqualTo(70) // 기록이 밀려도 상한
        assertThat(ConversationBuilder.rawWindow(0, 30, 2, 30).afterTurn).isEqualTo(0) // 딱 30턴이면 1..30
        assertThat(ConversationBuilder.rawWindow(0, 31, 2, 30).afterTurn).isEqualTo(1)
        assertThat(ConversationBuilder.rawWindow(20, 20, 0, 30).afterTurn).isEqualTo(20) // 다 기록했고 겹침 0이면 원문 없음

        val window = ConversationBuilder.rawWindow(10, 15, 2, 30)
        assertThat(window.includes(8)).isFalse()
        assertThat(window.includes(9)).isTrue()
    }

    @Test
    fun `원문 상한은 설정값을 쓴다`() {
        val service = mock<MessageService> { on { list(7L) } doReturn conversation(10) }

        val result = builder(service, properties = PromptProperties(maxRawTurns = 3)).build(7L)

        assertThat(result.map { it.content }).containsExactly("u8", "a8", "u9", "a9", "u10", "a10")
    }

    @Test
    fun `재생성 대상 앞까지를 기준으로 범위를 정한다`() {
        val views = conversation(10)
        val service = mock<MessageService> { on { list(7L) } doReturn views }
        val lastAssistantSeq = views.last().seq

        val result = builder(service, properties = PromptProperties(maxRawTurns = 2))
            .build(7L, beforeSeq = lastAssistantSeq, turnInstruction = "더 짧게")

        assertThat(result.map { it.content }).containsExactly("u9", "a9", "[지시]\n더 짧게\n\nu10")
    }

    @Test
    fun `가상 입력은 맨 끝 USER로 넣고 지시를 그 앞에 붙인다`() {
        val views = listOf(view(MessageRole.USER, "안녕"), view(MessageRole.ASSISTANT, "응답"))

        assertThat(builder.build(views, "지시", pendingInput = "다음")).containsExactly(
            ChatMessage(AiRole.USER, "안녕"),
            ChatMessage(AiRole.ASSISTANT, "응답"),
            ChatMessage(AiRole.USER, "[지시]\n지시\n\n다음"),
        )
    }

    @Test
    fun `지시 합치기`() {
        assertThat(ConversationBuilder.combine("a", null, " ", "b")).isEqualTo("a\nb")
        assertThat(ConversationBuilder.combine(null, "")).isNull()
    }
}
