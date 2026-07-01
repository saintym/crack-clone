package com.crack.ai.controller

import com.crack.ai.provider.AiProviderRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ai")
class AiProviderController(
    private val registry: AiProviderRegistry
) {
    @GetMapping("/providers")
    fun listProviders(): Map<String, Any> = mapOf(
        "default" to registry.getDefault().name,
        "available" to registry.availableProviders()
    )
}
