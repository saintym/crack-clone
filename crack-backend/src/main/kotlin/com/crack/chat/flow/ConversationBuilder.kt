package com.crack.chat.flow

import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole as AiRole
import com.crack.message.dto.MessageView
import com.crack.message.entity.MessageRole
import com.crack.message.service.MessageService
import org.springframework.stereotype.Component

/**
 * 메시지 저장소(T03)에서 AI 입력 메시지를 만든다.
 *
 * - 지금은 **전체 대화**를 넣는다. 원문 범위 제한(DESIGN.md §6.1)은 T13이 한다.
 * - 저장된 `content`는 감정 태그가 빠진 상태이므로 그대로 넘긴다(§5.3).
 * - 같은 역할이 연속되면(예: 이어쓰기 응답들) 빈 줄로 합쳐 역할이 번갈아 오게 한다.
 * - [turnInstruction](이어쓰기, 재생성 지시)은 저장하지 않고 이번 요청에만 넣는다.
 *   마지막 메시지가 USER면 그 앞에 `[지시]` 블록으로 붙이고, 아니면 `[지시]`만 담은 USER 메시지를 덧붙인다(§6).
 */
@Component
class ConversationBuilder(private val messageService: MessageService) {

    /**
     * @param beforeSeq 이 seq **미만**의 메시지만 넣는다(재생성 대상 앞까지). null이면 전부.
     */
    fun build(storyId: Long, beforeSeq: Int? = null, turnInstruction: String? = null): List<ChatMessage> {
        val views = messageService.list(storyId).filter { beforeSeq == null || it.seq < beforeSeq }
        return build(views, turnInstruction)
    }

    fun build(views: List<MessageView>, turnInstruction: String?): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        for (view in views) {
            val role = if (view.role == MessageRole.USER) AiRole.USER else AiRole.ASSISTANT
            val last = result.lastOrNull()
            if (last != null && last.role == role) {
                result[result.lastIndex] = last.copy(content = last.content + "\n\n" + view.content)
            } else {
                result += ChatMessage(role, view.content)
            }
        }

        val instruction = turnInstruction?.trim()?.takeIf { it.isNotEmpty() } ?: return result
        val block = "[지시]\n$instruction"
        val last = result.lastOrNull()
        if (last != null && last.role == AiRole.USER) {
            result[result.lastIndex] = last.copy(content = block + "\n\n" + last.content)
        } else {
            result += ChatMessage(AiRole.USER, block)
        }
        return result
    }

    companion object {
        /** 이어쓰기 가상 지시. 저장하지 않는다. */
        const val CONTINUE_INSTRUCTION =
            "직전 응답에서 끊긴 장면을 자연스럽게 이어서 계속 써 주세요. 주인공(사용자)의 행동이나 대사는 만들지 마세요."

        /** 여러 지시를 한 블록으로 합친다. 빈 값은 뺀다. 모두 비면 null. */
        fun combine(vararg instructions: String?): String? =
            instructions.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString("\n")
                .ifEmpty { null }
    }
}
