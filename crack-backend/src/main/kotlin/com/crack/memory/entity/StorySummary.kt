package com.crack.memory.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "story_summaries")
class StorySummary(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "scenario_id", nullable = false)
    val scenarioId: Long,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val level: SummaryLevel,

    @Column(name = "from_turn", nullable = false)
    val fromTurn: Int,

    @Column(name = "to_turn", nullable = false)
    val toTurn: Int,

    @Column(nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class SummaryLevel {
    L1, L2, L3
}
