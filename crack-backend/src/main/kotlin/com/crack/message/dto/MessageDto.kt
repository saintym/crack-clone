package com.crack.message.dto

import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.entity.StoryMessage
import java.time.LocalDateTime

/**
 * 응답 첫 줄 태그 줄에서 뽑은 값 (DESIGN.md §5.3, D19·D31).
 *
 * `[감정: 경계심] [인물: 설월/당황]` → `ResponseTags("경계심", "설월", "당황")`.
 * 메시지와 후보에 함께 저장한다. 사용자에게 글로 보이지 않는 내부 신호다.
 */
data class ResponseTags(
    val emotion: String? = null,
    /** 이 응답의 중심 인물 이름. 인물 이미지 태그 `{speaker}_{speakerVariant}`의 앞부분이다 */
    val speaker: String? = null,
    /** 인물 이미지 변형 이름. 없으면 프론트가 `기본`으로 폴백한다 */
    val speakerVariant: String? = null,
) {
    companion object {
        val NONE = ResponseTags()

        /** `emotion` 칼럼 크기 */
        const val MAX_EMOTION = 100

        /** `speaker` 칼럼 크기 */
        const val MAX_SPEAKER = 100

        /** `speaker_variant` 칼럼 크기 */
        const val MAX_VARIANT = 50

        /** 공백을 떼고 칼럼 크기에 맞게 자른다. 빈 값은 null이고, 인물이 없으면 변형도 버린다. */
        fun of(emotion: String?, speaker: String? = null, speakerVariant: String? = null): ResponseTags {
            val name = speaker.normalize(MAX_SPEAKER)
            return ResponseTags(
                emotion = emotion.normalize(MAX_EMOTION),
                speaker = name,
                speakerVariant = if (name == null) null else speakerVariant.normalize(MAX_VARIANT),
            )
        }

        private fun String?.normalize(max: Int): String? = this?.trim()?.take(max)?.ifEmpty { null }
    }
}

/**
 * 화면용 메시지 (DESIGN.md §5.2).
 * `emotion`·`speaker`·`speakerVariant`는 화면에 글로 출력하지 않는 내부 신호다(§5.3).
 * 프론트는 `speaker`로 인물 이미지를 고를 때만 쓴다(§8.5).
 */
data class MessageView(
    val id: Long,
    val seq: Int,
    val turn: Int,
    val role: MessageRole,
    val kind: MessageKind,
    val content: String,
    /** ASSISTANT만. 첫 줄 `[감정: …]` 값 (§5.3). 화면에 글로 출력하지 않는다 */
    val emotion: String? = null,
    /** ASSISTANT만. 첫 줄 `[인물: …]`의 인물 이름 (§5.3). 인물 이미지 선택에 쓴다 */
    val speaker: String? = null,
    /** ASSISTANT만. 첫 줄 `[인물: 이름/변형]`의 변형 이름 */
    val speakerVariant: String? = null,
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
            emotion = message.emotion,
            speaker = message.speaker,
            speakerVariant = message.speakerVariant,
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
