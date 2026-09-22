package com.crack.message.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "story_messages",
    uniqueConstraints = [UniqueConstraint(columnNames = ["story_id", "seq"])],
    indexes = [Index(name = "idx_story_messages_turn", columnList = "story_id, turn_no")],
)
class StoryMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(nullable = false)
    val seq: Int,

    @Column(name = "turn_no", nullable = false)
    val turnNo: Int,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val role: MessageRole,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val kind: MessageKind = MessageKind.NORMAL,

    /** 현재 보이는 내용. ASSISTANT는 선택된 후보의 사본이다. */
    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,

    /** ASSISTANT만. 선택된 후보의 감정 값 사본. 화면에 노출하지 않는다(DESIGN.md §5.3). */
    @Column(length = 100)
    var emotion: String? = null,

    /** ASSISTANT만. 0부터. */
    @Column(name = "selected_variant")
    var selectedVariant: Int? = null,

    @Column(name = "edited_at")
    var editedAt: LocalDateTime? = null,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
