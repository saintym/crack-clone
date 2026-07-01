package com.crack.setting.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "scenario_settings")
class ScenarioSetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "scenario_id", nullable = false)
    val scenarioId: Long,

    @Column(name = "setting_key", nullable = false)
    @Enumerated(EnumType.STRING)
    val settingKey: SettingKey,

    @Column(name = "setting_value", nullable = false, columnDefinition = "TEXT")
    var settingValue: String,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class SettingKey {
    OUTPUT_TEMPLATE,
    EXAMPLE_SCENE,
    VOLUME_RULE,
    DIALOGUE_FORMAT,
    CUSTOM_RULE
}
