package com.crack.ai.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "claude")
data class ClaudeConfig(
    val apiKey: String = "",
    val model: String = "claude-sonnet-4-20250514",
    val maxTokens: Int = 2048,
    val opusModel: String = "claude-opus-4-20250514",
    val haikuModel: String = "claude-haiku-4-5-20251001"
)
