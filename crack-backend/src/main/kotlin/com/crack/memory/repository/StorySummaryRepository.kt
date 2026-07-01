package com.crack.memory.repository

import com.crack.memory.entity.StorySummary
import com.crack.memory.entity.SummaryLevel
import org.springframework.data.jpa.repository.JpaRepository

interface StorySummaryRepository : JpaRepository<StorySummary, Long> {
    fun findByScenarioIdAndLevelOrderByFromTurnAsc(scenarioId: Long, level: SummaryLevel): List<StorySummary>
    fun countByScenarioIdAndLevel(scenarioId: Long, level: SummaryLevel): Long

    fun findByStoryIdAndLevelOrderByFromTurnAsc(storyId: Long, level: SummaryLevel): List<StorySummary>
    fun countByStoryIdAndLevel(storyId: Long, level: SummaryLevel): Long
}
