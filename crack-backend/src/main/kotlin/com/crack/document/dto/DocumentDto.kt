package com.crack.document.dto

data class DocumentResponse(
    val type: String,
    val name: String,
    val content: String
)

data class DocumentUpdateRequest(
    val content: String
)

data class CharacterCreateRequest(
    val name: String,
    val content: String? = null
)
