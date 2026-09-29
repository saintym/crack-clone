package com.crack.message.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "message_variants",
    uniqueConstraints = [UniqueConstraint(columnNames = ["message_id", "variant_index"])],
)
class MessageVariant(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "message_id", nullable = false)
    val messageId: Long,

    @Column(name = "variant_index", nullable = false)
    val variantIndex: Int,

    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(length = 100)
    var emotion: String? = null,

    /** 이 후보의 중심 인물 이름 (DESIGN.md §5.3) */
    @Column(length = 100)
    var speaker: String? = null,

    /** 이 후보의 인물 이미지 변형 이름 */
    @Column(name = "speaker_variant", length = 50)
    var speakerVariant: String? = null,

    /** 이 후보가 도달한 이야기 속 시각 (T38, DESIGN.md §5.4). 시계를 쓰지 않으면 null */
    @Column(name = "story_time")
    var storyTime: LocalDateTime? = null,

    /** 이 후보의 이야기 속 장소 (T38) */
    @Column(length = 200)
    var place: String? = null,

    /** 재생성 지시 (선택) */
    @Column(columnDefinition = "TEXT")
    val instruction: String? = null,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
