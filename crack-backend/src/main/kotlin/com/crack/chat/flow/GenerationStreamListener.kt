package com.crack.chat.flow

import com.crack.message.dto.MessageView
import com.crack.message.dto.ResponseTags
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 응답 생성 한 번의 끝을 맡는다: `delta` 전달, 완료 시 저장 → 락 해제 → `done`, 실패 시 `error`.
 *
 * - **클라이언트가 끊겨도 저장은 끝까지 한다.** SSE 전송 실패는 기록만 하고 무시한다.
 * - 저장은 [save]가 한다(MessageService의 트랜잭션 메서드). 실패하면 아무것도 저장되지 않은 채 `error`를 보낸다.
 * - 어떤 경우든 종료 콜백에서 락 티켓을 푼다.
 */
class GenerationStreamListener(
    private val storyId: Long,
    private val emitter: SseEmitter,
    private val ticket: StoryGenerationLock.Ticket,
    private val save: (body: String, tags: ResponseTags) -> MessageView,
    private val afterSave: (MessageView) -> Unit,
) : TaggedResponseListener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val clientGone = AtomicBoolean(false)

    override fun onDelta(text: String) {
        send(SseEvents.DELTA, text, null)
    }

    override fun onComplete(body: String, tags: ResponseTags) {
        try {
            if (body.isBlank()) {
                log.warn("빈 AI 응답 — 저장하지 않음: storyId=$storyId")
                send(SseEvents.ERROR, EMPTY_RESPONSE_MESSAGE, null)
                return
            }
            val saved = save(body, tags)
            ticket.release() // done을 받은 클라이언트가 바로 다음 요청을 보내도 409가 나지 않게 먼저 푼다
            afterSave(saved)
            send(SseEvents.DONE, saved, MediaType.APPLICATION_JSON)
        } catch (e: Exception) {
            log.error("AI 응답 저장 실패: storyId=$storyId", e)
            send(SseEvents.ERROR, e.message ?: SAVE_FAILED_MESSAGE, null)
        } finally {
            ticket.release()
            finish()
        }
    }

    override fun onError(error: Throwable) {
        try {
            log.warn("AI 응답 생성 실패: storyId=$storyId, ${error.message}")
            send(SseEvents.ERROR, error.message ?: GENERATION_FAILED_MESSAGE, null)
        } finally {
            ticket.release()
            finish()
        }
    }

    private fun send(name: String, data: Any, mediaType: MediaType?) {
        if (clientGone.get()) return
        try {
            emitter.send(SseEmitter.event().name(name).data(data, mediaType))
        } catch (e: Exception) {
            // 연결이 끊겼거나 타임아웃으로 이미 끝난 emitter. 생성과 저장은 계속한다.
            clientGone.set(true)
            log.debug("SSE 전송 실패(클라이언트 연결 끊김 추정): storyId=$storyId, ${e.message}")
        }
    }

    private fun finish() {
        try {
            emitter.complete()
        } catch (e: Exception) {
            log.debug("SSE complete 실패: ${e.message}")
        }
    }

    companion object {
        const val EMPTY_RESPONSE_MESSAGE = "AI 응답이 비어 있습니다"
        const val SAVE_FAILED_MESSAGE = "응답을 저장하지 못했습니다"
        const val GENERATION_FAILED_MESSAGE = "응답을 생성하지 못했습니다"
    }
}

/** SSE 이벤트 이름 (DESIGN.md §5.2) */
object SseEvents {
    const val USER = "user"
    const val DELTA = "delta"
    const val DONE = "done"
    const val ERROR = "error"
}
