package com.crack.global.auth

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authConfig: AuthConfig,
    private val authTokens: AuthTokens,
    private val rateLimiter: LoginRateLimiter,
) {
    /**
     * 비밀번호로 토큰을 발급한다. 대입을 막기 위해 [LoginRateLimiter]가 실패 횟수를 센다(T34).
     * 잠겨 있으면 비밀번호가 맞아도 429를 준다 — 맞는지 여부 자체를 알려 주지 않기 위해서다.
     */
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): LoginResponse {
        rateLimiter.retryAfter()?.let { left ->
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "${left.seconds}초 뒤에 다시 시도하세요")
        }

        if (authConfig.password.isNotBlank() && request.password != authConfig.password) {
            rateLimiter.onFailure()
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid password")
        }

        rateLimiter.onSuccess()
        // 비밀번호를 설정하지 않았으면(개방) 토큰은 발급하되 필터도 통과시킨다.
        return LoginResponse(token = authTokens.issue())
    }

    @GetMapping("/verify")
    fun verify(@RequestHeader("Authorization", required = false) auth: String?) {
        if (authConfig.password.isBlank()) return

        val token = auth?.removePrefix("Bearer ")?.trim()
        if (!authTokens.isValid(token)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
    }
}

data class LoginRequest(val password: String)
data class LoginResponse(val token: String)
