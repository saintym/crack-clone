package com.crack.chat.flow

import com.crack.message.dto.ResponseTags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 첫 줄 감정·인물 태그 필터 (DESIGN.md §5.3, D19·D31) */
class EmotionTagFilterTest {

    private class Recorder : TaggedResponseListener {
        val deltas = mutableListOf<String>()
        var body: String? = null
        var tags: ResponseTags = ResponseTags.NONE
        var error: Throwable? = null
        override fun onDelta(text: String) { deltas += text }
        override fun onComplete(body: String, tags: ResponseTags) { this.body = body; this.tags = tags }
        override fun onError(error: Throwable) { this.error = error }

        val emotion: String? get() = tags.emotion
        val speaker: String? get() = tags.speaker
        val speakerVariant: String? get() = tags.speakerVariant
        /** delta에 태그 조각이 하나도 실리지 않았는지 */
        fun assertNoTagLeak() {
            assertThat(deltas).noneMatch { it.contains("감정") || it.contains("인물") || it.contains("[") }
        }
    }

    /** [text]를 [chunk]글자씩 흘린 뒤 완료한다. */
    private fun run(text: String, chunk: Int): Recorder {
        val recorder = Recorder()
        val filter = EmotionTagFilter(recorder)
        text.chunked(chunk).forEach(filter::onDelta)
        filter.onComplete(text)
        return recorder
    }

