package com.crack.story.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "stories")
class Story(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "scenario_id", nullable = false)
    val scenarioId: Long,

    @Column(nullable = false)
    var title: String,

    @Column(name = "data_path", nullable = false)
    val dataPath: String,

    @Column(name = "turn_count")
    var turnCount: Int = 0,

    @Column
    @Enumerated(EnumType.STRING)
    var status: StoryStatus = StoryStatus.ACTIVE,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class StoryStatus {
    ACTIVE, ARCHIVED
}
