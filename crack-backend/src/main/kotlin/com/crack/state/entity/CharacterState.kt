package com.crack.state.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "character_states")
class CharacterState(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "scenario_id", nullable = false)
    val scenarioId: Long,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(name = "character_name", nullable = false)
    val characterName: String,

    @Column(name = "state_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val stateType: StateType,

    @Column(name = "state_key", nullable = false)
    var stateKey: String,

    @Column(name = "state_value", nullable = false, columnDefinition = "TEXT")
    var stateValue: String,

    @Column(columnDefinition = "TEXT")
    var context: String? = null,

    @Column(name = "acquired_turn")
    val acquiredTurn: Int? = null,

    @Column(name = "is_active")
    var isActive: Boolean = true,

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class StateType {
    INVENTORY,
    LOCATION,
    SCHEDULE,
    RELATIONSHIP,
    SKILL,
    STATUS
}
