package com.crack.story.clock

import com.crack.memory.docs.MemoryDocs
import com.crack.story.settings.ClockSettings
import com.crack.story.settings.StorySettings
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * 스토리 폴더에서 시계를 읽고 쓴다 (T38, DESIGN.md §5.4).
 *
 * 스토리 폴더만 본다(D12). 값은 `state.json`의 `clock`·`place`에 있고, 시작점은 `settings.json`의 `clock`에 있다.
 * DB도 Spring도 모르는 순수 파일 라이브러리다 — 프롬프트 기여자가 매 턴 파일 두 개를 읽는다.
 *
 * **읽기는 어떤 경우에도 예외를 던지지 않는다.** 읽지 못하면 시계를 쓰지 않는 것으로 본다(경고 로그).
 * 시계가 깨지는 것보다 멈추는 쪽이 낫다.
 */
object StoryClockFiles {

    private val log = LoggerFactory.getLogger(StoryClockFiles::class.java)

    /** 이 스토리의 시계 설정. 읽지 못하면 기본값(`enabled: true`, 시작 시각 없음) */
    fun settings(storyDir: Path): ClockSettings = try {
        StorySettings.clock(storyDir)
    } catch (e: Exception) {
        log.warn("시계 설정을 읽지 못해 기본값을 쓴다: {} ({})", storyDir, e.message)
        ClockSettings()
    }

    /**
     * 지금 이야기 속 시점. **null이면 이 스토리는 시계를 쓰지 않는다.**
     *
     * 1. `settings.json`의 `clock.enabled`가 false면 쓰지 않는다
     * 2. `state.json`의 `clock`이 있으면 그 값이다
     * 3. 없으면 `settings.json`의 `clock.start`다
     * 4. 그것도 없으면 쓰지 않는다(옛 스토리 호환). 프롤로그 태그는 스토리를 만들 때 [start]가 켜 둔다
     */
    fun point(storyDir: Path): ClockPoint? {
        val settings = settings(storyDir)
        if (!settings.enabled) return null
        val state = try {
            MemoryDocs.readState(storyDir)
        } catch (e: Exception) {
            log.warn("state.json을 읽지 못해 시계를 건너뛴다: {} ({})", storyDir, e.message)
            return null
        }
        val fromState = StoryClock.parse(state.clock)
        if (fromState != null) {
            return ClockPoint(fromState, StoryClock.place(state.place) ?: StoryClock.place(settings.place))
        }
        if (state.clock != null) {
            log.warn("state.json의 clock 값을 알아볼 수 없어 시작 시각으로 돌아간다: {} ({})", storyDir, state.clock)
        }
        val fromSettings = StoryClock.parse(settings.start)
        if (fromSettings == null) {
            if (settings.start != null) {
                log.warn("settings.json의 clock.start 값을 알아볼 수 없어 시계를 쓰지 않는다: {} ({})", storyDir, settings.start)
            }
            return null
        }
        return ClockPoint(fromSettings, StoryClock.place(settings.place))
    }

    /** 이 스토리가 시계를 쓰는지. 프롬프트에 시계 규칙을 넣을지 정하는 데 쓴다 */
    fun isOn(storyDir: Path): Boolean = point(storyDir) != null

    /**
     * `state.json`의 시계를 [point]로 맞춘다. [point]가 null이면 값을 지운다(시작 시각으로 돌아간다).
     * 값이 그대로면 파일을 건드리지 않는다.
     *
     * @return 실제로 파일을 고쳤는지
     */
    fun write(storyDir: Path, point: ClockPoint?): Boolean {
        val state = MemoryDocs.readState(storyDir)
        val next = state.copy(
            clock = point?.let { StoryClock.store(it.time) },
            place = point?.place,
        )
        if (next == state) return false
        MemoryDocs.writeState(storyDir, next)
        return true
    }

    /**
     * 시계를 아직 쓰지 않는 스토리라면 [point]로 시작한다 (프롤로그 첫 줄 태그, T38).
     * `settings.json`에 시작 시각이 있거나 이미 `state.json`에 값이 있으면 아무것도 하지 않는다.
     */
    fun start(storyDir: Path, point: ClockPoint?): Boolean {
        if (point == null) return false
        return try {
            val settings = settings(storyDir)
            if (!settings.enabled) return false
            if (StoryClock.parse(settings.start) != null) return false
            if (StoryClock.parse(MemoryDocs.readState(storyDir).clock) != null) return false
            write(storyDir, ClockPoint(point.time, point.place ?: StoryClock.place(settings.place)))
        } catch (e: Exception) {
            log.warn("시계를 시작하지 못했다: {} ({})", storyDir, e.message)
            false
        }
    }
}
