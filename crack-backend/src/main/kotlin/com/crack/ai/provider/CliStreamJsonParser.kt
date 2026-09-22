package com.crack.ai.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Claude Code CLI `--output-format stream-json --verbose --include-partial-messages` 출력 파서.
 * 한 스트림(프로세스 1회)마다 새 인스턴스를 쓴다. 줄 단위로 [feed]하면 흘려보낼 delta 텍스트를 돌려준다.
 *
 * 처리 규칙 (중복 출력 없음):
 * - `stream_event` + `event.type == content_block_delta` + `delta.type == text_delta` → delta
 * - 최상위 `content_block_delta` (옛 형식) → delta
 * - `assistant` 메시지의 text 블록 → partial delta를 한 번도 못 받았을 때만 delta
 *   (`--include-partial-messages`에서는 partial 뒤에 같은 내용의 assistant 메시지가 또 온다)
 * - `result` → 지금까지 받은 텍스트가 없을 때만 `result` 필드를 delta로 (폴백).
 *   `is_error: true`면 [errorMessage]를 채운다.
 */
class CliStreamJsonParser(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val buffer = StringBuilder()
    private var sawPartial = false

    /** 지금까지 흘려보낸 전체 텍스트 */
    val text: String get() = buffer.toString()

    /** `result` 줄이 `is_error: true`였을 때의 메시지 */
    var errorMessage: String? = null
        private set

    fun feed(line: String): List<String> {
        if (line.isBlank()) return emptyList()
        val node = try {
            objectMapper.readTree(line)
        } catch (e: Exception) {
            log.debug("Failed to parse stream line: ${line.take(100)}")
            return emptyList()
        }
        if (node == null || !node.isObject) return emptyList()

        val deltas = when (node.path("type").asText()) {
            "stream_event" -> listOfNotNull(textDelta(node.path("event"))).also { if (it.isNotEmpty()) sawPartial = true }
            "content_block_delta" -> listOfNotNull(textDelta(node)).also { if (it.isNotEmpty()) sawPartial = true }
            "assistant" -> if (sawPartial) emptyList() else assistantTexts(node)
            "result" -> handleResult(node)
            else -> emptyList()
        }
        deltas.forEach { buffer.append(it) }
        return deltas
    }

    private fun textDelta(event: JsonNode): String? {
        if (event.path("type").asText() != "content_block_delta") return null
        val delta = event.path("delta")
        if (delta.path("type").asText() != "text_delta") return null
        return delta.path("text").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }
    }

    private fun assistantTexts(node: JsonNode): List<String> {
        val content = node.path("message").path("content")
        if (!content.isArray) return emptyList()
        return content
            .filter { it.path("type").asText() == "text" }
            .mapNotNull { block -> block.path("text").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() } }
    }

    private fun handleResult(node: JsonNode): List<String> {
        val result = node.path("result").takeIf { it.isTextual }?.asText()
        if (node.path("is_error").asBoolean(false)) {
            errorMessage = result?.takeIf { it.isNotBlank() } ?: node.path("subtype").asText("error")
            return emptyList()
        }
        return if (buffer.isEmpty() && !result.isNullOrEmpty()) listOf(result) else emptyList()
    }
}
