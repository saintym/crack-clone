package com.crack.story.repository

import com.crack.story.entity.Story
import com.crack.story.entity.StoryStatus
import org.springframework.data.jpa.repository.JpaRepository

interface StoryRepository : JpaRepository<Story, Long> {
    fun findByScenarioIdAndStatusOrderByUpdatedAtDesc(scenarioId: Long, status: StoryStatus = StoryStatus.ACTIVE): List<Story>
    fun findByScenarioIdOrderByUpdatedAtDesc(scenarioId: Long): List<Story>
}
