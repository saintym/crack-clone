package com.crack.ai.provider

import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.MessageRole
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.BufferedReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Claude Code CLI를 통한 AI 호출 (Max 구독 활용, 토큰 비용 없음).
 * `claude -p "prompt" --output-format json` 명령어로 처리.
 */
@Component
class ClaudeCodeCliProvider(
    private val objectMapper: ObjectMapper,
    @Value("\${crack.ai.claude-cli-path:claude}")
    private val cliPath: String
) : AiProvider {

    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newCachedThreadPool()

    override val name = "claude-code-cli"

    override fun chat(request: AiRequest): String {
        val prompt = buildPrompt(request)
        val process = startProcess(prompt, outputFormat = "json")

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            log.error("Claude Code CLI failed (exit=$exitCode): $errorOutput")
            throw RuntimeException("Claude Code CLI failed: $errorOutput")
        }

        // Parse JSON output to extract "result" field
        return try {
            val node = objectMapper.readTree(output)
            node.get("result")?.asText() ?: output.trim()
        } catch (e: Exception) {
            log.debug("Failed to parse JSON output, using raw: ${e.message}")
            output.trim()
        }
    }

    override fun streamChat(request: AiRequest, emitter: SseEmitter) {
        executor.execute {
            try {
                val prompt = buildPrompt(request)
                val process = startProcess(prompt, outputFormat = "stream-json", verbose = true)
                val reader = process.inputStream.bufferedReader()
                val fullResponse = StringBuilder()

                processStreamOutput(reader, fullResponse, emitter)

                val completed = process.waitFor(120, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    log.error("Claude Code CLI stream timed out")
                }

                val result = fullResponse.toString()
                log.info("Claude CLI stream completed: ${result.length} chars")
                if (result.isBlank()) {
                    log.warn("Claude CLI returned empty response")
                    // Read stderr for clues
                    val errorOutput = process.errorStream.bufferedReader().readText()
                    if (errorOutput.isNotBlank()) {
                        log.warn("Claude CLI stderr: ${errorOutput.take(500)}")
                    }
                }

                emitter.send(SseEmitter.event()
                    .name("done")
                    .data(result))
                emitter.complete()

            } catch (e: Exception) {
                log.error("Claude Code CLI streaming error", e)
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

    private fun processStreamOutput(reader: BufferedReader, fullResponse: StringBuilder, emitter: SseEmitter) {
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine

            try {
                val node = objectMapper.readTree(line)
                val type = node.get("type")?.asText()

                when (type) {
                    "assistant" -> {
                        // Extract text from content blocks
                        val content = node.get("message")?.get("content")
                        if (content != null && content.isArray) {
                            for (block in content) {
                                if (block.get("type")?.asText() == "text") {
                                    val text = block.get("text")?.asText() ?: continue
                                    fullResponse.append(text)
                                    try {
                                        emitter.send(SseEmitter.event()
                                            .name("delta")
                                            .data(text))
                                    } catch (e: Exception) {
                                        log.debug("SSE send failed: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                    "content_block_delta" -> {
                        val text = node.get("delta")?.get("text")?.asText() ?: return@forEachLine
                        fullResponse.append(text)
                        try {
                            emitter.send(SseEmitter.event()
                                .name("delta")
                                .data(text))
                        } catch (e: Exception) {
                            log.debug("SSE send failed: ${e.message}")
                        }
                    }
                    "result" -> {
                        // If we haven't captured any text yet, use the result field
                        val result = node.get("result")?.asText()
                        if (result != null && fullResponse.isEmpty()) {
                            fullResponse.append(result)
                            try {
                                emitter.send(SseEmitter.event()
                                    .name("delta")
                                    .data(result))
                            } catch (e: Exception) {
                                log.debug("SSE send failed: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("Failed to parse stream line: ${line.take(100)}")
            }
        }
    }

    private fun buildPrompt(request: AiRequest): String {
        val sb = StringBuilder()

        sb.appendLine("[System Instructions]")
        sb.appendLine(request.systemPrompt)
        sb.appendLine()

        for (msg in request.messages) {
            when (msg.role) {
                MessageRole.USER -> {
                    sb.appendLine("[User]")
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                MessageRole.ASSISTANT -> {
                    sb.appendLine("[Assistant]")
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
            }
        }

        return sb.toString().trim()
    }

    private fun startProcess(prompt: String, outputFormat: String, verbose: Boolean = false): Process {
        val command = mutableListOf(
            resolveCliPath(), "-p", prompt,
            "--output-format", outputFormat,
            "--tools", "",
            "--no-session-persistence"
        )
        if (verbose) {
            command.add("--verbose")
        }

        log.debug("Starting Claude CLI: ${command.take(3)}... (format=$outputFormat, verbose=$verbose)")

        return ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
    }

    private fun resolveCliPath(): String {
        // If configured path exists, use it
        if (cliPath != "claude") return cliPath

        // Try common locations
        val commonPaths = listOf(
            "/Users/mayfly/.npm-global/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude"
        )
        for (path in commonPaths) {
            if (java.io.File(path).exists()) return path
        }

        // Fallback to just "claude" and hope it's on PATH
        return "claude"
    }
}
