package com.crack.setting.controller

import com.crack.setting.dto.*
import com.crack.setting.entity.SettingKey
import com.crack.setting.service.SettingService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/scenarios/{scenarioName}/settings")
class SettingController(
    private val settingService: SettingService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createOrUpdate(
        @PathVariable scenarioName: String,
        @RequestBody request: SettingCreateRequest
    ): SettingResponse = settingService.createOrUpdate(scenarioName, request)

    @PutMapping("/{settingKey}")
    fun update(
        @PathVariable scenarioName: String,
        @PathVariable settingKey: SettingKey,
        @RequestBody request: SettingUpdateRequest
    ): SettingResponse = settingService.update(scenarioName, settingKey, request)

    @GetMapping
    fun getAll(
        @PathVariable scenarioName: String
    ): List<SettingResponse> = settingService.getAll(scenarioName)

    @GetMapping("/{settingKey}")
    fun getByKey(
        @PathVariable scenarioName: String,
        @PathVariable settingKey: SettingKey
    ): SettingResponse = settingService.getByKey(scenarioName, settingKey)

    @DeleteMapping("/{settingKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable scenarioName: String,
        @PathVariable settingKey: SettingKey
    ) = settingService.delete(scenarioName, settingKey)
}
