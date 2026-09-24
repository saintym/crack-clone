package com.crack.chat.flow

import com.crack.ai.provider.StreamListener
import com.crack.message.dto.ResponseTags

/**
 * 첫 줄 태그를 뗀 응답을 받는 리스너. [EmotionTagFilter]의 출력 쪽이다.
 * 종료 콜백([onComplete] 또는 [onError])은 정확히 한 번 온다(게이트웨이의 SafeStreamListener가 보장).
 */
interface TaggedResponseListener {
    /** 태그가 제거된 본문 조각 */
    fun onDelta(text: String)

    /** @param body 태그를 뗀 본문 전체 @param tags 첫 줄에서 뽑은 감정·인물 값 */
    fun onComplete(body: String, tags: ResponseTags)

    fun onError(error: Throwable)
}

/**
 * 응답 앞쪽의 `[감정: …]`·`[인물: 이름/변형]` 태그를 스트림에서 떼어 낸다 (D19, D31, DESIGN.md §5.3).
 *
 * - 응답 시작부를 버퍼링한다. 그동안은 delta를 흘리지 않는다. 버퍼가 더 이상 태그의 앞부분일 수 없게 되면
 *   바로 판단을 끝내고 흘린다(`[`로 시작하지 않는 응답은 첫 delta에서 바로 통과한다).
 * - 앞에서부터 태그를 **연달아** 소비한다. 한 줄에 둘을 같이 써도(`[감정: 슬픔] [인물: 설월/당황]`),
 *   줄을 나눠 써도 된다(최대 [MAX_TAG_LINES]줄, 태그 [MAX_TAGS]개까지).
 * - 태그 뒤에 본문이 같은 줄에 붙어 있어도 **태그만 떼고** 본문은 남긴다. 태그는 어떤 경우에도 사용자에게 보이지 않는다.
 * - 태그를 하나라도 뗐으면 뒤따르는 공백·빈 줄도 버린다.
 * - 태그가 아니면 버퍼를 원문 그대로 흘리고 이후는 통과시킨다.
 *
 * 흘린 delta를 이어 붙이면 [parse]`(fullText).body`와 같다(fullText가 delta의 합일 때).
 * 저장에는 [onComplete]로 받은 fullText를 [parse]한 결과를 쓴다.
 *
 * 스레드 안전하지 않다. 프로바이더는 한 스레드에서 순서대로 콜백한다.
 */
class EmotionTagFilter(private val delegate: TaggedResponseListener) : StreamListener {

    private enum class State { BUFFERING, SKIP_LEADING_BLANK, PASS }

    private var state = State.BUFFERING
    private val buffer = StringBuilder()

    override fun onDelta(text: String) {
        if (text.isEmpty()) return
        when (state) {
            State.PASS -> delegate.onDelta(text)
            State.SKIP_LEADING_BLANK -> emitSkippingLeadingBlank(text)
            State.BUFFERING -> {
                buffer.append(text)
                decide(final = false)
            }
        }
    }

    override fun onComplete(fullText: String) {
        if (state == State.BUFFERING) decide(final = true)
        val parsed = parse(fullText)
        delegate.onComplete(parsed.body, parsed.tags)
    }

    override fun onError(error: Throwable) {
        delegate.onError(error)
    }

    /** 버퍼로 태그 여부를 판단할 수 있으면 판단하고 상태를 바꾼다. [final]이면 스트림이 끝난 것이다. */
    private fun decide(final: Boolean) {
        val text = buffer.toString()
        val scan = scanTags(text)
        val rest = text.substring(scan.end)
        if (!final && scan.count < MAX_TAGS && couldContinue(rest)) return

        buffer.setLength(0)
        if (scan.count == 0) {
            // 태그가 없다. 원문 그대로 흘린다(앞쪽 공백도 건드리지 않는다).
            state = State.PASS
            if (text.isNotEmpty()) delegate.onDelta(text)
        } else {
            state = State.SKIP_LEADING_BLANK
            if (rest.isNotEmpty()) emitSkippingLeadingBlank(rest)
        }
    }

