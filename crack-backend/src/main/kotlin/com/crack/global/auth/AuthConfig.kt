package com.crack.global.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "crack.auth")
data class AuthConfig(
    val password: String = "",
    val tokenSecret: String = "crack-default-secret-change-me",
    /** 발급 토큰의 유효 기간(시간). 개인용이라 길게 두지만 무한은 아니다 (BUG-021). */
    val tokenTtlHours: Long = 720,
)
