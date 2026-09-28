package com.crack.global.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** 로그인 대입 제한 (T34) */
class LoginRateLimiterTest {

    private val t0: Instant = Instant.parse("2026-09-28T00:00:00Z")

    @Test
    fun `봐주는 횟수까지는 잠기지 않는다`() {
        val limiter = LoginRateLimiter()
        repeat(LoginRateLimiter.FREE_ATTEMPTS) { limiter.onFailure(t0) }

        assertThat(limiter.retryAfter(t0)).isNull()
    }

    @Test
    fun `봐주는 횟수를 넘기면 잠긴다`() {
        val limiter = LoginRateLimiter()
        repeat(LoginRateLimiter.FREE_ATTEMPTS + 1) { limiter.onFailure(t0) }

        assertThat(limiter.retryAfter(t0)).isEqualTo(LoginRateLimiter.BASE_LOCK)
    }

    @Test
    fun `실패가 이어지면 잠금 시간이 두 배씩 늘어난다`() {
        val limiter = LoginRateLimiter()
        repeat(LoginRateLimiter.FREE_ATTEMPTS + 2) { limiter.onFailure(t0) }

        assertThat(limiter.retryAfter(t0)).isEqualTo(LoginRateLimiter.BASE_LOCK.multipliedBy(2))
    }

    @Test
    fun `잠금 시간에는 상한이 있다`() {
        val limiter = LoginRateLimiter()
        repeat(100) { limiter.onFailure(t0) }

        assertThat(limiter.retryAfter(t0)).isEqualTo(LoginRateLimiter.MAX_LOCK)
    }

    @Test
    fun `시간이 지나면 풀린다`() {
        val limiter = LoginRateLimiter()
        repeat(LoginRateLimiter.FREE_ATTEMPTS + 1) { limiter.onFailure(t0) }

        assertThat(limiter.retryAfter(t0.plus(LoginRateLimiter.BASE_LOCK))).isNull()
    }

    @Test
    fun `성공하면 초기화된다`() {
        val limiter = LoginRateLimiter()
        repeat(LoginRateLimiter.FREE_ATTEMPTS + 3) { limiter.onFailure(t0) }
        limiter.onSuccess()

        assertThat(limiter.retryAfter(t0)).isNull()
        repeat(LoginRateLimiter.FREE_ATTEMPTS) { limiter.onFailure(t0) }
        assertThat(limiter.retryAfter(t0)).isNull()
    }
}
