package com.crack.memory.record

import com.crack.prompt.context.RecordedTurnSource
import com.crack.story.entity.Story
import com.crack.story.repository.StoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.Optional

class StoryRecordedTurnSourceTest {

    private val story = Story(scenarioId = 1L, title = "t", dirName = "d", recordedThroughTurn = 20)

    @Test
    fun `스토리의 recorded_through_turn을 돌려준다`() {
        val repository = mock<StoryRepository> { on { findById(7L) } doReturn Optional.of(story) }
        val source: RecordedTurnSource = StoryRecordedTurnSource(repository)

        assertEquals(20, source.recordedThroughTurn(7L))
    }

    @Test
    fun `스토리가 없으면 0을 돌려준다`() {
        val repository = mock<StoryRepository> { on { findById(8L) } doReturn Optional.empty() }

        assertEquals(0, StoryRecordedTurnSource(repository).recordedThroughTurn(8L))
    }
}
