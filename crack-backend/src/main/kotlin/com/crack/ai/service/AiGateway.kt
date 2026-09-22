package com.crack.ai.service

import com.crack.ai.dto.AiRequest
import com.crack.ai.provider.AiProvider
import com.crack.ai.provider.AiProviderRegistry
import com.crack.ai.provider.SafeStreamListener
import com.crack.ai.provider.StreamListener
import org.springframework.stereotype.Component

/**
 * 모든 AI 호출의 단일 진입점. 앱 코드는 프로바이더나 SDK를 직접 쓰지 않고 이것만 쓴다.
 * [provider]가 null이면 기본 프로바이더, 지정했지만 쓸 수 없으면 기본 프로바이더로 대체한다.
 */
@Component
class AiGateway(
    private val registry: AiProviderRegistry
) {
    fun chat(request: AiRequest, provider: String? = null): String =
        resolve(provider).chat(request)

    /** 비동기 스트리밍. 리스너는 정확히 한 번 complete 또는 error를 받는다. */
    fun stream(request: AiRequest, listener: StreamListener, provider: String? = null) {
        val safeListener = SafeStreamListener(listener)
        try {
            resolve(provider).stream(request, safeListener)
        } catch (e: Exception) {
            safeListener.onError(e)
        }
    }

    private fun resolve(provider: String?): AiProvider =
        if (provider.isNullOrBlank()) registry.getDefault() else registry.getByName(provider)
}
