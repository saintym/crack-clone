package com.crack.chat.dto

data class ChatRequest(
    val message: String,
    val activeCharacters: List<String>? = null,
    val provider: String? = null
)

data class ParsedResponse(
    val raw: String,
    val emotions: List<String>,
    val segments: List<ResponseSegment>
)

data class ResponseSegment(
    val type: SegmentType,
    val content: String
)

enum class SegmentType {
    EMOTION,    // [감정: ...]
    ACTION,     // *...*
    DIALOGUE,   // "..."
    NARRATION   // 그 외 텍스트
}
