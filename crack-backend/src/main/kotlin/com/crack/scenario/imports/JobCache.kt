package com.crack.scenario.imports

import com.crack.global.exception.NotFoundException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 진행 중인 생성 작업을 **서버 메모리에** 담아 두는 TTL 캐시 (DESIGN.md §11.2).
 *
 * DB 테이블을 만들지 않는다. 확인 화면에서 생성으로 넘어가는 몇 분 동안만 살아 있으면 되고,
 * 서버를 다시 띄우면 처음부터 다시 하는 것이 맞다. 쓸 때마다 만료된 것을 지운다.
 *
 * URL 가져오기([ImportJobStore])와 질문으로 만들기가 같이 쓴다.
 *
 * @param ttlMinutes 설정이 바뀌어도 따라가게 매번 읽는다
 * @param notFound 없거나 만료됐을 때의 안내 문구. 입력원마다 "다시 하라"는 말이 다르다
 */
class JobCache<T : Any>(
    private val ttlMinutes: () -> Long,
    private val notFound: (String) -> String,
) {
    private val entries = ConcurrentHashMap<String, Entry<T>>()

    private data class Entry<T>(val value: T, val createdAt: Instant)

    fun newId(): String = UUID.randomUUID().toString()

    fun put(id: String, value: T): T {
        purgeExpired()
        entries[id] = Entry(value, Instant.now())
        return value
    }

    /** 이미 있는 작업을 덮어쓴다. 만든 시각은 그대로 둬 TTL이 늘어나지 않게 한다. */
    fun replace(id: String, value: T): T {
        val createdAt = entries[id]?.createdAt ?: Instant.now()
        entries[id] = Entry(value, createdAt)
        return value
    }

    fun find(id: String): T? {
        purgeExpired()
        return entries[id]?.value
    }

    /** 없거나 만료됐으면 404. */
    fun require(id: String): T = find(id) ?: throw NotFoundException(notFound(id))

    fun remove(id: String) {
        entries.remove(id)
    }

    fun size(): Int = entries.size

    private fun purgeExpired() {
        val ttl = Duration.ofMinutes(ttlMinutes().coerceAtLeast(1))
        val deadline = Instant.now().minus(ttl)
        entries.entries.removeIf { it.value.createdAt.isBefore(deadline) }
    }
}
