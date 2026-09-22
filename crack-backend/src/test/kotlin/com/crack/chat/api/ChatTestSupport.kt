package com.crack.chat.api

import com.crack.ai.dto.AiRequest
import com.crack.ai.provider.AiProvider
import com.crack.ai.provider.StreamListener
import com.crack.chat.flow.AfterTurnEvent
import com.crack.chat.flow.AfterTurnHook
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 테스트가 스트림 동작(조각, 실패, 대기)을 직접 정하는 프로바이더. 요청 본문에 `provider: "scripted"`를 줘서 쓴다.
 * 스크립트는 별도 데몬 스레드에서 돈다(실제 프로바이더처럼 비동기).
 */
class ScriptedAiProvider : AiProvider {
    override val name = NAME

    @Volatile
    var script: (AiRequest, StreamListener) -> Unit = { _, listener -> listener.onComplete("") }

    override fun chat(request: AiRequest): String = throw UnsupportedOperationException()

    override fun stream(request: AiRequest, listener: StreamListener) {
        Thread({
            try {
                script(request, listener)
            } catch (e: Exception) {
                listener.onError(e)
            }
        }, "scripted-ai").apply { isDaemon = true }.start()
    }

    companion object {
        const val NAME = "scripted"
    }
}

class RecordingAfterTurnHook : AfterTurnHook {
    val events = CopyOnWriteArrayList<AfterTurnEvent>()
    override fun afterTurn(event: AfterTurnEvent) {
        events += event
    }
}

/** 훅이 실패해도 응답 저장과 done에는 영향이 없어야 한다. */
class FailingAfterTurnHook : AfterTurnHook {
    override fun afterTurn(event: AfterTurnEvent) = throw IllegalStateException("hook failure")
}

@TestConfiguration
class ChatTestConfig {
    @Bean
    fun scriptedAiProvider() = ScriptedAiProvider()

    @Bean
    fun recordingAfterTurnHook() = RecordingAfterTurnHook()

    @Bean
    fun failingAfterTurnHook() = FailingAfterTurnHook()
}

data class SseEvent(val name: String, val data: String)

/** `event:`/`data:` 줄로 된 SSE 본문을 이벤트 목록으로 나눈다. 여러 `data:` 줄은 `\n`으로 잇는다. */
fun parseSse(body: String): List<SseEvent> {
    val events = mutableListOf<SseEvent>()
    var name: String? = null
    val data = mutableListOf<String>()
    fun flush() {
        if (name != null || data.isNotEmpty()) events += SseEvent(name ?: "message", data.joinToString("\n"))
        name = null
        data.clear()
    }
    for (line in body.split("\n")) {
        when {
            line.isEmpty() -> flush()
            line.startsWith("event:") -> name = line.removePrefix("event:").removePrefix(" ")
            line.startsWith("data:") -> data += line.removePrefix("data:")
        }
    }
    flush()
    return events
}

/** 조건이 참이 될 때까지 기다린다(비동기 저장 확인용). */
fun awaitUntil(timeoutMs: Long = 5_000, message: String = "조건", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("시간 안에 충족되지 않음: $message")
        Thread.sleep(20)
    }
}
