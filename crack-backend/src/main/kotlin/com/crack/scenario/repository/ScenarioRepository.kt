package com.crack.scenario.repository

import com.crack.scenario.entity.Scenario
import com.crack.scenario.entity.ScenarioStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ScenarioRepository : JpaRepository<Scenario, Long> {
    fun findByName(name: String): Scenario?
    fun findByStatus(status: ScenarioStatus): List<Scenario>
    fun existsByName(name: String): Boolean
}
