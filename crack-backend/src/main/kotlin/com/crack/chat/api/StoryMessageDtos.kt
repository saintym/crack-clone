package com.crack.chat.api

/** `POST /messages` body. [command]는 T16(`/` 명령)이 쓴다. 지금은 받기만 한다. */
data class SendMessageRequest(
    val content: String? = null,
    val provider: String? = null,
    val command: String? = null,
)

/** `POST /messages/continue` body (생략 가능) */
data class ContinueRequest(
    val provider: String? = null,
)

/**
 * `POST /messages/regenerate` body (생략 가능).
 * [messageId]는 DESIGN.md에 없던 선택 필드다. 주면 대화의 마지막 메시지인지 검사하고, 아니면 400(D17).
 */
data class RegenerateRequest(
    val provider: String? = null,
    val instruction: String? = null,
    val messageId: Long? = null,
)

/** `PUT /messages/{id}/variant` body */
data class SelectVariantRequest(
    val index: Int? = null,
)

/** `PATCH /messages/{id}` body */
data class EditMessageRequest(
    val content: String? = null,
)
