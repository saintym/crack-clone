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

/**
 * AI 호출 목적. 모델 등급은 목적에서 정해진다.
 * 기본 매핑: CHAT→OPUS, RECORD→SONNET, UTILITY→HAIKU (`crack.ai.purpose-tiers`로 변경 가능)
 */
enum class AiPurpose {
    CHAT,     // 매 턴 롤플레이 응답
    RECORD,   // 기억 기록, 요약, 캐릭터 문서 갱신
    UTILITY   // 가벼운 판별, 분류
}

data class AiRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val purpose: AiPurpose = AiPurpose.UTILITY,
    val maxTokens: Int? = null,
)
