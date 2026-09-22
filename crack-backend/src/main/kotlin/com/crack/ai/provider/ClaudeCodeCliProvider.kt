package com.crack.ai.provider

import com.crack.ai.config.AiProperties
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.MessageRole
import com.crack.ai.support.daemonThreadFactory
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Claude Code CLI를 통한 AI 호출 (Max 구독 활용, 토큰 비용 없음).
 * 프롬프트는 stdin으로 넘긴다(ARG_MAX 회피). 스트리밍은
 * `--output-format stream-json --verbose --include-partial-messages`를 쓴다.
 * 설정: `crack.ai.cli.path`, `crack.ai.cli.timeout-seconds`, `crack.ai.cli.models.*`
 */
@Component
class ClaudeCodeCliProvider(
    private val objectMapper: ObjectMapper,
    private val aiProperties: AiProperties
) : AiProvider {

    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newCachedThreadPool(daemonThreadFactory("claude-cli"))
    private val watchdog = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("claude-cli-watchdog"))

    override val name = "claude-code-cli"

    override fun chat(request: AiRequest): String {
        val run = startProcess(request, outputFormat = "json")
        val output = run.process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val exitCode = run.finish()

        if (run.timedOut) {
            throw TimeoutException("Claude Code CLI timed out after ${timeoutSeconds()}s")
        }
        if (exitCode != 0) {
            val stderr = run.stderr()
            log.error("Claude Code CLI failed (exit=$exitCode): $stderr")
            throw RuntimeException("Claude Code CLI failed (exit=$exitCode): ${stderr.take(500)}")
        }

        val node = try {
            objectMapper.readTree(output)
        } catch (e: Exception) {
            log.debug("Failed to parse JSON output, using raw: ${e.message}")
            return output.trim()
        }
        val result = node?.get("result")?.takeIf { it.isTextual }?.asText()
        if (node?.path("is_error")?.asBoolean(false) == true) {
            throw RuntimeException("Claude Code CLI returned error: ${result ?: node.path("subtype").asText()}")
        }
        return result ?: output.trim()
    }

    override fun stream(request: AiRequest, listener: StreamListener) {
        executor.execute {
            try {
                val run = startProcess(request, outputFormat = "stream-json", partialMessages = true)
                val parser = CliStreamJsonParser(objectMapper)

                run.process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    parser.feed(line).forEach(listener::onDelta)
                }
                val exitCode = run.finish()
                val result = parser.text

                when {
                    run.timedOut ->
                        listener.onError(TimeoutException("Claude Code CLI timed out after ${timeoutSeconds()}s"))
                    result.isEmpty() && parser.errorMessage != null ->
                        listener.onError(RuntimeException("Claude Code CLI returned error: ${parser.errorMessage}"))
                    result.isEmpty() && exitCode != 0 ->
                        listener.onError(RuntimeException("Claude Code CLI failed (exit=$exitCode): ${run.stderr().take(500)}"))
                    else -> {
                        log.info("Claude CLI stream completed: ${result.length} chars")
                        if (result.isBlank()) {
                            log.warn("Claude CLI returned empty response. stderr: ${run.stderr().take(500)}")
                        }
                        listener.onComplete(result)
                    }
                }
            } catch (e: Exception) {
                log.error("Claude Code CLI streaming error", e)
                listener.onError(e)
            }
        }
    }

    /** 실행할 명령. 프롬프트는 인자로 넣지 않고 stdin으로 넘긴다. */
    fun buildCommand(request: AiRequest, outputFormat: String, partialMessages: Boolean = false): List<String> {
        val model = aiProperties.cli.models.forTier(aiProperties.tierFor(request.purpose))
        val command = mutableListOf(
            aiProperties.cli.path, "-p",
            "--output-format", outputFormat,
            "--model", model,
            "--tools", "",
            "--no-session-persistence"
        )
        if (outputFormat == "stream-json") {
            command.add("--verbose") // -p에서 stream-json은 --verbose가 필요
        }
        if (partialMessages) {
            command.add("--include-partial-messages")
        }
        return command
    }

    fun buildPrompt(request: AiRequest): String {
        val sb = StringBuilder()

        sb.appendLine("[System Instructions]")
        sb.appendLine(request.systemPrompt)
        sb.appendLine()

        for (msg in request.messages) {
            when (msg.role) {
                MessageRole.USER -> sb.appendLine("[User]")
                MessageRole.ASSISTANT -> sb.appendLine("[Assistant]")
            }
            sb.appendLine(msg.content)
            sb.appendLine()
        }

        return sb.toString().trim()
    }

    private fun timeoutSeconds(): Long = aiProperties.cli.timeoutSeconds.coerceAtLeast(1)

    private fun startProcess(request: AiRequest, outputFormat: String, partialMessages: Boolean = false): CliRun {
        val command = buildCommand(request, outputFormat, partialMessages)
        log.debug("Starting Claude CLI: {} (format={}, model={})", command.first(), outputFormat, command[command.indexOf("--model") + 1])

        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        val run = CliRun(process)

        // stderr는 버퍼가 차서 막히지 않도록 따로 비운다
        run.stderrFuture = CompletableFuture.supplyAsync({ readQuietly(process.errorStream) }, executor)
        run.watchdogFuture = watchdog.schedule({
            if (process.isAlive) {
                run.timedOutFlag.set(true)
                log.error("Claude Code CLI timed out after ${timeoutSeconds()}s — killing process")
                process.destroyForcibly()
            }
        }, timeoutSeconds(), TimeUnit.SECONDS)

        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(buildPrompt(request)) }
        } catch (e: Exception) {
            process.destroyForcibly()
            run.finish()
            throw e
        }
        return run
    }

    private fun readQuietly(stream: InputStream): String =
        try {
            stream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Exception) {
            ""
        }

    private class CliRun(val process: Process) {
        val timedOutFlag = AtomicBoolean(false)
        var stderrFuture: CompletableFuture<String>? = null
        var watchdogFuture: ScheduledFuture<*>? = null

        val timedOut: Boolean get() = timedOutFlag.get()

        /** 프로세스 종료를 기다리고 워치독을 해제한다. 종료 코드를 돌려준다. */
        fun finish(): Int {
            val exitCode = process.waitFor()
            watchdogFuture?.cancel(false)
            return exitCode
        }

        fun stderr(): String =
            try {
                stderrFuture?.get(5, TimeUnit.SECONDS) ?: ""
            } catch (e: Exception) {
                ""
            }
    }
}
