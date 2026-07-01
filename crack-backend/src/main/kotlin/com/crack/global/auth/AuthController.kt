package com.crack.global.auth

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authConfig: AuthConfig
) {
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): LoginResponse {
        if (authConfig.password.isBlank()) {
            // No password configured — open access
            return LoginResponse(token = generateToken())
        }

        if (request.password != authConfig.password) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid password")
        }

        return LoginResponse(token = generateToken())
    }

    @GetMapping("/verify")
    fun verify(@RequestHeader("Authorization", required = false) auth: String?) {
        if (authConfig.password.isBlank()) return

        val token = auth?.removePrefix("Bearer ")?.trim()
        if (token == null || !isValidToken(token)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
    }

    private fun generateToken(): String {
        val raw = "${authConfig.tokenSecret}:${System.currentTimeMillis()}"
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    private fun isValidToken(token: String): Boolean {
        // Simple: any non-empty token is valid (stateless, password is the gate)
        return token.isNotBlank() && token.length > 10
    }
}

data class LoginRequest(val password: String)
data class LoginResponse(val token: String)
