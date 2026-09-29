package com.crack.prompt.keyword

import org.springframework.stereotype.Component
import java.util.Locale

/**
 * 매칭 대상 하나. [id]는 매칭 결과로 돌려줄 식별자(인물 이름, 키워드북 항목 제목 등)이고,
 * [keys] 중 하나라도 텍스트에 나타나면 매칭된다.
 */
data class KeywordEntry(val id: String, val keys: List<String>)

/**
 * 인물 문서 선택(T13)과 키워드북(T17)이 함께 쓰는 키워드 매칭 엔진 (DESIGN.md §8.4).
 *
 * 규칙:
 * - **부분 문자열 매칭**을 쓴다. 한국어 조사 때문이다("설월이", "설월에게"도 "설월"에 매칭).
 * - 대소문자를 구분하지 않는다(`Locale.ROOT` 소문자 기준).
 * - 키의 앞뒤 공백을 제거한다. 남은 것이 없으면 무시한다.
 * - **1글자 키는 낱말 처음에서만 맞는다**(T50/D48). 바로 앞 글자가 글자나 숫자면 낱말 안쪽이라고
 *   보고 버린다("그린"의 "린"). 앞이 문장 처음·공백·문장부호면 맞는 것으로 본다("린이", "토오사카 린은", `"린."`).
 *   **뒤는 보지 않는다** — 한국어는 조사가 붙어서("린이", "렌은") 뒤를 막으면 대부분 놓친다.
 *   그래서 그 글자로 시작하는 낱말("렌즈", "린스")은 여전히 걸린다. 늘 장면에 있는 인물은
 *   `settings.json`의 `alwaysActive`로 넣는 것이 확실하다(DESIGN.md §6.2).
 * - **2글자 이상 키의 동작은 예전 그대로다**(단순 `contains`).
 * - 글자 수는 코드 포인트 기준이다(이모지 하나는 1글자).
 * - 결과는 **입력 순서(=우선순위)를 유지**하고, 같은 id는 처음 한 번만 넣는다.
 * - [limit]이 있으면 그만큼만 돌려준다. 0 이하면 빈 목록이다.
 *
 * 상태가 없는 순수 로직이라 스레드 안전하다. 성능 측정값은 `KeywordMatcherTest` 주석에 있다.
 */
@Component
class KeywordMatcher {

    /** [text]에서 [entries]의 키를 찾아 매칭된 id를 입력 순서대로 돌려준다. */
    fun match(entries: List<KeywordEntry>, text: String, limit: Int? = null): List<String> {
        if (limit != null && limit <= 0) return emptyList()
        if (entries.isEmpty() || text.isEmpty()) return emptyList()

        val haystack = normalize(text)
        val result = LinkedHashSet<String>()
        for (entry in entries) {
            if (entry.id in result) continue
            if (entry.keys.any { key -> matches(haystack, key) }) {
                result += entry.id
                if (limit != null && result.size >= limit) break
            }
        }
        return result.toList()
    }

    private fun matches(haystack: String, rawKey: String): Boolean {
        val key = normalize(rawKey.trim())
        if (key.isEmpty()) return false
        if (key.codePointCount(0, key.length) > SHORT_KEY_LENGTH) return haystack.contains(key)
        return containsAtWordStart(haystack, key)
    }

    /** [key]가 낱말 처음(앞 글자가 글자도 숫자도 아닌 자리)에 나타나는지. */
    private fun containsAtWordStart(haystack: String, key: String): Boolean {
        var from = 0
        while (from <= haystack.length - key.length) {
            val at = haystack.indexOf(key, from)
            if (at < 0) return false
            if (at == 0 || !Character.isLetterOrDigit(haystack.codePointBefore(at))) return true
            from = at + 1
        }
        return false
    }

    private fun normalize(s: String): String = s.lowercase(Locale.ROOT)

    companion object {
        /**
         * 이 글자 수 이하인 키는 낱말 처음에서만 맞는 것으로 본다(코드 포인트 기준).
         * 예전에는 아예 버렸는데, 그러면 이름이 한 글자인 인물이 프롬프트에서 통째로 빠졌다(T50/D48).
         */
        const val SHORT_KEY_LENGTH = 1
    }
}
