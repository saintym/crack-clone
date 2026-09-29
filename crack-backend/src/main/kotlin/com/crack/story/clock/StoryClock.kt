package com.crack.story.clock

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 이야기 속 한 시점 (T38, D39).
 *
 * @property time 이야기 속 시각. 현실 달력(ISO)으로만 다룬다
 * @property place 이야기 속 장소. 자유 문자열이고 없을 수 있다
 */
data class ClockPoint(val time: LocalDateTime, val place: String? = null)

/**
 * 시각 태그를 받아들인 결과.
 *
 * @property point 이번 턴에 쓸 시점
 * @property rewound 태그가 직전보다 이른 시각이어서 버렸는지 (경고 로그용)
 */
data class ClockAdvance(val point: ClockPoint, val rewound: Boolean = false)

/**
 * 이야기 속 시계의 순수 계산 (T38, DESIGN.md §5.4, D39).
 *
 * 파일도 DB도 모르는 순수 함수만 둔다. 파일 입출력은 [StoryClockFiles],
 * 스토리 단위 흐름은 [StoryClockService]가 맡는다.
 *
 * **내부 표현은 `YYYY-MM-DD HH:mm`이다.** 경과분이 아니라 **장면이 도달한 절대 시각**을 받는다(D39).
 * 서버는 **단조 비감소만** 강제한다. 상한은 두지 않는다 — "한 달 후"를 막아 버리기 때문이다.
 */
object StoryClock {

    private val log = LoggerFactory.getLogger(StoryClock::class.java)

    /** 프롬프트와 태그에 쓰는 형식. 표시 형식은 프론트(T51)가 정한다 */
    const val PATTERN = "yyyy-MM-dd HH:mm"

    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(PATTERN)

    /** `2026-09-28 23:40`, `2026-09-28T23:40`, 초까지 붙은 값, 날짜만 있는 값을 받는다 */
    private val ACCEPTED: List<DateTimeFormatter> = listOf(
        FORMATTER,
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d"),
    )

    /** `+3일`, `-2시간`, `+1주 6시간`처럼 상대 이동을 적은 값 (`/시간` 명령) */
    private val OFFSET = Regex("""([+-])?\s*(\d{1,6})\s*(분|시간|시|일|주|개월|달|년|해)""")

    /** 값의 앞뒤 공백을 떼고 [max]자로 자른다. 비면 null */
    fun place(raw: String?, max: Int = MAX_PLACE): String? = raw?.trim()?.take(max)?.ifEmpty { null }

    /** `장소` 칼럼 크기 */
    const val MAX_PLACE = 200

    fun format(time: LocalDateTime): String = FORMATTER.format(time)

    /** `state.json`에 적는 형식(`2026-09-28T23:40`). 초가 0이면 생략된다 */
    fun store(time: LocalDateTime): String = time.withSecond(0).withNano(0).toString()

    /** 알아볼 수 있는 시각 문자열이면 [LocalDateTime], 아니면 null. 초는 버린다 */
    fun parse(raw: String?): LocalDateTime? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (formatter in ACCEPTED) {
            try {
                return LocalDateTime.parse(text, formatter).withSecond(0).withNano(0)
            } catch (_: Exception) {
                // 다음 형식으로 넘어간다
            }
        }
        // 날짜만 적힌 값은 그날 0시로 본다. 뒤로 가는 값이면 단조 검사가 막는다
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 응답 첫 줄 `[시간: …]` 값. 알아볼 수 없으면 경고만 남기고 null을 돌려준다.
     * **턴을 실패시키지 않는다** — 값이 없으면 직전 시각을 잇는다.
     */
    fun parseTag(raw: String?): LocalDateTime? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = parse(text)
        if (parsed == null) log.warn("이야기 속 시각 태그를 알아볼 수 없어 직전 값을 잇는다: [시간: {}]", text)
        return parsed
    }

    /**
     * [from]에서 상대 이동([spec], 예 `+3일`, `-2시간`, `+1주 6시간`)한 시각. 알아볼 수 없으면 null.
     * 부호를 적지 않으면 앞으로 간다.
     */
    fun applyOffset(from: LocalDateTime, spec: String): LocalDateTime? {
        var result = from
        var applied = false
        var sign = if (spec.trim().startsWith("-")) -1L else 1L
        for (match in OFFSET.findAll(spec)) {
            match.groupValues[1].takeIf { it.isNotEmpty() }?.let { sign = if (it == "-") -1L else 1L }
            val amount = match.groupValues[2].toLongOrNull() ?: continue
            val value = sign * amount
            result = when (match.groupValues[3]) {
                "분" -> result.plusMinutes(value)
                "시간", "시" -> result.plusHours(value)
                "일" -> result.plusDays(value)
                "주" -> result.plusWeeks(value)
                "개월", "달" -> result.plusMonths(value)
                else -> result.plusYears(value)
            }
            applied = true
        }
        return if (applied) result else null
    }

    /**
     * `/시간` 명령의 인자를 시각으로 바꾼다 (DESIGN.md §8.2).
     * 절대 시각(`2026-10-05 08:00`)이 먼저고, 아니면 [current] 기준 상대 이동(`+3일`)으로 본다.
     * 둘 다 아니거나 상대 이동인데 [current]가 없으면 null.
     */
    fun resolve(current: LocalDateTime?, arg: String): LocalDateTime? =
        parse(arg) ?: current?.let { applyOffset(it, arg) }

    /**
     * 태그 값을 직전 시점에 이어 붙인다. **단조 비감소만 강제한다**(D39).
     *
     * - 시각 태그가 없으면 직전 시각을 잇는다(장면이 이어지는 턴).
     * - 직전보다 이르면 버리고 직전 시각을 쓴다([ClockAdvance.rewound]).
     * - 장소 태그가 없으면 직전 장소를 잇는다.
     * - **상한은 두지 않는다.** "한 달 후"를 막아 버리기 때문이다.
     *
     * 시계를 아직 쓰지 않는 스토리에는 [previous]가 없으므로 이 함수를 부르지 않는다
     * (시작은 `settings.json`의 `clock.start`나 프롤로그 태그로만 한다).
     */
    fun advance(previous: ClockPoint, tagTime: LocalDateTime?, tagPlace: String?): ClockAdvance {
        val place = place(tagPlace) ?: previous.place
        if (tagTime == null) return ClockAdvance(previous.copy(place = place))
        if (tagTime.isBefore(previous.time)) return ClockAdvance(previous.copy(place = place), rewound = true)
        return ClockAdvance(ClockPoint(tagTime, place))
    }
}
