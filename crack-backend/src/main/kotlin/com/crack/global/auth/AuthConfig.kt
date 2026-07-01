package com.crack.global.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "crack.auth")
data class AuthConfig(
    val password: String = "",
    val tokenSecret: String = "crack-default-secret-change-me"
)
