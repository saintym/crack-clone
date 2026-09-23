package com.crack.global.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

/** BUG-021 회귀 테스트: 토큰은 서명과 만료를 검증해야 한다. */
class AuthTokensTest {

    private val tokens = AuthTokens(AuthConfig(password = "pw", tokenSecret = "secret-a", tokenTtlHours = 1))

    @Test
    fun `발급한 토큰은 통과한다`() {
        assertTrue(tokens.isValid(tokens.issue()))
    }

    @Test
    fun `임의 문자열은 거부한다`() {
        listOf(null, "", "   ", "aaa.bbb.ccc", "aaa.bbb", "toooooooken", "a".repeat(64), "v1:0.x").forEach {
            assertFalse(tokens.isValid(it), "거부해야 한다: $it")
        }
    }

    @Test
    fun `서명이나 내용을 위조하면 거부한다`() {
        val (payload, signature) = tokens.issue().split(".")
        assertFalse(tokens.isValid("$payload.${flipLast(signature)}"))
        assertFalse(tokens.isValid("${flipLast(payload)}.$signature"))
        // 만료를 늘려 쓴 payload는 서명이 맞지 않는다
        val forged = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("v1:${Instant.now().epochSecond + 999_999}".toByteArray())
        assertFalse(tokens.isValid("$forged.$signature"))
    }

    /** 마지막 글자를 반드시 다른 글자로 바꾼다(원래 값이 'A'여도 위조가 되도록). */
    private fun flipLast(value: String): String =
        value.dropLast(1) + if (value.last() == 'A') 'B' else 'A'

    @Test
    fun `다른 시크릿으로 만든 토큰은 거부한다`() {
        val other = AuthTokens(AuthConfig(password = "pw", tokenSecret = "secret-b"))
        assertFalse(tokens.isValid(other.issue()))
    }

    @Test
    fun `만료된 토큰은 거부한다`() {
        val issued = tokens.issue(Instant.parse("2026-01-01T00:00:00Z"))
        assertTrue(tokens.isValid(issued, Instant.parse("2026-01-01T00:59:00Z")))
        assertFalse(tokens.isValid(issued, Instant.parse("2026-01-01T01:00:01Z")))
    }
}
