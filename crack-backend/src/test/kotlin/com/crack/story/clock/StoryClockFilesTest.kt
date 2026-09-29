package com.crack.story.clock

import com.crack.memory.docs.MemoryDocs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * 스토리 폴더에서 시계를 읽고 쓰는 규칙 (T38, DESIGN.md §5.4).
 * `state.json` → `settings.json` 순서, 옛 스토리 호환, `clock.enabled: false`를 확인한다.
 */
class StoryClockFilesTest {

    @TempDir
    lateinit var dir: Path

    private val start = LocalDateTime.of(2026, 2, 15, 19, 0)

    @BeforeEach
    fun setUp() {
        // 스토리 폴더가 실제로 있어야 state.json을 쓸 수 있다
        Files.createDirectories(dir)
    }

    private fun settings(json: String) = Files.writeString(dir.resolve("settings.json"), json)

    private fun state(json: String) = Files.writeString(dir.resolve("state.json"), json)

    // ---- 시계를 쓰지 않는 경우 ----

    @Test
    fun `파일이 아무것도 없으면 시계를 쓰지 않는다`() {
        assertThat(StoryClockFiles.point(dir)).isNull()
        assertThat(StoryClockFiles.isOn(dir)).isFalse()
    }

    @Test
    fun `시작 시각도 없고 state_json에도 값이 없으면 시계를 쓰지 않는다`() {
        settings("""{"responseChars": {"min": 1000, "max": 1800}}""")
        state("""{"companions": ["설월"], "time": "3일차 밤"}""")

        assertThat(StoryClockFiles.point(dir)).isNull()
    }

    @Test
    fun `enabled가 false면 값이 있어도 시계를 쓰지 않는다`() {
        settings("""{"clock": {"start": "2026-02-15 19:00", "enabled": false}}""")
        state("""{"clock": "2026-09-28T23:40"}""")

        assertThat(StoryClockFiles.point(dir)).isNull()
        assertThat(StoryClockFiles.isOn(dir)).isFalse()
    }

    // ---- 값을 고르는 순서 ----

    @Test
    fun `settings_json의 시작 시각으로 시계를 켠다`() {
        settings("""{"clock": {"start": "2026-02-15 19:00", "place": "후유키 심산정"}}""")

        assertThat(StoryClockFiles.point(dir)).isEqualTo(ClockPoint(start, "후유키 심산정"))
        assertThat(StoryClockFiles.isOn(dir)).isTrue()
    }

    @Test
    fun `state_json의 값이 시작 시각보다 우선한다`() {
        settings("""{"clock": {"start": "2026-02-15 19:00", "place": "후유키 심산정"}}""")
        state("""{"clock": "2026-09-28T23:40", "place": "에미야 저택"}""")

        assertThat(StoryClockFiles.point(dir)).isEqualTo(ClockPoint(LocalDateTime.of(2026, 9, 28, 23, 40), "에미야 저택"))
    }

    @Test
    fun `state_json에 장소가 없으면 시작 장소를 쓴다`() {
        settings("""{"clock": {"start": "2026-02-15 19:00", "place": "후유키 심산정"}}""")
        state("""{"clock": "2026-09-28T23:40"}""")

        assertThat(StoryClockFiles.point(dir)?.place).isEqualTo("후유키 심산정")
    }

    @Test
    fun `이상한 값은 경고만 남기고 폴백한다`() {
        settings("""{"clock": {"start": "2026-02-15 19:00"}}""")
        state("""{"clock": "홍무 15년 8월 13일"}""")

        assertThat(StoryClockFiles.point(dir)?.time).isEqualTo(start)
    }

    @Test
    fun `시작 시각이 이상하면 시계를 쓰지 않는다`() {
        settings("""{"clock": {"start": "언젠가"}}""")

        assertThat(StoryClockFiles.point(dir)).isNull()
    }

    @Test
    fun `settings_json이 깨져 있어도 턴을 막지 않는다`() {
        settings("{ clock: ")

        assertThat(StoryClockFiles.point(dir)).isNull()
        assertThat(StoryClockFiles.settings(dir).enabled).isTrue()
    }

    @Test
    fun `모르는 필드는 무시한다`() {
        settings("""{"clock": {"start": "2026-02-15 19:00", "calendar": "홍무"}, "unknown": 1}""")

        assertThat(StoryClockFiles.point(dir)?.time).isEqualTo(start)
    }

    // ---- 쓰기 ----

    @Test
    fun `시계를 쓰면 state_json의 다른 필드는 그대로 남는다`() {
        state("""{"companions": ["설월"], "time": "3일차 밤", "updatedAtTurn": 30}""")

        assertThat(StoryClockFiles.write(dir, ClockPoint(start, "후유키 심산정"))).isTrue()

        val saved = MemoryDocs.readState(dir)
        assertThat(saved.clock).isEqualTo("2026-02-15T19:00")
        assertThat(saved.place).isEqualTo("후유키 심산정")
        assertThat(saved.companions).containsExactly("설월")
        assertThat(saved.time).isEqualTo("3일차 밤")
        assertThat(saved.updatedAtTurn).isEqualTo(30)
    }

    @Test
    fun `같은 값을 다시 쓰면 파일을 건드리지 않는다`() {
        StoryClockFiles.write(dir, ClockPoint(start))

        assertThat(StoryClockFiles.write(dir, ClockPoint(start))).isFalse()
    }

    @Test
    fun `null을 쓰면 값을 지워 시작 시각으로 돌아간다`() {
        settings("""{"clock": {"start": "2026-02-15 19:00"}}""")
        StoryClockFiles.write(dir, ClockPoint(LocalDateTime.of(2026, 9, 28, 23, 40), "에미야 저택"))

        StoryClockFiles.write(dir, null)

        assertThat(MemoryDocs.readState(dir).clock).isNull()
        assertThat(StoryClockFiles.point(dir)?.time).isEqualTo(start)
    }

    // ---- 프롤로그 태그로 시작하기 ----

    @Test
    fun `시작 시각이 없으면 프롤로그 태그로 시계를 켠다`() {
        assertThat(StoryClockFiles.start(dir, ClockPoint(start, "후유키 심산정"))).isTrue()

        assertThat(StoryClockFiles.point(dir)).isEqualTo(ClockPoint(start, "후유키 심산정"))
    }

    @Test
    fun `settings_json에 시작 시각이 있으면 프롤로그 태그는 덮어쓰지 않는다`() {
        settings("""{"clock": {"start": "2026-02-15 19:00"}}""")

        assertThat(StoryClockFiles.start(dir, ClockPoint(LocalDateTime.of(2030, 1, 1, 0, 0)))).isFalse()
        assertThat(StoryClockFiles.point(dir)?.time).isEqualTo(start)
    }

    @Test
    fun `enabled가 false면 프롤로그 태그로도 켜지 않는다`() {
        settings("""{"clock": {"enabled": false}}""")

        assertThat(StoryClockFiles.start(dir, ClockPoint(start))).isFalse()
        assertThat(StoryClockFiles.point(dir)).isNull()
    }

    @Test
    fun `프롤로그에 시각 태그가 없으면 아무것도 하지 않는다`() {
        assertThat(StoryClockFiles.start(dir, null)).isFalse()
        assertThat(Files.exists(dir.resolve("state.json"))).isFalse()
    }
}
