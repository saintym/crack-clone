package com.crack.memory.record

import com.crack.message.createTestStory
import com.crack.story.repository.StoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/** BUG-009 회귀 테스트: 읽음 처리는 끝난(DONE, FAILED) 기록만 대상으로 한다. */
@SpringBootTest
@ActiveProfiles("test")
class MemoryRecordSeenTest {

    @Autowired lateinit var service: MemoryRecordService
    @Autowired lateinit var recordRepository: MemoryRecordRepository
    @Autowired lateinit var storyRepository: StoryRepository

    private var storyId: Long = 0
    private var otherStoryId: Long = 0

    @BeforeEach
    fun setUp() {
        storyId = storyRepository.createTestStory("읽음 테스트").id
        otherStoryId = storyRepository.createTestStory("다른 스토리").id
    }

    @AfterEach
    fun tearDown() {
        listOf(storyId, otherStoryId).forEach { id ->
            recordRepository.deleteAll(recordRepository.findByStoryIdOrderByIdDesc(id))
            storyRepository.deleteById(id)
        }
    }

    private fun save(story: Long, status: RecordStatus): Long =
        recordRepository.save(
            MemoryRecord(storyId = story, fromTurn = 1, toTurn = 10, reason = RecordReason.AUTO, status = status),
        ).id

    private fun seen(id: Long) = recordRepository.findById(id).get().seen

    @Test
    fun `DONE과 FAILED만 읽음 처리하고 RUNNING은 남긴다`() {
        val done = save(storyId, RecordStatus.DONE)
        val failed = save(storyId, RecordStatus.FAILED)
        val running = save(storyId, RecordStatus.RUNNING)
        val other = save(otherStoryId, RecordStatus.DONE)

        service.markSeen(storyId)

        assertThat(seen(done)).isTrue()
        assertThat(seen(failed)).isTrue()
        assertThat(seen(running)).isFalse()
        assertThat(seen(other)).isFalse()
        assertThat(service.status(storyId).unseen).isFalse()
    }

    @Test
    fun `RUNNING 중 읽음 처리해도 끝난 뒤에는 뱃지가 뜬다`() {
        val running = save(storyId, RecordStatus.RUNNING)
        service.markSeen(storyId)

        val record = recordRepository.findById(running).get()
        record.status = RecordStatus.DONE
        recordRepository.save(record)

        assertThat(service.status(storyId).unseen).isTrue()
    }
}
