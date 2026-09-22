package com.crack.message

import com.crack.message.service.TruncateHook
import com.crack.story.entity.Story
import com.crack.story.repository.StoryRepository
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * 테스트용 스토리 생성. Story 생성자가 바뀌면(T02: data_path → dir_name) 여기만 고치면 된다.
 */
fun StoryRepository.createTestStory(title: String = "테스트 스토리"): Story =
    save(Story(scenarioId = 1L, title = title, dataPath = "test-story"))

/** truncateFrom이 알린 (storyId, minTruncatedTurn)을 모은다. */
class RecordingTruncateHook : TruncateHook {
    val calls = mutableListOf<Pair<Long, Int>>()
    override fun onTruncate(storyId: Long, minTruncatedTurn: Int) {
        calls += storyId to minTruncatedTurn
    }
}

@TestConfiguration
class MessageTestConfig {
    @Bean
    fun recordingTruncateHook() = RecordingTruncateHook()

    @Bean
    fun secondTruncateHook() = RecordingTruncateHook()
}
