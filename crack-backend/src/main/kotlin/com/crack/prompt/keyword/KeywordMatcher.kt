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
 * - 키의 앞뒤 공백을 제거하고, 제거한 뒤 **2글자 미만인 키는 무시**한다(글자 수는 코드 포인트 기준).
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
        if (key.codePointCount(0, key.length) < MIN_KEY_LENGTH) return false
        return haystack.contains(key)
    }

    private fun normalize(s: String): String = s.lowercase(Locale.ROOT)

    companion object {
        /** 이보다 짧은 키는 오탐이 많아 무시한다. */
        const val MIN_KEY_LENGTH = 2
    }
}
