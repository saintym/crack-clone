package com.crack.ai.provider

import com.crack.ai.dto.AiRequest
import com.crack.ai.service.ClaudeService
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Claude API를 통한 AI 호출 (토큰 과금)
 */
@Component
class ClaudeApiProvider(
    private val claudeService: ClaudeService
) : AiProvider {

    override val name = "claude-api"

    override fun chat(request: AiRequest): String = claudeService.chat(request)

    override fun streamChat(request: AiRequest, emitter: SseEmitter) = claudeService.streamChat(request, emitter)
}
