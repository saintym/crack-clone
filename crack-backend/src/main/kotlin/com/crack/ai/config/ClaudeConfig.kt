package com.crack.ai.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Claude API 프로바이더 설정 (`claude.*`). 키가 비어 있으면 API 프로바이더는 사용 불가로 표시된다. */
@ConfigurationProperties(prefix = "claude")
data class ClaudeConfig(
    val apiKey: String = "",
    val model: String = "claude-sonnet-5",
    val maxTokens: Int = 2048,
    val opusModel: String = "claude-opus-5",
    val haikuModel: String = "claude-haiku-4-5-20251001"
)
