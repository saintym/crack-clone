package com.crack.ai.provider

/**
 * 스트리밍 응답 콜백. 프로바이더는 0회 이상의 [onDelta] 뒤에
 * [onComplete] 또는 [onError] 중 정확히 하나를 한 번 호출한다.
 */
interface StreamListener {
    fun onDelta(text: String)
    fun onComplete(fullText: String)
    fun onError(error: Throwable)
}
