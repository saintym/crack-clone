package com.crack.story.clock

import com.crack.global.exception.BadRequestException
import com.crack.message.dto.ResponseTags
import com.crack.story.files.StoryDirs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.LocalDateTime

/** `/시간` 명령의 응답과 시계 조회 결과 (T38). 필드 이름은 `MessageView`와 같게 둔다 */
data class StoryClockView(
    val storyTime: LocalDateTime,
    val place: String? = null,
)

/**
 * 스토리 단위 시계 흐름 (T38, DESIGN.md §5.4, D39).
 *
 * - [stamp]: 응답 첫 줄 태그를 이번 턴의 시각·장소로 확정한다. **단조 비감소만** 강제한다
 * - [commit]: 확정한 값을 `state.json`에 남긴다
 * - [moveTo]: 메시지를 지우거나 다른 후보를 고르면 시계도 그 시점으로 옮긴다([StoryClockTruncateHook])
 * - [set]: `/시간` 명령. AI가 어긋나게 찍었을 때의 탈출구다
 *
 * **[set]만 예외를 던진다.** 나머지는 어떤 실패도 경고 로그로 삼키고 턴을 계속 진행시킨다 —
 * 시계 때문에 플레이가 멈추지 않게 하려는 것이다.
 */
@Service
class StoryClockService(private val storyDirs: StoryDirs) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 지금 이야기 속 시점. null이면 시계를 쓰지 않는 스토리다 */
    fun current(storyId: Long): ClockPoint? = storyDir(storyId)?.let { StoryClockFiles.point(it) }

    /**
     * 응답 태그에 이번 턴의 시각·장소를 확정해 넣는다.
     *
     * - **시계를 쓰지 않는 스토리는 시각·장소를 지운 태그를 돌려준다.** 설정으로 껐거나 시작점이 없는 경우다.
     *   응답에 태그가 실려 와도 여기서 시계가 켜지지는 않는다(옛 스토리 호환 — 시작은 `clock.start`나 프롤로그 태그다)
     * - 값이 없으면 직전 값을 잇고, 직전보다 이르면 버리고 직전 값을 쓴다(경고 로그)
     * - **어떤 경우에도 예외를 던지지 않는다.** 실패하면 시각·장소 없이 저장된다
     */
    fun stamp(storyId: Long, tags: ResponseTags): ResponseTags {
        val dir = storyDir(storyId) ?: return tags.withoutClock()
        return try {
            val previous = StoryClockFiles.point(dir) ?: return tags.withoutClock()
            val advance = StoryClock.advance(previous, tags.storyTime, tags.place)
            if (advance.rewound) {
                log.warn(
                    "이야기 속 시각이 뒤로 가려 해 직전 값을 유지한다: storyId={}, 태그={}, 직전={}",
                    storyId, tags.storyTime, previous.time,
                )
            }
            val point = advance.point
            tags.copy(storyTime = point.time, place = point.place)
        } catch (e: Exception) {
            log.warn("이야기 속 시각을 정하지 못해 이번 턴은 시계를 비운다: storyId={} ({})", storyId, e.message)
            tags.withoutClock()
        }
    }

    /** 확정한 시각·장소를 `state.json`에 남긴다. 시각이 없으면 아무것도 하지 않는다 */
    fun commit(storyId: Long, tags: ResponseTags) {
        val time = tags.storyTime ?: return
        val dir = storyDir(storyId) ?: return
        try {
            StoryClockFiles.write(dir, ClockPoint(time, tags.place))
        } catch (e: Exception) {
            log.warn("이야기 속 시각을 state.json에 남기지 못했다: storyId={} ({})", storyId, e.message)
        }
    }

    /**
     * 시계를 [point]로 옮긴다. [point]가 null이면 `state.json`의 값을 지워 시작 시각으로 돌아간다.
     *
     * 쓰는 곳은 둘이다 — 메시지를 지우거나 자를 때([StoryClockTruncateHook])와 다른 후보를 고를 때다.
     * 둘 다 **단조 검사를 적용하지 않는다.** 되돌리기가 목적이기 때문이다.
     * 실패해도 예외를 던지지 않는다(삭제 트랜잭션을 깨뜨리지 않는다).
     */
    fun moveTo(storyId: Long, point: ClockPoint?) {
        val dir = storyDir(storyId) ?: return
        try {
            if (!StoryClockFiles.settings(dir).enabled) return
            if (StoryClockFiles.write(dir, point)) {
                log.info("이야기 속 시각을 옮겼다: storyId={}, 시각={}", storyId, point?.time)
            }
        } catch (e: Exception) {
            log.warn("이야기 속 시각을 옮기지 못했다: storyId={} ({})", storyId, e.message)
        }
    }

    /** 프롤로그 첫 줄 태그로 시계를 시작한다. 시작 시각이 이미 있으면 아무것도 하지 않는다 */
    fun start(storyDir: Path, tags: ResponseTags) {
        val time = tags.storyTime ?: return
        StoryClockFiles.start(storyDir, ClockPoint(time, tags.place))
    }

    /**
     * `/시간` 명령 (DESIGN.md §8.2). 절대 시각(`2026-10-05 08:00`)이나 상대 이동(`+3일`)을 받는다.
     *
     * **단조 검사를 적용하지 않는다** — AI가 앞으로 너무 많이 찍었을 때 되돌리는 것이 이 명령의 목적이다.
     * 사용자 명령이므로 알아볼 수 없는 인자는 400으로 알린다(턴 생성 경로가 아니라 즉시 실행 명령이다).
     */
    fun set(storyId: Long, args: String?): StoryClockView {
        val text = args?.trim().orEmpty()
        if (text.isEmpty()) throw BadRequestException("시각을 적어 주세요. 예: /시간 2026-10-05 08:00 또는 /시간 +3일")
        val dir = storyDirs.locate(storyId).dir
        if (!StoryClockFiles.settings(dir).enabled) {
            throw BadRequestException("이 스토리는 시계를 쓰지 않습니다(settings.json의 clock.enabled가 false입니다)")
        }
        val previous = StoryClockFiles.point(dir)
        val time = StoryClock.resolve(previous?.time, text)
            ?: throw BadRequestException(
                if (previous == null) {
                    "시각을 알아볼 수 없습니다: $text — 아직 시계가 없으니 절대 시각으로 적어 주세요(예: 2026-10-05 08:00)"
                } else {
                    "시각을 알아볼 수 없습니다: $text (예: 2026-10-05 08:00, +3일)"
                }
            )
        StoryClockFiles.write(dir, ClockPoint(time, previous?.place))
        log.info("사용자가 이야기 속 시각을 맞췄다: storyId={}, {} → {}", storyId, previous?.time, time)
        return StoryClockView(time, previous?.place)
    }

    private fun ResponseTags.withoutClock(): ResponseTags =
        if (storyTime == null && place == null) this else copy(storyTime = null, place = null)

    /** 스토리 폴더. 찾지 못하면 null(시계를 건너뛴다) */
    private fun storyDir(storyId: Long): Path? = try {
        storyDirs.locate(storyId).dir
    } catch (e: Exception) {
        log.warn("스토리 폴더를 찾지 못해 시계를 건너뛴다: storyId={} ({})", storyId, e.message)
        null
    }
}
