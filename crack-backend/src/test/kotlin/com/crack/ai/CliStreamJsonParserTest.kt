package com.crack.ai

import com.crack.ai.provider.CliStreamJsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Claude Code CLI `-p --output-format stream-json --verbose [--include-partial-messages]` 출력 샘플.
 * `stream_event`는 `{type:"stream_event", event:<Anthropic 원시 스트림 이벤트>}` 형태다
 * (CLI 2.1.x 번들에서 `yield{type:"stream_event",event:...}` 확인).
 */
class CliStreamJsonParserTest {

    private val parser = CliStreamJsonParser(ObjectMapper())

    private fun feedAll(lines: List<String>): List<String> = lines.flatMap { parser.feed(it) }

    companion object {
        val INIT = """{"type":"system","subtype":"init","session_id":"s1","model":"claude-opus-5","tools":[]}"""

        /** --include-partial-messages: partial 이벤트 뒤에 같은 내용의 assistant 메시지와 result가 또 온다 */
        val PARTIAL_STREAM = listOf(
            INIT,
            """{"type":"stream_event","event":{"type":"message_start","message":{"id":"m1","role":"assistant","content":[]}},"session_id":"s1"}""",
            """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}},"session_id":"s1"}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"안녕"}},"session_id":"s1"}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"하세요, "}},"session_id":"s1"}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"여행자님."}},"session_id":"s1"}""",
            """{"type":"stream_event","event":{"type":"content_block_stop","index":0},"session_id":"s1"}""",
            """{"type":"assistant","message":{"id":"m1","role":"assistant","content":[{"type":"text","text":"안녕하세요, 여행자님."}]},"session_id":"s1"}""",
            """{"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}},"session_id":"s1"}""",
            """{"type":"stream_event","event":{"type":"message_stop"},"session_id":"s1"}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"안녕하세요, 여행자님.","session_id":"s1"}""",
        )

        /** partial 없이(옛 형식) assistant 블록만 오는 경우 */
        val ASSISTANT_ONLY = listOf(
            INIT,
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"첫 문장. "},{"type":"tool_use","id":"t","name":"x","input":{}},{"type":"text","text":"둘째 문장."}]}}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"첫 문장. 둘째 문장."}""",
        )
    }

    @Test
    fun `stream_event의 text_delta를 순서대로 흘리고 assistant와 result는 중복시키지 않는다`() {
        val deltas = feedAll(PARTIAL_STREAM)

        assertThat(deltas).containsExactly("안녕", "하세요, ", "여행자님.")
        assertThat(parser.text).isEqualTo("안녕하세요, 여행자님.")
        assertThat(parser.errorMessage).isNull()
    }

    @Test
    fun `partial이 없으면 assistant의 text 블록을 쓰고 result는 무시한다`() {
        val deltas = feedAll(ASSISTANT_ONLY)

        assertThat(deltas).containsExactly("첫 문장. ", "둘째 문장.")
        assertThat(parser.text).isEqualTo("첫 문장. 둘째 문장.")
    }

    @Test
    fun `텍스트를 하나도 못 받았으면 result를 폴백으로 쓴다`() {
        val deltas = feedAll(listOf(INIT, """{"type":"result","subtype":"success","is_error":false,"result":"결과만 있음"}"""))

        assertThat(deltas).containsExactly("결과만 있음")
        assertThat(parser.text).isEqualTo("결과만 있음")
    }

    @Test
    fun `옛 최상위 content_block_delta도 처리하고 이후 assistant는 중복시키지 않는다`() {
        val deltas = feedAll(listOf(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"옛 "}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"형식"}}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"옛 형식"}]}}""",
        ))

        assertThat(deltas).containsExactly("옛 ", "형식")
    }

    @Test
    fun `text가 아닌 delta와 깨진 줄, 빈 줄은 무시한다`() {
        val deltas = feedAll(listOf(
            "",
            "not json",
            "[1,2]",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"음..."}}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{"}}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"본문"}}}""",
        ))

        assertThat(deltas).containsExactly("본문")
    }

    @Test
    fun `is_error result는 errorMessage로 남기고 텍스트로 흘리지 않는다`() {
        val deltas = feedAll(listOf(
            INIT,
            """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Invalid model"}""",
        ))

        assertThat(deltas).isEmpty()
        assertThat(parser.text).isEmpty()
        assertThat(parser.errorMessage).isEqualTo("Invalid model")
    }
}
