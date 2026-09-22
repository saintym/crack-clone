package com.crack.document.story

/** `GET /api/stories/{id}/documents`의 항목. `size`는 바이트 수. */
data class StoryDocumentSummary(
    val path: String,
    val kind: String,
    val size: Long,
)

data class StoryDocumentContent(
    val path: String,
    val kind: String,
    val content: String,
)

data class StoryDocumentUpdateRequest(
    val content: String,
)
