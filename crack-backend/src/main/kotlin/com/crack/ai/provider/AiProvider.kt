package com.crack.ai.provider

import com.crack.ai.dto.AiRequest

/**
 * AI 프로바이더 추상화 인터페이스.
 * 앱 코드는 프로바이더를 직접 쓰지 않고 [com.crack.ai.service.AiGateway]를 거친다.
 */
interface AiProvider {
    val name: String

    /** 동기 호출 — 전체 응답을 한번에 반환 */
    fun chat(request: AiRequest): String

    /** 비동기 스트리밍 호출. 리스너는 정확히 한 번 complete 또는 error를 받는다. */
    fun stream(request: AiRequest, listener: StreamListener)

    /** 지금 호출할 수 있는지 (예: API 키 유무). 레지스트리는 사용 가능한 프로바이더만 노출한다. */
    fun isAvailable(): Boolean = true
}
