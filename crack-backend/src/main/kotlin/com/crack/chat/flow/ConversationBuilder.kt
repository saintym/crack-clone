package com.crack.chat.flow

import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole as AiRole
import com.crack.message.dto.MessageView
import com.crack.message.entity.MessageRole
import com.crack.message.service.MessageService
import com.crack.prompt.config.PromptProperties
import com.crack.prompt.context.RecordedTurnSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * 대화 원문 범위 (DESIGN.md §6.1). `turn_no > afterTurn`인 메시지만 넣는다.
 *
 * @property recordedThroughTurn 계산에 쓴 마지막 기록 턴
 * @property afterTurn 이 턴 **초과**만 넣는다. 음수면 프롤로그(턴 0)까지 들어간다
 */
data class RawWindow(val recordedThroughTurn: Int, val afterTurn: Int) {
    fun includes(turn: Int): Boolean = turn > afterTurn
}

/**
 * 메시지 저장소(T03)에서 AI 입력 메시지를 만든다.
 *
 * - **원문 범위(§6.1, T13):** `turn_no > recorded_through_turn - overlap`, 상한은 최근 `max-raw-turns`턴.
 *   기록한 적이 있으면(`recorded_through_turn >= 1`) 프롤로그(턴 0)는 뺀다. 마지막 기록 턴은 [RecordedTurnSource] 빈에서
 *   읽고, 빈이 없으면 0으로 본다(T14 전).
 * - 저장된 `content`는 감정 태그가 빠진 상태이므로 그대로 넘긴다(§5.3).
 * - 같은 역할이 연속되면(예: 이어쓰기 응답들) 빈 줄로 합쳐 역할이 번갈아 오게 한다.
 * - 지시(BOTTOM 슬롯: 이어쓰기, 재생성 지시, 지속 지시)는 저장하지 않고 이번 요청에만 넣는다.
 *   마지막 메시지가 USER면 그 앞에 `[지시]` 블록으로 붙이고, 아니면 `[지시]`만 담은 USER 메시지를 덧붙인다(§6).
 */
@Component
class ConversationBuilder(
    private val messageService: MessageService,
    private val properties: PromptProperties,
    private val recordedTurnSources: ObjectProvider<RecordedTurnSource>,
) {

    /**
     * 원문 범위를 적용한 AI 입력.
     *
     * @param beforeSeq 이 seq **미만**의 메시지만 넣는다(재생성 대상 앞까지). null이면 전부.
     */
    fun build(storyId: Long, beforeSeq: Int? = null, turnInstruction: String? = null): List<ChatMessage> {
        val views = messageService.list(storyId).filter { beforeSeq == null || it.seq < beforeSeq }
        val window = rawWindow(storyId, views)
        return build(views.filter { window.includes(it.turn) }, turnInstruction)
    }

    /** [views]에 대한 원문 범위. 기준 최대 턴은 [views] 중 최대 턴이다. */
    fun rawWindow(storyId: Long, views: List<MessageView>): RawWindow =
        rawWindow(
            recordedThroughTurn = recordedThroughTurn(storyId),
            maxTurn = views.maxOfOrNull { it.turn } ?: 0,
            overlapTurns = properties.overlapTurns,
            maxRawTurns = properties.maxRawTurns,
        )

    fun recordedThroughTurn(storyId: Long): Int =
        (recordedTurnSources.getIfAvailable() ?: RecordedTurnSource.NONE).recordedThroughTurn(storyId).coerceAtLeast(0)

    /**
     * 범위를 적용하지 않고 [views]를 그대로 옮긴다.
     *
     * @param pendingInput 저장하지 않은 가상 유저 입력(preview). 맨 끝 USER 메시지로 넣는다
     */
    fun build(views: List<MessageView>, turnInstruction: String?, pendingInput: String? = null): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        for (view in views) {
            append(result, if (view.role == MessageRole.USER) AiRole.USER else AiRole.ASSISTANT, view.content)
        }
        pendingInput?.takeIf { it.isNotBlank() }?.let { append(result, AiRole.USER, it) }

        val instruction = turnInstruction?.trim()?.takeIf { it.isNotEmpty() } ?: return result
        val block = "$INSTRUCTION_HEADER\n$instruction"
        val last = result.lastOrNull()
        if (last != null && last.role == AiRole.USER) {
            result[result.lastIndex] = last.copy(content = block + "\n\n" + last.content)
        } else {
            result += ChatMessage(AiRole.USER, block)
        }
        return result
    }

    private fun append(result: MutableList<ChatMessage>, role: AiRole, content: String) {
        val last = result.lastOrNull()
        if (last != null && last.role == role) {
            result[result.lastIndex] = last.copy(content = last.content + "\n\n" + content)
        } else {
            result += ChatMessage(role, content)
        }
    }

    companion object {
        const val INSTRUCTION_HEADER = "[지시]"

        /** 이어쓰기 가상 지시. 저장하지 않는다. */
        const val CONTINUE_INSTRUCTION =
            "직전 응답에서 끊긴 장면을 자연스럽게 이어서 계속 써 주세요. 주인공(사용자)의 행동이나 대사는 만들지 마세요."

        /** 여러 지시를 한 블록으로 합친다. 빈 값은 뺀다. 모두 비면 null. */
        fun combine(vararg instructions: String?): String? =
            instructions.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString("\n")
                .ifEmpty { null }

        /**
         * 원문 범위 계산 (DESIGN.md §6.1).
         *
         * `afterTurn = max(recorded - overlap, maxTurn - maxRaw)`. 기록한 적이 있으면 0 이상(프롤로그 제외).
         * [maxRawTurns]는 1 이상, [overlapTurns]는 0 이상으로 보정한다.
         */
        fun rawWindow(recordedThroughTurn: Int, maxTurn: Int, overlapTurns: Int, maxRawTurns: Int): RawWindow {
            val recorded = recordedThroughTurn.coerceAtLeast(0)
            var after = maxOf(recorded - overlapTurns.coerceAtLeast(0), maxTurn - maxRawTurns.coerceAtLeast(1))
            if (recorded >= 1) after = maxOf(after, 0)
            return RawWindow(recorded, after)
        }
    }
}
