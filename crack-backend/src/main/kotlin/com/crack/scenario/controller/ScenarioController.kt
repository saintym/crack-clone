package com.crack.scenario.controller

import com.crack.scenario.dto.ScenarioCreateRequest
import com.crack.scenario.dto.ScenarioResponse
import com.crack.scenario.service.ScenarioService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/scenarios")
class ScenarioController(
    private val scenarioService: ScenarioService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: ScenarioCreateRequest): ScenarioResponse {
        return scenarioService.create(request)
    }

    @GetMapping
    fun findAll(): List<ScenarioResponse> {
        return scenarioService.findAll()
    }

    @GetMapping("/{name}")
    fun findByName(@PathVariable name: String): ScenarioResponse {
        return scenarioService.findByName(name)
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable name: String) {
        scenarioService.delete(name)
    }
}
