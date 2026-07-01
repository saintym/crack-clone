package com.crack.state.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "character_events")
class CharacterEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "scenario_id", nullable = false)
    val scenarioId: Long,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(name = "character_name", nullable = false)
    val characterName: String,

    @Column(name = "turn_number", nullable = false)
    val turnNumber: Int,

    @Column(name = "event_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val eventType: EventType,

    @Column(nullable = false, length = 500)
    val summary: String,

    @Column(columnDefinition = "TEXT")
    val detail: String? = null,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class EventType {
    RELATIONSHIP_CHANGE,
    KNOWLEDGE_GAIN,
    PERSONALITY_SHIFT,
    MAJOR_EVENT
}
