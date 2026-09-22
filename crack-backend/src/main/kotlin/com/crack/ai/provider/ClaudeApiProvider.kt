package com.crack.ai.provider

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.crack.ai.config.AiProperties
import com.crack.ai.config.ClaudeConfig
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.MessageRole
import com.crack.ai.dto.ModelTier
import com.crack.ai.support.daemonThreadFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/**
 * Claude API(Anthropic Java SDK)를 통한 AI 호출 (토큰 과금).
 * `claude.api-key`가 비어 있으면 사용 불가로 표시된다.
 */
@Component
class ClaudeApiProvider(
    private val claudeConfig: ClaudeConfig,
    private val aiProperties: AiProperties
) : AiProvider {

    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newCachedThreadPool(daemonThreadFactory("claude-api"))

    private val client: AnthropicClient? =
        if (claudeConfig.apiKey.isNotBlank()) {
            AnthropicOkHttpClient.builder()
                .apiKey(claudeConfig.apiKey)
                .build()
                .also {
                    log.info("Claude API client initialized. Models - opus: ${claudeConfig.opusModel}, sonnet: ${claudeConfig.model}, haiku: ${claudeConfig.haikuModel}")
                }
        } else {
            log.info("Claude API key not configured. claude-api provider is unavailable.")
            null
        }

    override val name = "claude-api"

    override fun isAvailable(): Boolean = client != null

    fun resolveModel(tier: ModelTier): String = when (tier) {
        ModelTier.OPUS -> claudeConfig.opusModel
        ModelTier.SONNET -> claudeConfig.model
        ModelTier.HAIKU -> claudeConfig.haikuModel
    }

    override fun chat(request: AiRequest): String {
        val response = requireClient().messages().create(buildParams(request))
        return response.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
    }

    override fun stream(request: AiRequest, listener: StreamListener) {
        val client = requireClient()
        val params = buildParams(request)
        executor.execute {
            try {
                val fullResponse = StringBuilder()
                client.messages().createStreaming(params).use { streamResponse ->
                    streamResponse.stream()
                        .flatMap { event -> event.contentBlockDelta().stream() }
                        .flatMap { deltaEvent -> deltaEvent.delta().text().stream() }
                        .forEach { textDelta ->
                            val text = textDelta.text()
                            fullResponse.append(text)
                            listener.onDelta(text)
                        }
                }
                listener.onComplete(fullResponse.toString())
            } catch (e: Exception) {
                log.error("Claude API streaming error", e)
                listener.onError(e)
            }
        }
    }

    private fun requireClient(): AnthropicClient =
        client ?: throw IllegalStateException("Claude API key not configured (claude.api-key).")

    private fun buildParams(request: AiRequest): MessageCreateParams {
        val modelId = resolveModel(aiProperties.tierFor(request.purpose))
        val paramsBuilder = MessageCreateParams.builder()
            .model(Model.of(modelId))
            .maxTokens(request.maxTokens?.toLong() ?: claudeConfig.maxTokens.toLong())
            .system(request.systemPrompt)

        request.messages.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> paramsBuilder.addUserMessage(msg.content)
                MessageRole.ASSISTANT -> paramsBuilder.addAssistantMessage(msg.content)
            }
        }
        return paramsBuilder.build()
    }
}
