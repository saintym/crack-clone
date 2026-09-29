package com.crack.prompt.keyword

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeywordMatcherTest {

    private val matcher = KeywordMatcher()

    @Test
    fun `조사가 붙은 이름도 부분 문자열로 매칭한다`() {
        val entries = listOf(
            KeywordEntry("설월", listOf("설월")),
            KeywordEntry("천마", listOf("천마")),
            KeywordEntry("백연", listOf("백연")),
        )
        val text = "설월이 고개를 들었다. 천마에게 보고해야 한다."
        assertEquals(listOf("설월", "천마"), matcher.match(entries, text))
    }

    @Test
    fun `대소문자를 무시하고 키의 앞뒤 공백을 제거한다`() {
        val entries = listOf(
            KeywordEntry("npc", listOf("  Alice  ")),
            KeywordEntry("org", listOf("MURIM league")),
        )
        assertEquals(listOf("npc", "org"), matcher.match(entries, "alice joined the Murim League."))
    }

    @Test
    fun `빈 키는 무시한다`() {
        val entries = listOf(
            KeywordEntry("빈키", listOf("", "   ")),
            KeywordEntry("정상", listOf("설월")),
        )
        assertEquals(listOf("정상"), matcher.match(entries, "설월과 달"))
    }

    @Test
    fun `1글자 키는 낱말 안쪽에서 맞지 않는다`() {
        val entries = listOf(KeywordEntry("린", listOf("린")))
        assertEquals(emptyList<String>(), matcher.match(entries, "그린 하늘"))
        assertEquals(emptyList<String>(), matcher.match(entries, "바이올린 소리가 들렸다"))
        assertEquals(emptyList<String>(), matcher.match(entries, "훈련은 계속됐다")) // "련"은 다른 음절이라 애초에 안 걸린다
        // 앞 글자가 영문이나 숫자여도 낱말 안쪽으로 본다.
        assertEquals(emptyList<String>(), matcher.match(listOf(KeywordEntry("a", listOf("a"))), "bar에서 만났다"))
    }

    @Test
    fun `1글자 키는 낱말 처음이면 맞는다`() {
        val entries = listOf(KeywordEntry("린", listOf("린")))
        assertEquals(listOf("린"), matcher.match(entries, "린이 말했다"))          // 문장 처음
        assertEquals(listOf("린"), matcher.match(entries, "토오사카 린은 웃었다")) // 앞이 공백
        assertEquals(listOf("린"), matcher.match(entries, "\"린.\" 하고 불렀다"))  // 앞이 따옴표
        assertEquals(listOf("린"), matcher.match(entries, "그린 하늘 아래 린이 섰다")) // 앞쪽 오탐 뒤에도 찾는다
    }

    @Test
    fun `1글자 판정은 코드 포인트 기준이다`() {
        // 이모지 하나는 UTF-16으로 2 char지만 1글자라 낱말 경계 규칙을 받는다.
        val emoji = listOf(KeywordEntry("emoji", listOf("😀")))
        assertEquals(listOf("emoji"), matcher.match(emoji, "웃음 😀"))
        assertEquals(emptyList<String>(), matcher.match(emoji, "웃음😀")) // 앞이 한글 음절
        // 2글자면 예전처럼 단순 contains다.
        assertEquals(listOf("emoji2"), matcher.match(listOf(KeywordEntry("emoji2", listOf("😀😀"))), "웃음😀😀"))
    }

    @Test
    fun `2글자 이상 키는 낱말 안쪽이어도 맞는다`() {
        // 회귀 방지: 1글자 규칙이 긴 키에 새지 않는다.
        val entries = listOf(KeywordEntry("설월", listOf("설월")), KeywordEntry("천마", listOf("천마")))
        assertEquals(listOf("설월", "천마"), matcher.match(entries, "가설월광천마신교"))
    }

    @Test
    fun `결과는 입력 순서(우선순위)를 유지하고 limit만큼 자른다`() {
        val entries = listOf(
            KeywordEntry("C", listOf("셋째")),
            KeywordEntry("A", listOf("첫째")),
            KeywordEntry("X", listOf("없음없음")),
            KeywordEntry("B", listOf("둘째")),
        )
        // 텍스트 등장 순서와 무관하게 입력 순서를 따른다.
        val text = "첫째, 둘째, 셋째가 모였다."
        assertEquals(listOf("C", "A", "B"), matcher.match(entries, text))
        assertEquals(listOf("C", "A"), matcher.match(entries, text, limit = 2))
        assertEquals(listOf("C", "A", "B"), matcher.match(entries, text, limit = 10))
        assertEquals(emptyList<String>(), matcher.match(entries, text, limit = 0))
        assertEquals(emptyList<String>(), matcher.match(entries, text, limit = -1))
    }

    @Test
    fun `같은 id는 한 번만 넣고 limit 계산에도 한 번만 센다`() {
        val entries = listOf(
            KeywordEntry("설월", listOf("설월", "설 소저")),
            KeywordEntry("설월", listOf("설월")),
            KeywordEntry("천마", listOf("천마")),
            KeywordEntry("설월", listOf("설 소저")),
        )
        val text = "설월, 설 소저, 천마"
        assertEquals(listOf("설월", "천마"), matcher.match(entries, text))
        assertEquals(listOf("설월", "천마"), matcher.match(entries, text, limit = 2))
    }

    @Test
    fun `뒤쪽 중복 id가 앞쪽 id의 우선순위를 바꾸지 않는다`() {
        // 앞의 "설월"은 매칭되지 않고 뒤의 "설월"이 매칭되면, 그 위치(뒤쪽)에서 들어간다.
        val entries = listOf(
            KeywordEntry("설월", listOf("없는키워드")),
            KeywordEntry("천마", listOf("천마")),
            KeywordEntry("설월", listOf("설월")),
        )
        assertEquals(listOf("천마", "설월"), matcher.match(entries, "천마와 설월"))
    }

    @Test
    fun `빈 입력은 빈 결과다`() {
        assertEquals(emptyList<String>(), matcher.match(emptyList(), "설월"))
        assertEquals(emptyList<String>(), matcher.match(listOf(KeywordEntry("a", listOf("설월"))), ""))
        assertEquals(emptyList<String>(), matcher.match(listOf(KeywordEntry("a", emptyList())), "설월"))
    }

    /**
     * 성능 확인: 엔트리 500개(키 3개씩, 매칭 대상 절반) × 텍스트 약 3만 자.
     *
     * 측정값(2026-09-23, Apple Silicon 개발 머신, JDK 17, 워밍업 뒤 20회 평균):
     * 단순 `contains` 반복으로 1회 약 16ms. 매 턴 한두 번 부르는 용도라 충분하므로
     * Aho-Corasick 같은 별도 알고리즘은 쓰지 않는다.
     * 아래 단언은 CI 편차를 고려해 넉넉하게(평균 200ms 미만) 잡았다.
     */
    @Test
    fun `엔트리 수백 개와 수만 자 텍스트에서도 빠르다`() {
        val entries = (0 until 500).map { i ->
            KeywordEntry("e$i", listOf("인물$i", "별칭${i}호", "Alias$i"))
        }
        val sb = StringBuilder()
        var n = 0
        while (sb.length < 30_000) {
            sb.append("평범한 서술 문장이 이어진다. 바람이 불고 달이 뜬다. ")
            if (n % 2 == 0 && n < 500) sb.append("alias${n}가 말했다. ")
            n++
        }
        val text = sb.toString()

        repeat(5) { matcher.match(entries, text) } // 워밍업
        val runs = 20
        val start = System.nanoTime()
        var result = emptyList<String>()
        repeat(runs) { result = matcher.match(entries, text) }
        val avgMs = (System.nanoTime() - start) / runs / 1_000_000.0
        println("KeywordMatcher 평균 ${"%.2f".format(avgMs)}ms (entries=${entries.size}, text=${text.length}자)")

        assertTrue(result.isNotEmpty())
        assertTrue(avgMs < 200.0, "평균 ${avgMs}ms")
    }
}
