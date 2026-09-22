package com.crack.ai.provider

import com.crack.ai.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * AI 프로바이더 레지스트리 — 이름으로 프로바이더를 선택.
 * 기본값은 `crack.ai.default-provider`. 사용 불가(`isAvailable() == false`)인 프로바이더는 노출하지 않는다.
 */
@Component
class AiProviderRegistry(
    providers: List<AiProvider>,
    private val aiProperties: AiProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val providerMap: Map<String, AiProvider> = providers.associateBy { it.name }

    /** 이름에 해당하는 사용 가능한 프로바이더. 없거나 사용 불가면 null */
    fun find(name: String): AiProvider? = providerMap[name]?.takeIf { it.isAvailable() }

    fun getDefault(): AiProvider =
        find(aiProperties.defaultProvider)
            ?: providerMap.values.firstOrNull { it.isAvailable() }
                ?.also { log.warn("기본 프로바이더 '${aiProperties.defaultProvider}'를 쓸 수 없어 '${it.name}'로 대체합니다.") }
            ?: throw IllegalStateException("사용 가능한 AI 프로바이더가 없습니다.")

    fun getByName(name: String): AiProvider =
        find(name) ?: getDefault().also {
            log.warn("프로바이더 '$name'를 쓸 수 없어 기본 프로바이더 '${it.name}'로 대체합니다.")
        }

    fun availableProviders(): List<String> =
        providerMap.values.filter { it.isAvailable() }.map { it.name }
}
