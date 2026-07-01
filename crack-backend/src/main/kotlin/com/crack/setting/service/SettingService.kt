package com.crack.setting.service

import com.crack.global.exception.NotFoundException
import com.crack.scenario.repository.ScenarioRepository
import com.crack.setting.dto.*
import com.crack.setting.entity.ScenarioSetting
import com.crack.setting.entity.SettingKey
import com.crack.setting.repository.ScenarioSettingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class SettingService(
    private val scenarioRepository: ScenarioRepository,
    private val scenarioSettingRepository: ScenarioSettingRepository
) {
    @Transactional
    fun createOrUpdate(scenarioName: String, request: SettingCreateRequest): SettingResponse {
        val scenario = findScenario(scenarioName)

        val existing = scenarioSettingRepository.findByScenarioIdAndSettingKey(scenario.id, request.settingKey)
        if (existing != null) {
            existing.settingValue = request.settingValue
            existing.updatedAt = LocalDateTime.now()
            return SettingResponse.from(existing)
        }

        val setting = ScenarioSetting(
            scenarioId = scenario.id,
            settingKey = request.settingKey,
            settingValue = request.settingValue
        )
        return SettingResponse.from(scenarioSettingRepository.save(setting))
    }

    @Transactional
    fun update(scenarioName: String, settingKey: SettingKey, request: SettingUpdateRequest): SettingResponse {
        val scenario = findScenario(scenarioName)
        val setting = scenarioSettingRepository.findByScenarioIdAndSettingKey(scenario.id, settingKey)
            ?: throw NotFoundException("Setting not found: $settingKey")

        setting.settingValue = request.settingValue
        setting.updatedAt = LocalDateTime.now()
        return SettingResponse.from(setting)
    }

    fun getAll(scenarioName: String): List<SettingResponse> {
        val scenario = findScenario(scenarioName)
        return scenarioSettingRepository.findByScenarioId(scenario.id)
            .map { SettingResponse.from(it) }
    }

    fun getByKey(scenarioName: String, settingKey: SettingKey): SettingResponse {
        val scenario = findScenario(scenarioName)
        val setting = scenarioSettingRepository.findByScenarioIdAndSettingKey(scenario.id, settingKey)
            ?: throw NotFoundException("Setting not found: $settingKey")
        return SettingResponse.from(setting)
    }

    @Transactional
    fun delete(scenarioName: String, settingKey: SettingKey) {
        val scenario = findScenario(scenarioName)
        val setting = scenarioSettingRepository.findByScenarioIdAndSettingKey(scenario.id, settingKey)
            ?: throw NotFoundException("Setting not found: $settingKey")
        scenarioSettingRepository.delete(setting)
    }

    fun getSettingValueOrDefault(scenarioId: Long, settingKey: SettingKey, default: String): String {
        return scenarioSettingRepository.findByScenarioIdAndSettingKey(scenarioId, settingKey)
            ?.settingValue ?: default
    }

    private fun findScenario(scenarioName: String) =
        scenarioRepository.findByName(scenarioName)
            ?: throw NotFoundException("Scenario not found: $scenarioName")
}
