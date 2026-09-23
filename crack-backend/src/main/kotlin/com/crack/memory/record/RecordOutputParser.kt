package com.crack.memory.record

import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.ProtagonistDoc
import com.crack.memory.docs.StoryState

/** 관리자 출력이 §7.3 형식에 맞지 않을 때. 파이프라인은 이 기록 시도를 실패로 본다. */
class RecordFormatException(message: String) : RuntimeException(message)

/** ① 시나리오 관리자 결과. [chronicle]은 새 턴이 없는 기록이면 null일 수 있다. */
data class ScenarioOutput(
    val chronicle: String?,
    /** `updatedAtTurn`은 비어 있다. 파이프라인이 채운다. */
    val state: StoryState,
    val involved: List<String>,
    /** 회차 번호 → 고친 회차 본문 전체 */
    val revised: Map<Int, String> = emptyMap(),
)

/** ② 캐릭터 관리자 결과. [protagonistChanges]는 없으면 null. */
data class CharacterOutput(val memory: String, val protagonistChanges: String?)

/**
 * 관리자 출력(DESIGN.md §7.3 XML 태그 블록) 파서. 순수 함수 모음이다.
 *
 * - 태그 밖의 글은 무시한다. 같은 태그가 여러 번 나오면 첫 번째를 쓴다(`<revised>`만 전부).
 * - 본문을 코드 펜스(```)로 감쌌으면 벗긴다.
 * - 필수 태그가 없거나 비어 있으면 [RecordFormatException].
 * - "비어 있어도 되는" 태그는 빈 값이나 `없음`을 없음으로 본다.
 */
object RecordOutputParser {

    private val NONE_WORDS = setOf("없음", "없음.", "(없음)", "-", "none", "n/a")
    private val NAME_SEPARATORS = Regex("""\s*[,，、\n]\s*""")
    private val REVISED = Regex("""<revised\s+entry\s*=\s*["']?(\d+)["']?\s*>([\s\S]*?)</revised>""", RegexOption.IGNORE_CASE)

    /** 회차 제목 줄(`## 회차 2 (턴 4–6)`). 제목은 시스템이 붙이므로 본문에 붙어 오면 뗀다(BUG-012). */
    private val ENTRY_HEADING = Regex("""^회차\s*\d+""")

    fun parseScenario(text: String, hasNewTurns: Boolean): ScenarioOutput {
        val chronicleRaw = requireTag(text, "chronicle", allowEmpty = !hasNewTurns)
        val chronicle = chronicleRaw.takeUnless { isNone(it) }?.let { stripEntryHeading(it) }?.takeUnless { isNone(it) }
        if (hasNewTurns && chronicle == null) throw RecordFormatException("<chronicle>이 비어 있습니다")
        val state = parseState(requireTag(text, "state"))
        val involved = parseNames(requireTag(text, "involved", allowEmpty = true))
        val revised = REVISED.findAll(text).mapNotNull { m ->
            val body = stripEntryHeading(unfence(m.groupValues[2]).trim())
            if (isNone(body)) null else m.groupValues[1].toInt() to body
        }.toMap()
        return ScenarioOutput(chronicle, state, involved, revised)
    }

    fun parseCharacter(text: String): CharacterOutput {
        val memory = stripHeading(requireTag(text, "memory"), CharacterDoc.MEMORY)
        if (memory.isBlank() || isNone(memory)) throw RecordFormatException("<memory>가 비어 있습니다")
        val changes = requireTag(text, "protagonist_changes", allowEmpty = true).takeUnless { isNone(it) }
        return CharacterOutput(memory, changes)
    }

    fun parseProtagonist(text: String): String {
        val changes = stripHeading(requireTag(text, "changes"), ProtagonistDoc.CHANGES)
        if (changes.isBlank() || isNone(changes)) throw RecordFormatException("<changes>가 비어 있습니다")
        return changes
    }

    /** 압축 결과. [heading]을 주면 맨 앞의 그 제목 줄을 뗀다. */
    fun parseCompressed(text: String, heading: String? = null): String {
        var body = requireTag(text, "compressed")
        if (heading != null) body = stripHeading(body, heading)
        if (body.isBlank() || isNone(body)) throw RecordFormatException("<compressed>가 비어 있습니다")
        return body
    }

