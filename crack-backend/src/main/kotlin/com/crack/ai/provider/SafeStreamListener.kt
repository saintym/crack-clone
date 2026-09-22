package com.crack.ai.provider

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 리스너 계약(종료 콜백 정확히 1회)을 강제하는 래퍼.
 * - 종료 뒤에 오는 delta/complete/error는 무시한다.
 * - 리스너가 던진 예외는 삼켜서 프로바이더 스레드를 보호한다.
 */
class SafeStreamListener(private val delegate: StreamListener) : StreamListener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val finished = AtomicBoolean(false)

    val isFinished: Boolean get() = finished.get()

    override fun onDelta(text: String) {
        if (finished.get() || text.isEmpty()) return
        try {
            delegate.onDelta(text)
        } catch (e: Exception) {
            log.debug("StreamListener.onDelta failed: ${e.message}")
        }
    }

    override fun onComplete(fullText: String) {
        if (!finished.compareAndSet(false, true)) return
        try {
            delegate.onComplete(fullText)
        } catch (e: Exception) {
            log.debug("StreamListener.onComplete failed: ${e.message}")
        }
    }

    override fun onError(error: Throwable) {
        if (!finished.compareAndSet(false, true)) return
        try {
            delegate.onError(error)
        } catch (e: Exception) {
            log.debug("StreamListener.onError failed: ${e.message}")
        }
    }
}
