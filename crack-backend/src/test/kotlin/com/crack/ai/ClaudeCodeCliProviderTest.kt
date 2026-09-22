package com.crack.ai

import com.crack.ai.config.AiProperties
import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.provider.ClaudeCodeCliProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClaudeCodeCliProviderTest {

    private val properties = AiProperties(
        cli = AiProperties.Cli(
            path = "/opt/tools/claude",
            timeoutSeconds = 30,
            models = AiProperties.CliModels(opus = "opus-alias", sonnet = "sonnet-alias", haiku = "haiku-alias"),
        )
    )
    private val provider = ClaudeCodeCliProvider(ObjectMapper(), properties)

    private val request = AiRequest(
        systemPrompt = "SYSTEM-PROMPT",
        messages = listOf(ChatMessage(MessageRole.USER, "USER-MESSAGE"), ChatMessage(MessageRole.ASSISTANT, "ASSISTANT-REPLY")),
        purpose = AiPurpose.CHAT,
    )

    @Test
    fun `스트리밍 명령은 설정 경로와 partial 옵션, 목적별 모델 별칭을 쓰고 프롬프트는 인자로 넣지 않는다`() {
        val command = provider.buildCommand(request, "stream-json", partialMessages = true)

        assertThat(command.first()).isEqualTo("/opt/tools/claude")
        assertThat(command).contains("-p", "--verbose", "--include-partial-messages", "--no-session-persistence")
        assertThat(command[command.indexOf("--output-format") + 1]).isEqualTo("stream-json")
        assertThat(command[command.indexOf("--model") + 1]).isEqualTo("opus-alias")
        assertThat(command.joinToString(" ")).doesNotContain("SYSTEM-PROMPT", "USER-MESSAGE")
    }

    @Test
    fun `동기 명령은 json 형식이고 목적에 따라 모델이 바뀐다`() {
        val record = provider.buildCommand(request.copy(purpose = AiPurpose.RECORD), "json")
        val utility = provider.buildCommand(request.copy(purpose = AiPurpose.UTILITY), "json")

        assertThat(record[record.indexOf("--output-format") + 1]).isEqualTo("json")
        assertThat(record).doesNotContain("--include-partial-messages")
        assertThat(record[record.indexOf("--model") + 1]).isEqualTo("sonnet-alias")
        assertThat(utility[utility.indexOf("--model") + 1]).isEqualTo("haiku-alias")
    }

    @Test
    fun `stdin 프롬프트는 시스템 지시와 대화를 순서대로 담는다`() {
        val prompt = provider.buildPrompt(request)

        assertThat(prompt).startsWith("[System Instructions]\nSYSTEM-PROMPT")
        assertThat(prompt.indexOf("[User]\nUSER-MESSAGE")).isLessThan(prompt.indexOf("[Assistant]\nASSISTANT-REPLY"))
    }

    @Test
    fun `CLI를 실행할 수 없으면 stream은 onError를 한 번 호출한다`() {
        val missing = ClaudeCodeCliProvider(ObjectMapper(), AiProperties(cli = AiProperties.Cli(path = "/nonexistent/claude-cli")))
        val listener = RecordingListener()

        missing.stream(request, listener)

        assertThat(listener.await()).isTrue()
        assertThat(listener.errors).hasSize(1)
        assertThat(listener.completes).isEmpty()
    }
}