    /** `<state>` JSON. 앞뒤 군더더기가 있어도 첫 `{`부터 마지막 `}`까지를 읽는다. */
    fun parseState(raw: String): StoryState {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) throw RecordFormatException("<state>에 JSON 객체가 없습니다")
        return try {
            StoryState.fromJson(raw.substring(start, end + 1)).let { s ->
                s.copy(
                    companions = s.companions.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                    location = s.location?.trim()?.takeIf { it.isNotEmpty() },
                    time = s.time?.trim()?.takeIf { it.isNotEmpty() },
                    updatedAtTurn = null,
                )
            }
        } catch (e: Exception) {
            throw RecordFormatException("<state> JSON을 읽을 수 없습니다: ${e.message}")
        }
    }

    fun parseNames(raw: String): List<String> {
        if (isNone(raw)) return emptyList()
        return raw.split(NAME_SEPARATORS)
            .map { it.trim().removePrefix("-").trim().trim('*', '`', '"', '\'') }
            .filter { it.isNotEmpty() && !isNone(it) }
            .distinct()
    }

    /**
     * 태그 본문(앞뒤 공백 제거, 코드 펜스 제거). 태그가 없으면 실패.
     * [allowEmpty]가 false면 빈 본문도 실패.
     */
    fun requireTag(text: String, name: String, allowEmpty: Boolean = false): String {
        val body = extractTag(text, name) ?: throw RecordFormatException("<$name> 태그가 없습니다")
        if (!allowEmpty && body.isBlank()) throw RecordFormatException("<$name>이 비어 있습니다")
        return body
    }

    /** 첫 `<name>…</name>`의 본문. 없으면 null. 속성이 붙은 여는 태그(`<name a="b">`)도 받는다. */
    fun extractTag(text: String, name: String): String? {
        val regex = Regex("""<${Regex.escape(name)}(?:\s[^>]*)?>([\s\S]*?)</${Regex.escape(name)}\s*>""", RegexOption.IGNORE_CASE)
        val m = regex.find(text) ?: return null
        return unfence(m.groupValues[1]).trim()
    }

    fun isNone(value: String): Boolean = value.isBlank() || value.trim().lowercase() in NONE_WORDS

    /** 본문 전체가 코드 펜스 하나로 감싸여 있으면 벗긴다. */
    fun unfence(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("```") && !trimmed.startsWith("~~~")) return body
        val fence = trimmed.substring(0, 3)
        val firstNl = trimmed.indexOf('\n')
        if (firstNl < 0 || !trimmed.endsWith(fence)) return body
        return trimmed.substring(firstNl + 1, trimmed.length - 3)
    }

    /**
     * 연대기 회차 본문 맨 앞에 붙어 온 `## 회차 N (턴 a–b)` 제목 줄(수준 무관)을 뗀다.
     *
     * 회차 제목은 시스템이 붙인다(DESIGN.md §7.1). 모델이 제목까지 써 보내면 제목이 두 줄로 겹쳐
     * 같은 번호의 회차가 둘이 되고, 다음 재반영이 빈 회차를 고치게 된다(BUG-012).
     */
    fun stripEntryHeading(body: String): String {
        val lines = body.trim().lines()
        val first = lines.firstOrNull() ?: return body.trim()
        val m = Regex("""^#{1,6}\s+(.*?)\s*$""").find(first) ?: return body.trim()
        return if (ENTRY_HEADING.containsMatchIn(m.groupValues[1].trim())) {
            lines.drop(1).joinToString("\n").trim()
        } else {
            body.trim()
        }
    }

    /** 본문 맨 앞의 `## {title}` 제목 줄(수준 무관)을 뗀다. */
    fun stripHeading(body: String, title: String): String {
        val lines = body.trim().lines()
        val first = lines.firstOrNull() ?: return body.trim()
        val m = Regex("""^#{1,6}\s+(.*?)\s*$""").find(first)
        return if (m != null && m.groupValues[1].trim() == title) lines.drop(1).joinToString("\n").trim() else body.trim()
    }
}
