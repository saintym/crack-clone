package com.crack.ai.service

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.crack.ai.config.ClaudeConfig
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.MessageRole
import com.crack.ai.dto.ModelTier
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.Executors

@Service
class ClaudeService(
    private val claudeConfig: ClaudeConfig
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newCachedThreadPool()
    private lateinit var client: AnthropicClient

    @PostConstruct
    fun init() {
        if (claudeConfig.apiKey.isNotBlank()) {
            client = AnthropicOkHttpClient.builder()
                .apiKey(claudeConfig.apiKey)
                .build()
            log.info("Claude API client initialized. Models - opus: ${claudeConfig.opusModel}, sonnet: ${claudeConfig.model}, haiku: ${claudeConfig.haikuModel}")
        } else {
            log.warn("Claude API key not configured. AI features will not work.")
        }
    }

    fun resolveModel(tier: ModelTier): String = when (tier) {
        ModelTier.OPUS -> claudeConfig.opusModel
        ModelTier.SONNET -> claudeConfig.model
        ModelTier.HAIKU -> claudeConfig.haikuModel
    }

    fun streamChat(request: AiRequest, emitter: SseEmitter) {
        executor.execute {
            try {
                val modelId = resolveModel(request.modelTier)
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

                val params = paramsBuilder.build()
                val fullResponse = StringBuilder()

                client.messages().createStreaming(params).use { streamResponse ->
                    streamResponse.stream()
                        .flatMap { event -> event.contentBlockDelta().stream() }
                        .flatMap { deltaEvent -> deltaEvent.delta().text().stream() }
                        .forEach { textDelta ->
                            val text = textDelta.text()
                            fullResponse.append(text)
                            try {
                                emitter.send(SseEmitter.event()
                                    .name("delta")
                                    .data(text))
                            } catch (e: Exception) {
                                log.debug("SSE send failed (client likely disconnected): ${e.message}")
                            }
                        }
                }

                emitter.send(SseEmitter.event()
                    .name("done")
                    .data(fullResponse.toString()))
                emitter.complete()

            } catch (e: Exception) {
                log.error("Claude API streaming error", e)
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data(e.message ?: "Unknown error"))
                    emitter.completeWithError(e)
                } catch (sendError: Exception) {
                    log.debug("Failed to send error via SSE: ${sendError.message}")
                }
            }
        }
    }

    fun chat(request: AiRequest): String {
        val modelId = resolveModel(request.modelTier)
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

        val response = client.messages().create(paramsBuilder.build())
        return response.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
    }
}
