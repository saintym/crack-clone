package com.crack.story.dto

import com.crack.story.entity.Story
import com.crack.story.entity.StoryStatus
import java.time.LocalDateTime

data class StoryCreateRequest(
    val title: String
)

data class StoryResponse(
    val id: Long,
    val scenarioId: Long,
    val title: String,
    val turnCount: Int,
    val status: StoryStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(story: Story) = StoryResponse(
            id = story.id,
            scenarioId = story.scenarioId,
            title = story.title,
            turnCount = story.turnCount,
            status = story.status,
            createdAt = story.createdAt,
            updatedAt = story.updatedAt
        )
    }
}
