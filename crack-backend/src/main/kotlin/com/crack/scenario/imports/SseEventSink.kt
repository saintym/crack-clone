package com.crack.scenario.imports

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 생성 진행 상황을 SSE로 보내는 [ImportEventSink] (DESIGN.md §11.2).
 *
 * 클라이언트가 끊겨도 생성은 끝까지 한다(중간에 멈추면 흔적이 남는다). 끊긴 뒤에는 전송만 건너뛴다.
 * URL 가져오기(T28)와 질문으로 만들기(T48)가 같이 쓴다.
 */
class SseEventSink(private val emitter: SseEmitter) : ImportEventSink {

    private val log = LoggerFactory.getLogger(javaClass)
    private var clientGone = false

    override fun step(event: ImportStepEvent) = send(ImportSseEvents.STEP, event, MediaType.APPLICATION_JSON)
    override fun done(event: ImportDoneEvent) = send(ImportSseEvents.DONE, event, MediaType.APPLICATION_JSON)
    override fun error(message: String) = send(ImportSseEvents.ERROR, message, null)

    fun finish() {
        try {
            emitter.complete()
        } catch (e: Exception) {
            log.debug("SSE complete 실패: {}", e.message)
        }
    }

    @Synchronized
    private fun send(name: String, data: Any, mediaType: MediaType?) {
        if (clientGone) return
        try {
            emitter.send(SseEmitter.event().name(name).data(data, mediaType))
        } catch (e: Exception) {
            clientGone = true
            log.debug("SSE 전송 실패(연결 끊김 추정): {}", e.message)
        }
    }
}
