package com.crack.migration.legacy

import com.crack.message.entity.MessageRole

/** 옛 대화 파일에서 읽은 메시지 하나. [content]는 앞뒤 공백을 뺀 원문이다(감정 태그 포함). */
data class LegacyMessage(val role: MessageRole, val content: String)

/**
 * 옛 파일 기반 대화(`chat/chat_latest.md`, `chat/archive/turn_*.md`) 파서.
 *
 * 형식: 파일 머리(`# 최근 대화`) 뒤에 `## USER` / `## ASSISTANT` 줄이 메시지 시작을 알리고,
 * 다음 머리 줄 전까지가 본문이다. 옛 `ChatFileService`는 T12에서 삭제되므로 이 패키지 안에 따로 구현한다.
 *
 * 옛 `ChatFileService.parseMessages`는 `split`에 캡처 그룹을 쓴 탓에 역할 이름이 결과에서 빠져
 * 역할과 본문이 한 칸씩 밀렸다. 여기서는 줄 단위로 읽어 머리 줄에서 역할을 직접 가져온다.
 */
object LegacyChatParser {

    private val HEADER = Regex("""^##\s+(USER|ASSISTANT)\s*$""")

    /** 첫 줄이 감정 태그인지 본다 (DESIGN.md §5.3과 같은 형식). */
    private val EMOTION_LINE = Regex("""^\[\s*감정\s*:\s*(.+?)]\s*$""")

    private const val EMOTION_MAX = 100

    /** 빈 본문도 결과에 넣는다(호출하는 쪽이 세고 건너뛴다). 머리 줄 앞의 내용은 버린다. */
    fun parse(text: String): List<LegacyMessage> {
        val result = mutableListOf<LegacyMessage>()
        var role: MessageRole? = null
        val body = StringBuilder()

        fun flush() {
            val r = role ?: return
            result += LegacyMessage(r, body.toString().trim())
            body.setLength(0)
        }

        for (raw in text.lineSequence()) {
            val line = raw.removeSuffix("\r")
            val m = HEADER.matchEntire(line)
            if (m != null) {
                flush()
                role = MessageRole.valueOf(m.groupValues[1])
                continue
            }
            if (role != null) body.append(line).append('\n')
        }
        flush()
        return result
    }

    /** 본문과 감정 값. 감정 태그가 없으면 [emotion]은 null이고 본문은 그대로다. */
    data class Split(val content: String, val emotion: String?)

    /** 응답 첫 줄의 `[감정: …]` 태그를 떼어 낸다 (DESIGN.md §5.3: content에는 태그를 뺀 본문, emotion 칼럼에 값). */
    fun splitEmotion(content: String): Split {
        val trimmed = content.trimStart()
        val firstLineEnd = trimmed.indexOf('\n').let { if (it < 0) trimmed.length else it }
        val firstLine = trimmed.substring(0, firstLineEnd).removeSuffix("\r")
        val m = EMOTION_LINE.matchEntire(firstLine) ?: return Split(content, null)
        val emotion = m.groupValues[1].trim().take(EMOTION_MAX).ifEmpty { null }
        return Split(trimmed.substring(firstLineEnd).trim(), emotion)
    }
}
