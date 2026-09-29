package com.crack.story.clock

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime

/** 이야기 속 시계의 순수 계산 (T38, DESIGN.md §5.4, D39) */
class StoryClockTest {

    private val base = LocalDateTime.of(2026, 9, 28, 23, 40)

    // ---- 읽기와 쓰기 ----

    @ParameterizedTest
    @ValueSource(
        strings = [
            "2026-09-28 23:40",
            "2026-09-28T23:40",
            "2026-09-28 23:40:00",
            "2026-09-28T23:40:59",
            " 2026-09-28 23:40 ",
            "2026-9-28 23:40",
        ]
    )
    fun `여러 형태의 시각 문자열을 같은 값으로 읽는다`(raw: String) {
        assertThat(StoryClock.parse(raw)).isEqualTo(base)
    }

    @Test
    fun `날짜만 적힌 값은 그날 0시로 본다`() {
        assertThat(StoryClock.parse("2026-09-28")).isEqualTo(LocalDateTime.of(2026, 9, 28, 0, 0))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "그날 늦은 밤", "홍무 15년 8월 13일", "2026-13-40 99:99", "23:40"])
    fun `알아볼 수 없는 값은 null이다`(raw: String) {
        assertThat(StoryClock.parse(raw)).isNull()
        assertThat(StoryClock.parseTag(raw)).isNull()
    }

    @Test
    fun `값이 없으면 null이다`() {
        assertThat(StoryClock.parse(null)).isNull()
        assertThat(StoryClock.parseTag(null)).isNull()
    }

    @Test
    fun `프롬프트 형식과 state_json 형식이 정해져 있다`() {
        assertThat(StoryClock.format(base)).isEqualTo("2026-09-28 23:40")
        assertThat(StoryClock.store(base)).isEqualTo("2026-09-28T23:40")
        // state.json에 쓴 값은 다시 읽을 수 있어야 한다
        assertThat(StoryClock.parse(StoryClock.store(base))).isEqualTo(base)
    }

    @Test
    fun `장소는 공백을 떼고 칼럼 크기로 자른다`() {
        assertThat(StoryClock.place("  에미야 저택 ")).isEqualTo("에미야 저택")
        assertThat(StoryClock.place("   ")).isNull()
        assertThat(StoryClock.place(null)).isNull()
        assertThat(StoryClock.place("가".repeat(300))).hasSize(StoryClock.MAX_PLACE)
    }

    // ---- 상대 이동 (`/시간 +3일`) ----

    @ParameterizedTest
    @CsvSource(
        "+30분,2026-09-29T00:10",
        "+2시간,2026-09-29T01:40",
        "+3일,2026-10-01T23:40",
        "3일,2026-10-01T23:40",
        "+1주,2026-10-05T23:40",
        "+1개월,2026-10-28T23:40",
        "+1년,2027-09-28T23:40",
        "-1일,2026-09-27T23:40",
    )
    fun `상대 이동을 적용한다`(spec: String, expected: String) {
        assertThat(StoryClock.applyOffset(base, spec)).isEqualTo(LocalDateTime.parse(expected))
    }

    @Test
    fun `여러 단위를 이어 적어도 적용한다`() {
        assertThat(StoryClock.applyOffset(base, "+1일 2시간 20분"))
            .isEqualTo(LocalDateTime.of(2026, 9, 30, 2, 0))
    }

    @ParameterizedTest
    @ValueSource(strings = ["내일 아침", "", "곧", "+"])
    fun `알아볼 수 없는 상대 이동은 null이다`(spec: String) {
        assertThat(StoryClock.applyOffset(base, spec)).isNull()
    }

    @Test
    fun `명령 인자는 절대 시각이 먼저고 없으면 상대 이동이다`() {
        assertThat(StoryClock.resolve(base, "2026-10-05 08:00")).isEqualTo(LocalDateTime.of(2026, 10, 5, 8, 0))
        assertThat(StoryClock.resolve(base, "+3일")).isEqualTo(LocalDateTime.of(2026, 10, 1, 23, 40))
        // 시계가 아직 없으면 상대 이동을 쓸 수 없다
        assertThat(StoryClock.resolve(null, "+3일")).isNull()
        assertThat(StoryClock.resolve(null, "2026-10-05 08:00")).isEqualTo(LocalDateTime.of(2026, 10, 5, 8, 0))
    }

    // ---- 단조 비감소 (D39) ----

    @Test
    fun `앞으로 가는 시각은 그대로 받는다`() {
        val next = LocalDateTime.of(2026, 9, 29, 8, 0)
        val advance = StoryClock.advance(ClockPoint(base, "에미야 저택"), next, "후유키 시내")

        assertThat(advance.point).isEqualTo(ClockPoint(next, "후유키 시내"))
        assertThat(advance.rewound).isFalse()
    }

    @Test
    fun `같은 시각은 뒤로 간 것이 아니다`() {
        val advance = StoryClock.advance(ClockPoint(base, "저택"), base, null)

        assertThat(advance.point).isEqualTo(ClockPoint(base, "저택"))
        assertThat(advance.rewound).isFalse()
    }

    @Test
    fun `직전보다 이른 시각은 버리고 직전 값을 쓴다`() {
        val earlier = base.minusHours(5)
        val advance = StoryClock.advance(ClockPoint(base, "저택"), earlier, "골목")

        assertThat(advance.point).isEqualTo(ClockPoint(base, "골목"))
        assertThat(advance.rewound).isTrue()
    }

    @Test
    fun `값이 없는 턴은 직전 시각과 장소를 잇는다`() {
        val advance = StoryClock.advance(ClockPoint(base, "저택"), null, null)

        assertThat(advance.point).isEqualTo(ClockPoint(base, "저택"))
        assertThat(advance.rewound).isFalse()
    }

    @Test
    fun `장소만 바뀌는 턴도 있다`() {
        val advance = StoryClock.advance(ClockPoint(base, "저택"), null, "정원")

        assertThat(advance.point).isEqualTo(ClockPoint(base, "정원"))
    }

    @Test
    fun `상한은 두지 않는다 — 한 달 뒤도 받는다`() {
        val far = base.plusMonths(1)

        assertThat(StoryClock.advance(ClockPoint(base), far, null).point).isEqualTo(ClockPoint(far))
    }
}
