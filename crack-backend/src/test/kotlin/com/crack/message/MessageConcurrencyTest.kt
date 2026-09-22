package com.crack.message

import com.crack.message.repository.MessageVariantRepository
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.story.repository.StoryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 스토리 행 비관적 락으로 같은 스토리의 동시 쓰기가 직렬화되는지 확인한다. 트랜잭션 없이 실제 커밋한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(MessageTestConfig::class) // MessageServiceTest와 같은 컨텍스트를 재사용한다
class MessageConcurrencyTest {

    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var messageRepository: StoryMessageRepository
    @Autowired lateinit var variantRepository: MessageVariantRepository
    @Autowired lateinit var storyRepository: StoryRepository

    private var storyId: Long = 0

    @AfterEach
    fun cleanUp() {
        val messages = messageRepository.findByStoryIdOrderBySeqAsc(storyId)
        variantRepository.deleteAll(messages.flatMap { variantRepository.findByMessageIdOrderByVariantIndexAsc(it.id) })
        messageRepository.deleteAll(messages)
        storyRepository.deleteById(storyId)
    }

    @Test
    fun `동시에 유저 메시지를 추가해도 seq와 턴이 겹치지 않고 연속된다`() {
        storyId = storyRepository.createTestStory().id
        val threads = 6
        val perThread = 5
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(perThread) { i ->
                    runCatching { messageService.appendUser(storyId, "t$t-$i") }.onFailure { errors += it }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)

        assertEquals(emptyList<Throwable>(), errors)
        val total = threads * perThread
        val messages = messageRepository.findByStoryIdOrderBySeqAsc(storyId)
        assertEquals((0 until total).toList(), messages.map { it.seq })
        assertEquals((1..total).toList(), messages.map { it.turnNo })
        assertEquals(total, storyRepository.findById(storyId).get().turnCount)
    }
}
