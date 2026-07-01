package com.crack.ai.dto

data class ChatMessage(
    val role: MessageRole,
    val content: String
)

enum class MessageRole {
    USER, ASSISTANT
}

enum class ModelTier {
    OPUS,    // 주요 출력용 (고품질 소설 생성)
    SONNET,  // 유틸리티용 (요약, 캐릭터 업데이트)
    HAIKU    // 경량용 (컨텍스트 판별, 분류)
}

data class AiRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int? = null,
    val modelTier: ModelTier = ModelTier.SONNET
)
