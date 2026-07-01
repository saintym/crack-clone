package com.crack.chat.repository

import com.crack.chat.entity.ChatMessage
import org.springframework.data.jpa.repository.JpaRepository

interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findByScenarioIdOrderByTurnNumberAsc(scenarioId: Long): List<ChatMessage>
    fun countByScenarioId(scenarioId: Long): Long

    fun findByStoryIdOrderByTurnNumberAsc(storyId: Long): List<ChatMessage>
    fun countByStoryId(storyId: Long): Long
}
