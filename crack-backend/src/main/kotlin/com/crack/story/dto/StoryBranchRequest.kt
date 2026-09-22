package com.crack.story.dto

/**
 * 분기 요청. 기준 메시지는 [messageId]로 지정한다.
 *
 * [messageIndex]는 과도기용이다. 현재 프론트(`src/api/stories.ts`)가 쓰므로 T11이 `messageId`로 옮기기 전까지 받는다.
 * 메시지의 `seq`로 해석한다. 둘 중 하나만 보내야 한다. T12에서 [messageIndex]를 지운다.
 */
data class StoryBranchRequest(
    val title: String = "",
    val messageId: Long? = null,
    val messageIndex: Int? = null,
)
