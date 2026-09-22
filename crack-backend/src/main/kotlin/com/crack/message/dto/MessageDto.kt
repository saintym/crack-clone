package com.crack.message.dto

import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.entity.StoryMessage
import java.time.LocalDateTime

/**
 * 화면용 메시지 (DESIGN.md §5.2).
 * `emotion`은 사용자에게 보이지 않는 내부 신호이므로 **넣지 않는다**(§5.3).
 */
data class MessageView(
    val id: Long,
    val seq: Int,
    val turn: Int,
    val role: MessageRole,
    val kind: MessageKind,
    val content: String,
    /** ASSISTANT만. 선택된 후보 번호(0부터). USER는 null */
    val variantIndex: Int?,
    /** ASSISTANT만 의미가 있다. USER는 0 */
    val variantCount: Int,
    val edited: Boolean,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun of(message: StoryMessage, variantCount: Int): MessageView = MessageView(
            id = message.id,
            seq = message.seq,
            turn = message.turnNo,
            role = message.role,
            kind = message.kind,
            content = message.content,
            variantIndex = message.selectedVariant,
            variantCount = variantCount,
            edited = message.editedAt != null,
            createdAt = message.createdAt,
        )
    }
}

/**
 * [com.crack.message.service.MessageService.truncateFrom] 결과.
 *
 * @property minTruncatedTurn 잘린 메시지 중 최소 turn_no
 * @property deletedCount 삭제된 메시지 수
 * @property turnCount 삭제 후 다시 계산한 `stories.turn_count`
 */
data class TruncateResult(
    val storyId: Long,
    val minTruncatedTurn: Int,
    val deletedCount: Int,
    val turnCount: Int,
)
