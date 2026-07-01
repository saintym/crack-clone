package com.crack.chat.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "chat_messages")
class ChatMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "scenario_id")
    val scenarioId: Long,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val role: ChatRole,

    @Column(name = "character_name")
    val characterName: String? = null,

    @Column(nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column
    val emotion: String? = null,

    @Column(name = "turn_number")
    val turnNumber: Int? = null,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class ChatRole {
    USER, ASSISTANT
}
