package com.crack.ai.provider

import com.crack.ai.dto.AiRequest
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * AI 프로바이더 추상화 인터페이스.
 * Claude API, Gemini API, Claude Code CLI 등 다양한 백엔드를 지원.
 */
interface AiProvider {
    val name: String

    /** 동기 호출 — 전체 응답을 한번에 반환 */
    fun chat(request: AiRequest): String

    /** SSE 스트리밍 호출 */
    fun streamChat(request: AiRequest, emitter: SseEmitter)
}
