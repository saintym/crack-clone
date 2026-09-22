package com.crack.ai

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.provider.FakeAiProvider
import com.crack.ai.provider.FakeResponses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

class FakeAiProviderTest {

    private val responses = FakeResponses()
    private val syncProvider = FakeAiProvider(responses, Executor { it.run() })

    private fun request(purpose: AiPurpose) = AiRequest(systemPrompt = "sys", messages = emptyList(), purpose = purpose)

    @Test
    fun `등록된 응답이 없으면 기본 롤플레이 문장을 쓴다`() {
        assertThat(syncProvider.chat(request(AiPurpose.CHAT))).isEqualTo(FakeResponses.DEFAULT_RESPONSE)
    }

    @Test
    fun `목적별로 등록한 응답을 쓴다`() {
        responses.register(AiPurpose.RECORD, "기록 응답")
        responses.register(AiPurpose.UTILITY) { req -> "echo:${req.systemPrompt}" }

        assertThat(syncProvider.chat(request(AiPurpose.RECORD))).isEqualTo("기록 응답")
        assertThat(syncProvider.chat(request(AiPurpose.UTILITY))).isEqualTo("echo:sys")
        assertThat(syncProvider.chat(request(AiPurpose.CHAT))).isEqualTo(FakeResponses.DEFAULT_RESPONSE)

        responses.reset()
        assertThat(syncProvider.chat(request(AiPurpose.RECORD))).isEqualTo(FakeResponses.DEFAULT_RESPONSE)
    }

    @Test
    fun `stream은 3~5조각 delta를 순서대로 보낸 뒤 complete를 한 번 호출한다`() {
        val listener = RecordingListener()

        syncProvider.stream(request(AiPurpose.CHAT), listener)

        assertThat(listener.deltas.size).isBetween(3, 5)
        assertThat(listener.deltas.joinToString("")).isEqualTo(FakeResponses.DEFAULT_RESPONSE)
        assertThat(listener.completes).containsExactly(FakeResponses.DEFAULT_RESPONSE)
        assertThat(listener.errors).isEmpty()
    }

    @Test
    fun `기본 생성자는 비동기로 스트리밍한다`() {
        responses.register(AiPurpose.CHAT, "하나 둘 셋 넷 다섯 여섯 일곱 여덟 아홉 열")
        val listener = RecordingListener()

        FakeAiProvider(responses).stream(request(AiPurpose.CHAT), listener)

        assertThat(listener.await()).isTrue()
        assertThat(listener.deltas.joinToString("")).isEqualTo("하나 둘 셋 넷 다섯 여섯 일곱 여덟 아홉 열")
        assertThat(listener.completes).hasSize(1)
    }

    @Test
    fun `split은 원문을 보존하고 짧은 텍스트와 서러게이트 쌍을 처리한다`() {
        assertThat(FakeResponses.split("")).isEmpty()
        assertThat(FakeResponses.split("ab")).containsExactly("a", "b")
        val emoji = "😀😁😂😃"
        val pieces = FakeResponses.split(emoji)
        assertThat(pieces.joinToString("")).isEqualTo(emoji)
        assertThat(pieces).allSatisfy { assertThat(it.codePointCount(0, it.length)).isGreaterThan(0) }
        val long = "가".repeat(500)
        assertThat(FakeResponses.split(long)).hasSize(5)
    }

    @Test
    fun `응답 생성 중 예외는 onError로 전달한다`() {
        responses.register(AiPurpose.CHAT) { throw IllegalStateException("fail") }
        val listener = RecordingListener()

        syncProvider.stream(request(AiPurpose.CHAT), listener)

        assertThat(listener.errors).hasSize(1)
        assertThat(listener.completes).isEmpty()
    }
}
