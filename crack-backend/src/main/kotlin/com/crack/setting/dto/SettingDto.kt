package com.crack.setting.dto

import com.crack.setting.entity.ScenarioSetting
import com.crack.setting.entity.SettingKey
import java.time.LocalDateTime

data class SettingCreateRequest(
    val settingKey: SettingKey,
    val settingValue: String
)

data class SettingUpdateRequest(
    val settingValue: String
)

data class SettingResponse(
    val id: Long,
    val scenarioId: Long,
    val settingKey: SettingKey,
    val settingValue: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(setting: ScenarioSetting) = SettingResponse(
            id = setting.id,
            scenarioId = setting.scenarioId,
            settingKey = setting.settingKey,
            settingValue = setting.settingValue,
            createdAt = setting.createdAt,
            updatedAt = setting.updatedAt
        )
    }
}
