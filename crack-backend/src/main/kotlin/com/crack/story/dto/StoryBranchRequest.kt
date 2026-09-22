package com.crack.story.dto

/**
 * 분기 요청. 기준 메시지는 [messageId]로 지정한다(필수). 비어 있으면 400이다.
 * 과도기 필드 `messageIndex`는 T12에서 지웠다.
 */
data class StoryBranchRequest(
    val title: String = "",
    val messageId: Long? = null,
)
