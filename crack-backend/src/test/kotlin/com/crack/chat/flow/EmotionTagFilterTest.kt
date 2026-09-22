package com.crack.chat.flow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EmotionTagFilterTest {

    private class Recorder : TaggedResponseListener {
        val deltas = mutableListOf<String>()
        var body: String? = null
        var emotion: String? = null
        var error: Throwable? = null
        override fun onDelta(text: String) { deltas += text }
        override fun onComplete(body: String, emotion: String?) { this.body = body; this.emotion = emotion }
        override fun onError(error: Throwable) { this.error = error }
    }

    /** [text]를 [chunk]글자씩 흘린 뒤 완료한다. */
    private fun run(text: String, chunk: Int): Recorder {
        val recorder = Recorder()
        val filter = EmotionTagFilter(recorder)
        text.chunked(chunk).forEach(filter::onDelta)
        filter.onComplete(text)
        return recorder
    }

    private val tagged = "[감정: 경계심, 호기심]\n\n*그녀가 고개를 들었다.*\n\"누구야.\""

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 5, 7, 13, 1000])
    fun `태그가 어떻게 쪼개져 들어와도 delta에 실리지 않고 감정만 분리된다`(chunk: Int) {
        val r = run(tagged, chunk)

        assertThat(r.deltas.joinToString("")).isEqualTo("*그녀가 고개를 들었다.*\n\"누구야.\"")
        assertThat(r.deltas).noneMatch { it.contains("감정") || it.contains("[") }
        assertThat(r.body).isEqualTo("*그녀가 고개를 들었다.*\n\"누구야.\"")
        assertThat(r.emotion).isEqualTo("경계심, 호기심")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 4, 1000])
    fun `태그가 없으면 원문 그대로 통과한다`(chunk: Int) {
        val text = "*비가 내렸다.*\n\"왔구나.\""
        val r = run(text, chunk)

        assertThat(r.deltas.joinToString("")).isEqualTo(text)
        assertThat(r.body).isEqualTo(text)
        assertThat(r.emotion).isNull()
    }

    @Test
    fun `형식이 다른 태그는 그대로 통과한다`() {
        val text = "[감정: 기쁨] 그리고 본문이 같은 줄에 있다\n다음 줄"
        val r = run(text, 3)

        assertThat(r.deltas.joinToString("")).isEqualTo(text)
        assertThat(r.body).isEqualTo(text)
        assertThat(r.emotion).isNull()
    }

    @Test
    fun `공백과 CRLF가 섞인 태그도 인식한다`() {
        val text = "[ 감정 :  두려움 ]\r\n\r\n본문"
        val r = run(text, 2)

        assertThat(r.deltas.joinToString("")).isEqualTo("본문")
        assertThat(r.emotion).isEqualTo("두려움")
    }

    @Test
    fun `줄바꿈 없이 200자를 넘으면 태그 판단을 그만두고 흘린다`() {
        val text = "가".repeat(250)
        val recorder = Recorder()
        val filter = EmotionTagFilter(recorder)
        val chunks = text.chunked(10)
        chunks.take(20).forEach(filter::onDelta)
        assertThat(recorder.deltas).isEmpty() // 200자까지는 태그인지 기다린다
        filter.onDelta(chunks[20])
        // 201자째에서 판단을 끝내고 완료 전에 흘린다(무한 버퍼링 금지)
        assertThat(recorder.deltas.joinToString("")).isEqualTo(text.substring(0, 210))
        chunks.drop(21).forEach(filter::onDelta)
        filter.onComplete(text)
        assertThat(recorder.deltas.joinToString("")).isEqualTo(text)
        assertThat(recorder.body).isEqualTo(text)
    }

    @Test
    fun `태그만 있고 본문이 없으면 본문은 빈 문자열이다`() {
        val r = run("[감정: 무표정]", 3)

        assertThat(r.deltas).isEmpty()
        assertThat(r.body).isEmpty()
        assertThat(r.emotion).isEqualTo("무표정")
    }

    @Test
    fun `스트림이 줄바꿈 전에 끝나도 버퍼를 흘린다`() {
        val r = run("짧은 답", 1)

        assertThat(r.deltas.joinToString("")).isEqualTo("짧은 답")
        assertThat(r.body).isEqualTo("짧은 답")
    }

    @Test
    fun `긴 감정 값은 칼럼 크기로 자른다`() {
        val parsed = EmotionTagFilter.parse("[감정: ${"가".repeat(150)}]\n본문")
        assertThat(parsed.emotion).hasSize(EmotionTagFilter.MAX_EMOTION_LENGTH)
        assertThat(parsed.body).isEqualTo("본문")
    }

    @Test
    fun `에러는 그대로 전달한다`() {
        val recorder = Recorder()
        val filter = EmotionTagFilter(recorder)
        filter.onDelta("[감정: ")
        filter.onError(IllegalStateException("boom"))

        assertThat(recorder.deltas).isEmpty()
        assertThat(recorder.error).hasMessage("boom")
    }
}
