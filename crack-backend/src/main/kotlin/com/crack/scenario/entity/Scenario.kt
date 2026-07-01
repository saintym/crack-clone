package com.crack.scenario.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "scenarios")
class Scenario(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val name: String,

    @Column(nullable = false)
    var title: String,

    @Column(name = "data_path", nullable = false)
    val dataPath: String,

    @Column
    @Enumerated(EnumType.STRING)
    var status: ScenarioStatus = ScenarioStatus.ACTIVE,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class ScenarioStatus {
    ACTIVE, ARCHIVED
}
