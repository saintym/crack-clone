package com.crack.chat.service

import com.crack.ai.provider.StreamListener
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * [StreamListener] → [SseEmitter] 어댑터. 이벤트 이름: `delta` / `done` / `error`.
 * T01의 임시 연결 — T07이 서버 측 저장을 포함한 흐름으로 교체한다.
 */
class SseStreamListener(private val emitter: SseEmitter) : StreamListener {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onDelta(text: String) {
        try {
            emitter.send(SseEmitter.event().name("delta").data(text))
        } catch (e: Exception) {
            log.debug("SSE send failed (client likely disconnected): ${e.message}")
        }
    }

    override fun onComplete(fullText: String) {
        try {
            emitter.send(SseEmitter.event().name("done").data(fullText))
            emitter.complete()
        } catch (e: Exception) {
            log.debug("SSE done send failed: ${e.message}")
        }
    }

    override fun onError(error: Throwable) {
        try {
            emitter.send(SseEmitter.event().name("error").data(error.message ?: "Unknown error"))
            emitter.completeWithError(error)
        } catch (e: Exception) {
            log.debug("Failed to send error via SSE: ${e.message}")
        }
    }
}
