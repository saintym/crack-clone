package com.crack.ai

import com.crack.ai.config.AiProperties
import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ModelTier
import com.crack.ai.provider.AiProvider
import com.crack.ai.provider.AiProviderRegistry
import com.crack.ai.provider.StreamListener
import com.crack.ai.service.AiGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AiGatewayTest {

    private class StubProvider(
        override val name: String,
        private val available: Boolean = true,
        private val streamAction: (StreamListener) -> Unit = { it.onDelta("[$name]"); it.onComplete("[$name]") }
    ) : AiProvider {
        val chatCalls = mutableListOf<AiRequest>()
        override fun chat(request: AiRequest): String {
            chatCalls.add(request)
            return "reply from $name"
        }
        override fun stream(request: AiRequest, listener: StreamListener) = streamAction(listener)
        override fun isAvailable(): Boolean = available
    }

    private val request = AiRequest(systemPrompt = "sys", messages = emptyList(), purpose = AiPurpose.CHAT)

    private fun gateway(default: String, vararg providers: AiProvider) =
        AiGateway(AiProviderRegistry(providers.toList(), AiProperties(defaultProvider = default)))

    @Test
    fun `provider를 지정하지 않으면 기본 프로바이더로 보낸다`() {
        val a = StubProvider("a")
        val b = StubProvider("b")
        val gateway = gateway("b", a, b)

        assertThat(gateway.chat(request)).isEqualTo("reply from b")
        assertThat(b.chatCalls).containsExactly(request)
        assertThat(a.chatCalls).isEmpty()
    }

    @Test
    fun `지정한 프로바이더로 보낸다`() {
        val gateway = gateway("b", StubProvider("a"), StubProvider("b"))

        assertThat(gateway.chat(request, "a")).isEqualTo("reply from a")

        val listener = RecordingListener()
        gateway.stream(request, listener, "a")
        assertThat(listener.deltas).containsExactly("[a]")
        assertThat(listener.completes).containsExactly("[a]")
    }

    @Test
    fun `없는 이름이나 빈 이름은 기본 프로바이더로 대체한다`() {
        val gateway = gateway("b", StubProvider("a"), StubProvider("b"))

        assertThat(gateway.chat(request, "nope")).isEqualTo("reply from b")
        assertThat(gateway.chat(request, "")).isEqualTo("reply from b")
    }

    @Test
    fun `사용 불가 프로바이더는 노출하지 않고 기본값도 사용 가능한 것으로 대체한다`() {
        val api = StubProvider("claude-api", available = false)
        val cli = StubProvider("claude-code-cli")
        val registry = AiProviderRegistry(listOf(api, cli), AiProperties(defaultProvider = "claude-api"))

        assertThat(registry.availableProviders()).containsExactly("claude-code-cli")
        assertThat(registry.find("claude-api")).isNull()
        assertThat(registry.getDefault().name).isEqualTo("claude-code-cli")
        assertThat(AiGateway(registry).chat(request, "claude-api")).isEqualTo("reply from claude-code-cli")
    }

    @Test
    fun `사용 가능한 프로바이더가 없으면 chat은 예외, stream은 onError`() {
        val gateway = gateway("x", StubProvider("x", available = false))

        assertThatThrownBy { gateway.chat(request) }.isInstanceOf(IllegalStateException::class.java)

        val listener = RecordingListener()
        gateway.stream(request, listener)
        assertThat(listener.errors).hasSize(1)
        assertThat(listener.completes).isEmpty()
    }

    @Test
    fun `프로바이더가 계약을 어겨도 리스너는 종료 콜백을 정확히 한 번 받는다`() {
        val misbehaving = StubProvider("bad", streamAction = { l ->
            l.onDelta("a")
            l.onComplete("a")
            l.onDelta("late")
            l.onComplete("again")
            l.onError(RuntimeException("late error"))
        })
        val listener = RecordingListener()

        gateway("bad", misbehaving).stream(request, listener)

        assertThat(listener.deltas).containsExactly("a")
        assertThat(listener.completes).containsExactly("a")
        assertThat(listener.errors).isEmpty()
    }

    @Test
    fun `stream 호출 중 동기 예외는 onError로 전달한다`() {
        val throwing = StubProvider("t", streamAction = { throw IllegalStateException("boom") })
        val listener = RecordingListener()

        gateway("t", throwing).stream(request, listener)

        assertThat(listener.errors.map { it.message }).containsExactly("boom")
    }

    @Test
    fun `목적별 모델 등급은 기본 매핑을 쓰고 설정으로 바꿀 수 있다`() {
        val defaults = AiProperties()
        assertThat(defaults.tierFor(AiPurpose.CHAT)).isEqualTo(ModelTier.OPUS)
        assertThat(defaults.tierFor(AiPurpose.RECORD)).isEqualTo(ModelTier.SONNET)
        assertThat(defaults.tierFor(AiPurpose.UTILITY)).isEqualTo(ModelTier.HAIKU)

        val custom = AiProperties(purposeTiers = mapOf("chat" to "sonnet", "RECORD" to "Opus", "utility" to "unknown"))
        assertThat(custom.tierFor(AiPurpose.CHAT)).isEqualTo(ModelTier.SONNET)
        assertThat(custom.tierFor(AiPurpose.RECORD)).isEqualTo(ModelTier.OPUS)
        assertThat(custom.tierFor(AiPurpose.UTILITY)).isEqualTo(ModelTier.HAIKU)
    }
}
