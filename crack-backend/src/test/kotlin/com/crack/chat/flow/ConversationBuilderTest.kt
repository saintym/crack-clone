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

    /** 프롤로그 없이 1..[turns]턴. 한 턴(유저+응답)이 정확히 [charsPerTurn]자다 */
    private fun sizedConversation(turns: Int, charsPerTurn: Int): List<MessageView> =
        (1..turns).flatMap { t ->
            listOf(
                view(MessageRole.USER, "u".repeat(charsPerTurn / 2), turn = t),
                view(MessageRole.ASSISTANT, "a".repeat(charsPerTurn - charsPerTurn / 2), turn = t),
            )
        }

    private fun window(
        views: List<MessageView>,
        recorded: Int = 0,
        budgetChars: Int = 6000,
        minTurns: Int = 2,
        maxRawTurns: Int = 30,
    ) = ConversationBuilder.rawWindow(views, recorded, budgetChars, minTurns, maxRawTurns)

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
        // 프롤로그 4자 + 턴마다 4자 × 5턴
        assertThat(builder(service).rawWindow(7L, conversation(5))).isEqualTo(RawWindow(0, -1, 6, 24))
    }

    @Test
    fun `첫 기록 이후에는 프롤로그를 뺀다`() {
        val service = mock<MessageService> { on { list(7L) } doReturn conversation(15) }

        val result = builder(service, recorded = 10).build(7L)

        // 기록한 구간도 예산 안이면 원문에 남는다. 빠지는 것은 프롤로그(턴 0)뿐이다
        assertThat(result.first()).isEqualTo(ChatMessage(AiRole.USER, "u1"))
        assertThat(result.last()).isEqualTo(ChatMessage(AiRole.ASSISTANT, "a15"))
        assertThat(result).hasSize(30)
        assertThat(builder(service, recorded = 10).rawWindow(7L, conversation(15)).afterTurn).isEqualTo(0)
    }

    // ---- 글자 예산 (T47) ----

    @Test
    fun `예산을 넘기 전까지 최근 턴부터 담는다`() {
        val views = sizedConversation(10, charsPerTurn = 1000)

        val window = window(views, budgetChars = 3500)

        assertThat(window.turnCount).isEqualTo(3)
        assertThat(window.chars).isEqualTo(3000)
        assertThat(window.afterTurn).isEqualTo(7)
    }

    @Test
    fun `턴을 쪼개지 않는다`() {
        // 예산 2,500자 = 두 턴(2,000자) + 세 번째 턴의 절반. 반 턴은 넣지 않는다
        val views = sizedConversation(10, charsPerTurn = 1000)

        val window = window(views, budgetChars = 2500)

        val turns = views.filter { window.includes(it.turn) }.map { it.turn }
        assertThat(turns).containsExactly(9, 9, 10, 10)
        assertThat(window.chars).isEqualTo(2000)
    }

    @Test
    fun `한 턴이 예산보다 길어도 최소 턴 수는 넣는다`() {
        val views = sizedConversation(5, charsPerTurn = 4000)

        val window = window(views, budgetChars = 1000, minTurns = 2)

        assertThat(window.turnCount).isEqualTo(2)
        assertThat(window.chars).isEqualTo(8000)
        assertThat(window.afterTurn).isEqualTo(3)
    }

    @Test
    fun `최소 턴 수는 1 미만으로 내려가지 않는다`() {
        val views = sizedConversation(3, charsPerTurn = 4000)

        val window = window(views, budgetChars = 0, minTurns = 0)

        assertThat(window.turnCount).isEqualTo(1)
        assertThat(window.afterTurn).isEqualTo(2)
    }

    @Test
    fun `예산이 남아도 안전 상한까지만 담는다`() {
        val views = sizedConversation(40, charsPerTurn = 10)

        val window = window(views, budgetChars = 1_000_000, maxRawTurns = 30)

        assertThat(window.turnCount).isEqualTo(30)
        assertThat(window.afterTurn).isEqualTo(10)
        assertThat(window.includes(10)).isFalse()
        assertThat(window.includes(11)).isTrue()
    }

    @Test
    fun `빈 대화는 아무것도 담지 않는다`() {
        assertThat(window(emptyList())).isEqualTo(RawWindow(0, -1, 0, 0))
    }

    @Test
    fun `예산은 설정값을 쓴다`() {
        val service = mock<MessageService> { on { list(7L) } doReturn sizedConversation(10, charsPerTurn = 1000) }

        val result = builder(service, properties = PromptProperties(rawBudgetChars = 2000)).build(7L)

        assertThat(result).hasSize(4) // 2턴 × (유저 + 응답)
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
