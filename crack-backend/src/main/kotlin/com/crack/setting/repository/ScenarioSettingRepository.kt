package com.crack.setting.repository

import com.crack.setting.entity.ScenarioSetting
import com.crack.setting.entity.SettingKey
import org.springframework.data.jpa.repository.JpaRepository

interface ScenarioSettingRepository : JpaRepository<ScenarioSetting, Long> {
    fun findByScenarioIdAndSettingKey(scenarioId: Long, settingKey: SettingKey): ScenarioSetting?
    fun findByScenarioId(scenarioId: Long): List<ScenarioSetting>
}
