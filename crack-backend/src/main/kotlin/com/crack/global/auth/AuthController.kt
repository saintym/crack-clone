package com.crack.global.auth

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authConfig: AuthConfig,
    private val authTokens: AuthTokens,
) {
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): LoginResponse {
        if (authConfig.password.isNotBlank() && request.password != authConfig.password) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid password")
        }

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
