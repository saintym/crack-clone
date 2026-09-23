package com.crack.global.auth

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 토큰 발급과 검증 (BUG-021).
 *
 * 형식은 `payload.signature`다.
 * - `payload` = base64url(`v1:<만료 epoch 초>`)
 * - `signature` = base64url(HMAC-SHA256(payload, `crack.auth.token-secret`))
 *
 * 서버가 상태를 갖지 않으므로 로그아웃(무효화)은 시크릿을 바꾸는 것으로 한다.
 * 이전 구현은 길이만 검사해서 아무 문자열이나 통과했다.
 */
@Component
class AuthTokens(private val authConfig: AuthConfig) {

    fun issue(now: Instant = Instant.now()): String {
        val expiresAt = now.plusSeconds(authConfig.tokenTtlHours * 3600)
        val payload = encode("v1:${expiresAt.epochSecond}".toByteArray(StandardCharsets.UTF_8))
        return "$payload.${sign(payload)}"
    }

    /** 서명과 만료를 모두 검증한다. 형식이 다르거나 위조·만료된 토큰은 false. */
    fun isValid(token: String?, now: Instant = Instant.now()): Boolean {
        val parts = token?.trim()?.split(".") ?: return false
        if (parts.size != 2 || parts.any { it.isEmpty() }) return false
        val (payload, signature) = parts

        if (!constantTimeEquals(signature, sign(payload))) return false

        val decoded = runCatching { String(decode(payload), StandardCharsets.UTF_8) }.getOrNull() ?: return false
        val expiresAt = decoded.removePrefix("v1:").toLongOrNull() ?: return false
        if (!decoded.startsWith("v1:")) return false

        return now.epochSecond < expiresAt
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(authConfig.tokenSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return encode(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    /** 서명 비교는 길이 정보만 흘리도록 상수 시간 비교를 쓴다. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
}
