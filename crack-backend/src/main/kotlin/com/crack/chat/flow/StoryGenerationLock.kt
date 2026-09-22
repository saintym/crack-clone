package com.crack.chat.flow

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 스토리에서 이미 응답을 생성 중이거나 메시지를 고치는 중일 때. API에서 409로 바뀐다. */
class GenerationInProgressException(storyId: Long) :
    RuntimeException("이 스토리에서 이미 응답을 생성하고 있습니다: $storyId")

/**
 * 스토리당 생성 1개 (DESIGN.md §5.2). 단일 서버 프로세스 메모리 락이다.
 *
 * [acquire]가 돌려준 [Ticket]으로만 풀 수 있다. 같은 티켓을 여러 번 풀어도 안전하고,
 * 이미 풀린 뒤 다른 요청이 잡은 락을 실수로 풀지 않는다.
 */
@Component
class StoryGenerationLock {

    private val holders = ConcurrentHashMap<Long, Ticket>()

    /** 락을 잡는다. 이미 잡혀 있으면 [GenerationInProgressException]. */
    fun acquire(storyId: Long): Ticket {
        val ticket = Ticket(storyId)
        if (holders.putIfAbsent(storyId, ticket) != null) throw GenerationInProgressException(storyId)
        return ticket
    }

    fun isLocked(storyId: Long): Boolean = holders.containsKey(storyId)

    inner class Ticket internal constructor(val storyId: Long) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) holders.remove(storyId, this)
        }
    }
}
