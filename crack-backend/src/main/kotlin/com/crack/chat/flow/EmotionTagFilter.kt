package com.crack.chat.flow

import com.crack.ai.provider.StreamListener

/**
 * 감정 태그를 뗀 응답을 받는 리스너. [EmotionTagFilter]의 출력 쪽이다.
 * 종료 콜백([onComplete] 또는 [onError])은 정확히 한 번 온다(게이트웨이의 SafeStreamListener가 보장).
 */
interface TaggedResponseListener {
    /** 태그가 제거된 본문 조각 */
    fun onDelta(text: String)

    /** @param body 태그를 뗀 본문 전체 @param emotion 감정 값(태그가 없으면 null) */
    fun onComplete(body: String, emotion: String?)

    fun onError(error: Throwable)
}

/**
 * 응답 첫 줄의 `[감정: …]` 태그를 스트림에서 떼어 낸다 (D19, DESIGN.md §5.3).
 *
 * - 응답 시작부를 첫 줄바꿈까지(최대 [MAX_TAG_LINE]자) 버퍼링한다. 그동안은 delta를 흘리지 않는다.
 * - 첫 줄이 [TAG_PATTERN]에 맞으면 그 줄을 버리고, 뒤따르는 공백·빈 줄도 버린다. 감정 값만 기록한다.
 * - 맞지 않으면(줄바꿈 없이 [MAX_TAG_LINE]자를 넘은 경우 포함) 버퍼를 그대로 흘리고 이후는 통과시킨다.
 * - 태그가 여러 delta로 쪼개져 들어와도 버퍼링하므로 delta에 태그 조각이 실리지 않는다.
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
        delegate.onComplete(parsed.body, parsed.emotion)
    }

    override fun onError(error: Throwable) {
        delegate.onError(error)
    }

    /** 버퍼로 태그 여부를 판단할 수 있으면 판단하고 상태를 바꾼다. [final]이면 스트림이 끝난 것이다. */
    private fun decide(final: Boolean) {
        val newline = buffer.indexOf("\n")
        val decidable = final || (newline in 0..MAX_TAG_LINE) || buffer.length > MAX_TAG_LINE
        if (!decidable) return

        val text = buffer.toString()
        buffer.setLength(0)
        val tagEnd = tagLineEnd(text)
        if (tagEnd == null) {
            state = State.PASS
            delegate.onDelta(text)
        } else {
            state = State.SKIP_LEADING_BLANK
            if (tagEnd < text.length) emitSkippingLeadingBlank(text.substring(tagEnd))
        }
    }

    private fun emitSkippingLeadingBlank(text: String) {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return
        state = State.PASS
        delegate.onDelta(text.substring(start))
    }

    data class Parsed(val emotion: String?, val body: String)

    companion object {
        /** 태그 줄로 볼 최대 길이(줄바꿈 제외) */
        const val MAX_TAG_LINE = 200

        /** `emotion` 칼럼 크기(VARCHAR(100)) */
        const val MAX_EMOTION_LENGTH = 100

        val TAG_PATTERN = Regex("""^\[\s*감정\s*:\s*(.+?)\]\s*$""")

        /** 전체 응답을 감정 값과 태그를 뗀 본문으로 나눈다. 태그가 없거나 형식이 다르면 원문 그대로. */
        fun parse(text: String): Parsed {
            val tagEnd = tagLineEnd(text) ?: return Parsed(null, text)
            val line = text.substring(0, tagEnd).trimEnd('\n', '\r')
            val emotion = TAG_PATTERN.find(line)!!.groupValues[1].trim().take(MAX_EMOTION_LENGTH)
            return Parsed(emotion.ifEmpty { null }, text.substring(tagEnd).trimStart())
        }

        /**
         * 첫 줄이 감정 태그면 그 줄 끝(줄바꿈 포함) 다음 위치를, 아니면 null.
         * 줄바꿈이 없으면 텍스트 전체를 첫 줄로 본다(길이 제한 안에서).
         */
        private fun tagLineEnd(text: String): Int? {
            val newline = text.indexOf('\n')
            val lineEnd = if (newline >= 0) newline else text.length
            if (lineEnd > MAX_TAG_LINE) return null
            val line = text.substring(0, lineEnd).trimEnd('\r')
            if (!TAG_PATTERN.matches(line)) return null
            return if (newline >= 0) newline + 1 else text.length
        }
    }
}