    private fun emitSkippingLeadingBlank(text: String) {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return
        state = State.PASS
        delegate.onDelta(text.substring(start))
    }

    data class Parsed(val tags: ResponseTags, val body: String)

    private class Scan(val end: Int, val count: Int, val tags: ResponseTags)

    companion object {
        /** 태그 하나의 값 길이 상한. 넘으면 태그로 보지 않는다(닫히지 않은 대괄호로 스트림이 막히지 않게) */
        const val MAX_TAG_LINE = 200

        /** 태그를 찾을 줄 수 상한 */
        const val MAX_TAG_LINES = 2

        /** 소비할 태그 개수 상한 */
        const val MAX_TAGS = 4

        /** 인물 이름과 변형을 나누는 문자: `[인물: 설월/당황]` */
        const val VARIANT_SEPARATOR = '/'

        private const val EMOTION_KEY = "감정"

        /** 태그 하나. 값에는 대괄호를 쓸 수 없고 [MAX_TAG_LINE]자까지다 */
        val TAG = Regex("""\[\s*(감정|인물)\s*:([^\[\]]{0,$MAX_TAG_LINE})\]""")

        /** 아직 닫히지 않은 태그의 앞부분(`[`, `[감`, `[인물`, `[인물: 설월/당`) */
        private val PARTIAL_TAG =
            Regex("""^\[\s*(?:감(?:정\s*(?::[^\[\]]{0,$MAX_TAG_LINE})?)?|인(?:물\s*(?::[^\[\]]{0,$MAX_TAG_LINE})?)?)?$""")

        /** 전체 응답을 태그 값과 본문으로 나눈다. 태그가 없으면 원문 그대로. */
        fun parse(text: String): Parsed {
            val scan = scanTags(text)
            if (scan.count == 0) return Parsed(ResponseTags.NONE, text)
            return Parsed(scan.tags, text.substring(scan.end).trimStart())
        }

        /** 앞에서부터 태그를 연달아 읽는다. [Scan.end]는 마지막 태그 끝(본문 시작 전)이다. */
        private fun scanTags(text: String): Scan {
            var end = 0
            var cursor = 0
            var count = 0
            var newlines = 0
            var emotion: String? = null
            var speaker: String? = null
            var variant: String? = null

            while (count < MAX_TAGS) {
                while (cursor < text.length && text[cursor].isWhitespace()) {
                    if (text[cursor] == '\n') newlines++
                    cursor++
                }
                if (newlines >= MAX_TAG_LINES) break
                val match = TAG.matchAt(text, cursor) ?: break
                val value = match.groupValues[2].trim()
                if (match.groupValues[1] == EMOTION_KEY) {
                    if (emotion == null && value.isNotEmpty()) emotion = value
                } else if (speaker == null && value.isNotEmpty()) {
                    val slash = value.indexOf(VARIANT_SEPARATOR)
                    if (slash < 0) {
                        speaker = value
                    } else {
                        speaker = value.substring(0, slash).trim()
                        variant = value.substring(slash + 1).trim()
                    }
                }
                count++
                cursor = match.range.last + 1
                end = cursor
            }
            return Scan(end, count, ResponseTags.of(emotion, speaker, variant))
        }

        /** 남은 버퍼가 아직 태그의 앞부분일 수 있는지. 아니면 기다리지 않고 바로 흘린다. */
        private fun couldContinue(rest: String): Boolean {
            var newlines = 0
            var i = 0
            while (i < rest.length && rest[i].isWhitespace()) {
                if (rest[i] == '\n' && ++newlines >= MAX_TAG_LINES) return false
                i++
            }
            if (i >= rest.length) return true
            return PARTIAL_TAG.matches(rest.substring(i))
        }
    }
}
