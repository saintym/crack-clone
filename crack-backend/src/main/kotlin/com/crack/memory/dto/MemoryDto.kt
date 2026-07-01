package com.crack.memory.dto

data class MustRememberResponse(
    val content: String
)

data class MustRememberUpdateRequest(
    val content: String
)

data class SummaryResponse(
    val fileName: String,
    val fromTurn: Int,
    val toTurn: Int,
    val content: String
)

data class SummarizeResult(
    val summaryFile: String,
    val turnRange: String,
    val summary: String,
    val charactersUpdated: List<String>
)
