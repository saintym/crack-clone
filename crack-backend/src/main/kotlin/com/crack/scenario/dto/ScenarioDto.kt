package com.crack.scenario.dto

import com.crack.scenario.entity.Scenario
import com.crack.scenario.entity.ScenarioStatus
import java.time.LocalDateTime

data class ScenarioCreateRequest(
    val name: String,
    val title: String
)

data class ScenarioResponse(
    val id: Long,
    val name: String,
    val title: String,
    val dataPath: String,
    val status: ScenarioStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(scenario: Scenario) = ScenarioResponse(
            id = scenario.id,
            name = scenario.name,
            title = scenario.title,
            dataPath = scenario.dataPath,
            status = scenario.status,
            createdAt = scenario.createdAt,
            updatedAt = scenario.updatedAt
        )
    }
}