    private val body = "*그녀가 고개를 들었다.*\n\"누구야.\""

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 5, 7, 13, 1000])
    fun `태그가 어떻게 쪼개져 들어와도 delta에 실리지 않고 감정만 분리된다`(chunk: Int) {
        val r = run("[감정: 경계심, 호기심]\n\n$body", chunk)

        assertThat(r.deltas.joinToString("")).isEqualTo(body)
        r.assertNoTagLeak()
        assertThat(r.body).isEqualTo(body)
        assertThat(r.emotion).isEqualTo("경계심, 호기심")
        assertThat(r.speaker).isNull()
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 5, 7, 13, 1000])
    fun `감정과 인물을 한 줄에 같이 써도 둘 다 떼어 낸다`(chunk: Int) {
        val r = run("[감정: 슬픔] [인물: 설월/당황]\n\n$body", chunk)

        assertThat(r.deltas.joinToString("")).isEqualTo(body)
        r.assertNoTagLeak()
        assertThat(r.body).isEqualTo(body)
        assertThat(r.emotion).isEqualTo("슬픔")
        assertThat(r.speaker).isEqualTo("설월")
        assertThat(r.speakerVariant).isEqualTo("당황")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 4, 1000])
    fun `인물 태그만 있어도 떼어 낸다`(chunk: Int) {
        val r = run("[인물: 설월]\n$body", chunk)

        assertThat(r.deltas.joinToString("")).isEqualTo(body)
        r.assertNoTagLeak()
        assertThat(r.emotion).isNull()
        assertThat(r.speaker).isEqualTo("설월")
        assertThat(r.speakerVariant).isNull()
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 6, 1000])
    fun `태그를 줄을 나눠 써도 두 줄까지 떼어 낸다`(chunk: Int) {
        val r = run("[인물: 설월 / 분노]\n[감정: 분노]\n\n$body", chunk)

        assertThat(r.deltas.joinToString("")).isEqualTo(body)
        r.assertNoTagLeak()
        assertThat(r.emotion).isEqualTo("분노")
        assertThat(r.speaker).isEqualTo("설월")
        assertThat(r.speakerVariant).isEqualTo("분노")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 4, 1000])
    fun `태그가 없으면 원문 그대로 통과한다`(chunk: Int) {
        val text = "*비가 내렸다.*\n\"왔구나.\""
        val r = run(text, chunk)

        assertThat(r.deltas.joinToString("")).isEqualTo(text)
        assertThat(r.body).isEqualTo(text)
        assertThat(r.emotion).isNull()
        assertThat(r.speaker).isNull()
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 5, 1000])
    fun `태그 뒤에 본문이 같은 줄에 붙어 있어도 태그만 뗀다`(chunk: Int) {
        val r = run("[감정: 기쁨] [인물: 설월/미소] 그리고 본문이 같은 줄에 있다\n다음 줄", chunk)

        assertThat(r.deltas.joinToString("")).isEqualTo("그리고 본문이 같은 줄에 있다\n다음 줄")
        r.assertNoTagLeak()
        assertThat(r.body).isEqualTo("그리고 본문이 같은 줄에 있다\n다음 줄")
        assertThat(r.emotion).isEqualTo("기쁨")
        assertThat(r.speaker).isEqualTo("설월")
    }

    @Test
    fun `본문 중간의 태그 모양 문장은 건드리지 않는다`() {
        val text = "*문이 열렸다.*\n[인물: 설월]이라고 쓰인 팻말이 있었다."
        val r = run(text, 7)

        assertThat(r.body).isEqualTo(text)
        assertThat(r.speaker).isNull()
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
        val text = "[감정: " + "가".repeat(250) // 태그처럼 시작했지만 닫히지 않는 긴 줄
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
    fun `태그로 시작할 수 없는 응답은 기다리지 않고 바로 흘린다`() {
        val recorder = Recorder()
        val filter = EmotionTagFilter(recorder)
        filter.onDelta("*비가")
        assertThat(recorder.deltas).containsExactly("*비가")
        filter.onDelta("[감정: x]")
        assertThat(recorder.deltas).containsExactly("*비가", "[감정: x]")
    }

    @Test
    fun `태그 앞부분인 동안은 기다리고 CR에서 끊겨도 태그를 흘리지 않는다`() {
        val recorder = Recorder()
        val filter = EmotionTagFilter(recorder)
        listOf("[", " 감", "정 ", ": 슬픔]", "\r", "\n본문").forEach(filter::onDelta)
        filter.onComplete("[ 감정 : 슬픔]\r\n본문")

        assertThat(recorder.deltas).containsExactly("본문")
        assertThat(recorder.emotion).isEqualTo("슬픔")
    }

    @Test
    fun `인물 태그 앞부분인 동안도 기다린다`() {
        val recorder = Recorder()
        val filter = EmotionTagFilter(recorder)
        listOf("[인", "물: 설", "월/당황", "]").forEach(filter::onDelta)
        assertThat(recorder.deltas).isEmpty()
        filter.onDelta("\n본문")
        filter.onComplete("[인물: 설월/당황]\n본문")

        assertThat(recorder.deltas).containsExactly("본문")
        assertThat(recorder.speaker).isEqualTo("설월")
        assertThat(recorder.speakerVariant).isEqualTo("당황")
    }

    @Test
    fun `태그만 있고 본문이 없으면 본문은 빈 문자열이다`() {
        val r = run("[감정: 무표정] [인물: 설월]", 3)

        assertThat(r.deltas).isEmpty()
        assertThat(r.body).isEmpty()
        assertThat(r.emotion).isEqualTo("무표정")
        assertThat(r.speaker).isEqualTo("설월")
    }

    @Test
    fun `스트림이 줄바꿈 전에 끝나도 버퍼를 흘린다`() {
        val r = run("짧은 답", 1)

        assertThat(r.deltas.joinToString("")).isEqualTo("짧은 답")
        assertThat(r.body).isEqualTo("짧은 답")
    }

    @Test
    fun `긴 값은 칼럼 크기로 자른다`() {
        val parsed = EmotionTagFilter.parse("[감정: ${"가".repeat(150)}] [인물: ${"나".repeat(120)}/${"다".repeat(70)}]\n본문")

        assertThat(parsed.tags.emotion).hasSize(ResponseTags.MAX_EMOTION)
        assertThat(parsed.tags.speaker).hasSize(ResponseTags.MAX_SPEAKER)
        assertThat(parsed.tags.speakerVariant).hasSize(ResponseTags.MAX_VARIANT)
        assertThat(parsed.body).isEqualTo("본문")
    }

    @Test
    fun `빈 태그 값은 null이다`() {
        val parsed = EmotionTagFilter.parse("[감정: ] [인물:  ]\n본문")

        assertThat(parsed.tags).isEqualTo(ResponseTags.NONE)
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
