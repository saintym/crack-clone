package com.crack.chat.flow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StoryGenerationLockTest {

    private val lock = StoryGenerationLock()

    @Test
    fun `같은 스토리는 하나만 잡을 수 있고 다른 스토리는 독립이다`() {
        val ticket = lock.acquire(1L)
        assertThrows<GenerationInProgressException> { lock.acquire(1L) }
        lock.acquire(2L).release()

        ticket.release()
        assertThat(lock.isLocked(1L)).isFalse()
        lock.acquire(1L).release()
    }

    @Test
    fun `이미 푼 티켓을 다시 풀어도 다른 요청의 락을 풀지 않는다`() {
        val first = lock.acquire(1L)
        first.release()
        val second = lock.acquire(1L)

        first.release()
        assertThat(lock.isLocked(1L)).isTrue()
        second.release()
        assertThat(lock.isLocked(1L)).isFalse()
    }
}
