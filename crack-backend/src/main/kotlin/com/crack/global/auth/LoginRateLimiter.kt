package com.crack.global.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 로그인 대입 제한 (T34).
 *
 * 인터넷에 열어 두면 `/api/auth/login`이 무제한 대입 대상이 된다. 개인용 1인 앱이므로
 * **IP별이 아니라 전역**으로 센다. 터널 뒤에서는 모든 요청의 원격 주소가 루프백이라
 * IP별 계산이 의미가 없고, 전달 헤더는 위조할 수 있기 때문이다.
 *
 * - [FREE_ATTEMPTS]회까지는 그냥 틀려도 된다(오타 여유).
 * - 그 뒤로는 실패할 때마다 잠금 시간이 2배로 늘어난다. 상한은 [MAX_LOCK].
 * - 성공하면 초기화된다.
 *
 * 전역이라 공격자가 주인을 잠글 수 있다(가벼운 DoS). 비밀번호가 충분히 길면 대입은
 * 애초에 무의미하므로, **뚫리는 것보다 잠깐 기다리는 쪽**을 택했다.
 *
 * 메모리에만 둔다. 서버를 다시 띄우면 초기화된다.
 */
@Component
class LoginRateLimiter {
    private val log = LoggerFactory.getLogger(javaClass)

    private var failures = 0
    private var lockedUntil: Instant = Instant.EPOCH

    /** 지금 시도할 수 있으면 null, 잠겨 있으면 남은 시간 */
    @Synchronized
    fun retryAfter(now: Instant = Instant.now()): Duration? {
        val left = Duration.between(now, lockedUntil)
        return if (left.isNegative || left.isZero) null else left
    }

    @Synchronized
    fun onFailure(now: Instant = Instant.now()) {
        failures++
        if (failures <= FREE_ATTEMPTS) return
        val steps = (failures - FREE_ATTEMPTS - 1).coerceAtMost(MAX_DOUBLINGS)
        val lock = minOf(BASE_LOCK.multipliedBy(1L shl steps), MAX_LOCK)
        lockedUntil = now.plus(lock)
        log.warn("로그인 실패 {}회. {}초 동안 잠급니다.", failures, lock.seconds)
    }

    @Synchronized
    fun onSuccess() {
        failures = 0
        lockedUntil = Instant.EPOCH
    }

    companion object {
        /** 잠그지 않고 봐주는 실패 횟수 */
        const val FREE_ATTEMPTS = 5

        /** 첫 잠금 시간 */
        val BASE_LOCK: Duration = Duration.ofSeconds(30)

        /** 잠금 시간 상한 */
        val MAX_LOCK: Duration = Duration.ofMinutes(15)

        /** 2배씩 늘리는 횟수 상한 (오버플로 방지) */
        const val MAX_DOUBLINGS = 20
    }
}
