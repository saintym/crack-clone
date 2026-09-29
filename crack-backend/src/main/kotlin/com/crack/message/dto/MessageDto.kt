package com.crack.message.dto

import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.entity.StoryMessage
import java.time.LocalDateTime

/**
 * 응답 첫 줄 태그 줄에서 뽑은 값 (DESIGN.md §5.3, §5.4, D19·D31·D39).
 *
 * `[감정: 경계심] [인물: 설월/당황]` → `ResponseTags("경계심", "설월", "당황")`.
 * `[시간: 2026-09-28 23:40] [장소: 에미야 저택]`이 있으면 [storyTime]·[place]에 담긴다(T38).
 * 메시지와 후보에 함께 저장한다. 사용자에게 글로 보이지 않는 내부 신호다.
 */
data class ResponseTags(
    val emotion: String? = null,
    /** 이 응답의 중심 인물 이름. 인물 이미지 태그 `{speaker}_{speakerVariant}`의 앞부분이다 */
    val speaker: String? = null,
    /** 인물 이미지 변형 이름. 없으면 프론트가 `기본`으로 폴백한다 */
    val speakerVariant: String? = null,
    /**
     * 이 장면이 **도달한** 이야기 속 시각 (T38, D39). 첫 줄 `[시간: 2026-09-28 23:40]`.
     *
     * 태그에서 갓 뽑은 값은 AI가 적은 그대로다. 단조 비감소를 강제하고 값이 없는 턴에 직전 값을 잇는 것은
     * 저장 직전에 `StoryClockService.stamp`가 한다.
     */
    val storyTime: LocalDateTime? = null,
    /** 이 장면의 이야기 속 장소 (T38). 첫 줄 `[장소: 에미야 저택]`. 자유 문자열이다 */
    val place: String? = null,
) {
    companion object {
        val NONE = ResponseTags()

        /** `emotion` 칼럼 크기 */
        const val MAX_EMOTION = 100

        /** `speaker` 칼럼 크기 */
        const val MAX_SPEAKER = 100

        /** `speaker_variant` 칼럼 크기 */
        const val MAX_VARIANT = 50

        /** `place` 칼럼 크기 */
        const val MAX_PLACE = 200

        /** 공백을 떼고 칼럼 크기에 맞게 자른다. 빈 값은 null이고, 인물이 없으면 변형도 버린다. */
        fun of(
            emotion: String?,
            speaker: String? = null,
            speakerVariant: String? = null,
            storyTime: LocalDateTime? = null,
            place: String? = null,
        ): ResponseTags {
            val name = speaker.normalize(MAX_SPEAKER)
            return ResponseTags(
                emotion = emotion.normalize(MAX_EMOTION),
                speaker = name,
                speakerVariant = if (name == null) null else speakerVariant.normalize(MAX_VARIANT),
                storyTime = storyTime,
                place = place.normalize(MAX_PLACE),
            )
        }

        private fun String?.normalize(max: Int): String? = this?.trim()?.take(max)?.ifEmpty { null }
    }
}

/**
 * 화면용 메시지 (DESIGN.md §5.2).
 * `emotion`·`speaker`·`speakerVariant`는 화면에 글로 출력하지 않는 내부 신호다(§5.3).
 * 프론트는 `speaker`로 인물 이미지를 고를 때만 쓴다(§8.5).
 * `storyTime`·`place`는 이야기 속 시각·장소다(§5.4, T38). 머리글에 띄우는 값이다(T51).
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
    /**
     * 이야기 속 시각 (§5.4, T38). ASSISTANT만 값이 있다. 시계를 쓰지 않는 스토리는 null이다.
     * ISO-8601 지역 시각(`2026-09-28T23:40:00`)으로 직렬화된다
     */
    val storyTime: LocalDateTime? = null,
    /** 이야기 속 장소 (§5.4, T38). 값이 없으면 null이다 */
    val place: String? = null,
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
            storyTime = message.storyTime,
            place = message.place,
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
