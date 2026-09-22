package com.crack.memory.record

import com.crack.prompt.context.RecordedTurnSource
import com.crack.story.repository.StoryRepository
import org.springframework.stereotype.Component

/**
 * [RecordedTurnSource]의 실제 구현. 기록 파이프라인이 갱신하는 `stories.recorded_through_turn`을 읽는다.
 * 이 빈이 등록되면 대화 원문 범위(DESIGN.md §6.1)가 마지막 기록 턴을 기준으로 잘린다.
 */
@Component
class StoryRecordedTurnSource(
    private val storyRepository: StoryRepository,
) : RecordedTurnSource {

    override fun recordedThroughTurn(storyId: Long): Int =
        storyRepository.findById(storyId).map { it.recordedThroughTurn }.orElse(0)
}
