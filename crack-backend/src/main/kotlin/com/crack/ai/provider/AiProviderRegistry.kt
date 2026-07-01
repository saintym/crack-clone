package com.crack.ai.provider

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * AI 프로바이더 레지스트리 — 이름으로 프로바이더를 선택.
 * 기본값은 application.yml의 crack.ai.default-provider로 설정.
 */
@Component
class AiProviderRegistry(
    providers: List<AiProvider>,
    @Value("\${crack.ai.default-provider:claude-code-cli}")
    private val defaultProviderName: String
) {
    private val providerMap: Map<String, AiProvider> = providers.associateBy { it.name }

    fun getDefault(): AiProvider =
        providerMap[defaultProviderName]
            ?: providerMap.values.first()

    fun getByName(name: String): AiProvider =
        providerMap[name] ?: getDefault()

    fun availableProviders(): List<String> = providerMap.keys.toList()
}
