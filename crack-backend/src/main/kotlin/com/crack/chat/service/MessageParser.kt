package com.crack.chat.service

import com.crack.chat.dto.ParsedResponse
import com.crack.chat.dto.ResponseSegment
import com.crack.chat.dto.SegmentType
import org.springframework.stereotype.Component

@Component
class MessageParser {

    companion object {
        private val EMOTION_PATTERN = Regex("""\[감정:\s*(.+?)]""")
        private val ACTION_PATTERN = Regex("""\*(.+?)\*""")
        private val DIALOGUE_PATTERN = Regex(""""(.+?)"""")
    }

    fun parse(rawResponse: String): ParsedResponse {
        val emotions = extractEmotions(rawResponse)
        val segments = parseSegments(rawResponse)
        return ParsedResponse(
            raw = rawResponse,
            emotions = emotions,
            segments = segments
        )
    }

    fun extractEmotions(text: String): List<String> {
        return EMOTION_PATTERN.findAll(text)
            .flatMap { match ->
                match.groupValues[1].split(",").map { it.trim() }
            }
            .filter { it.isNotBlank() }
            .toList()
    }

    fun parseSegments(text: String): List<ResponseSegment> {
        val segments = mutableListOf<ResponseSegment>()
        var remaining = text

        while (remaining.isNotBlank()) {
            remaining = remaining.trimStart()
            if (remaining.isBlank()) break

            when {
                remaining.startsWith("[감정:") -> {
                    val match = EMOTION_PATTERN.find(remaining)
                    if (match != null) {
                        segments.add(ResponseSegment(SegmentType.EMOTION, match.value))
                        remaining = remaining.substring(match.range.last + 1)
                    } else {
                        val (consumed, rest) = consumeUntilNext(remaining)
                        segments.add(ResponseSegment(SegmentType.NARRATION, consumed))
                        remaining = rest
                    }
                }
                remaining.startsWith("*") -> {
                    val match = ACTION_PATTERN.find(remaining)
                    if (match != null && match.range.first == 0) {
                        segments.add(ResponseSegment(SegmentType.ACTION, match.groupValues[1]))
                        remaining = remaining.substring(match.range.last + 1)
                    } else {
                        val (consumed, rest) = consumeUntilNext(remaining)
                        segments.add(ResponseSegment(SegmentType.NARRATION, consumed))
                        remaining = rest
                    }
                }
                remaining.startsWith("\"") || remaining.startsWith("\u201C") -> {
                    val match = DIALOGUE_PATTERN.find(remaining)
                    if (match != null && match.range.first == 0) {
                        segments.add(ResponseSegment(SegmentType.DIALOGUE, match.groupValues[1]))
                        remaining = remaining.substring(match.range.last + 1)
                    } else {
                        val (consumed, rest) = consumeUntilNext(remaining)
                        segments.add(ResponseSegment(SegmentType.NARRATION, consumed))
                        remaining = rest
                    }
                }
                else -> {
                    val (consumed, rest) = consumeUntilNext(remaining)
                    segments.add(ResponseSegment(SegmentType.NARRATION, consumed))
                    remaining = rest
                }
            }
        }

        return segments
    }

    private fun consumeUntilNext(text: String): Pair<String, String> {
        val nextMarkers = listOf(
            text.indexOf("[감정:", 1),
            text.indexOf("*", 1),
            text.indexOf("\"", 1),
            text.indexOf("\n", 0)
        ).filter { it > 0 }

        return if (nextMarkers.isEmpty()) {
            text.trim() to ""
        } else {
            val splitAt = nextMarkers.min()
            text.substring(0, splitAt).trim() to text.substring(splitAt)
        }
    }
}
