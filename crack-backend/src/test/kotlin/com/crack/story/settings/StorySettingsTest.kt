package com.crack.story.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** 스토리 폴더의 `settings.json` 읽기와 폴백 (DESIGN.md §6.4 / D33). */
class StorySettingsTest {

    @TempDir lateinit var dir: Path

    private val global = ResponseChars()

    private fun write(json: String) = Files.writeString(dir.resolve(StorySettings.FILE_NAME), json)

    @Test
    fun `기본값은 800에서 1500이고 절대 상한은 2000이다`() {
        assertThat(global.min).isEqualTo(800)
        assertThat(global.max).isEqualTo(1500)
        assertThat(global.hardMax).isEqualTo(2000)
    }

    @Test
    fun `파일이 없으면 전역 기본값을 쓴다`() {
        assertThat(StorySettings.read(dir)).isEqualTo(StorySettings.EMPTY)
        assertThat(StorySettings.responseChars(dir, global)).isEqualTo(global)
    }

    @Test
    fun `파일에 값이 있으면 그 값을 쓴다`() {
        write("""{"responseChars": {"min": 1200, "max": 2200}}""")
        assertThat(StorySettings.responseChars(dir, global)).isEqualTo(ResponseChars(1200, 2200))
    }

    @Test
    fun `모르는 필드는 무시하고 빠진 필드는 기본값을 쓴다`() {
        write("""{"모르는필드": 1, "responseChars": {"min": 600, "낯선키": "x"}}""")
        assertThat(StorySettings.responseChars(dir, global)).isEqualTo(ResponseChars(600, ResponseChars.DEFAULT_MAX))
    }

    @Test
    fun `responseChars가 없으면 전역 기본값을 쓴다`() {
        write("""{"다른설정": true}""")
        assertThat(StorySettings.responseChars(dir, global)).isEqualTo(global)
        write("""{"responseChars": null}""")
        assertThat(StorySettings.responseChars(dir, global)).isEqualTo(global)
    }

    @Test
    fun `JSON이 깨졌으면 전역 기본값을 쓴다`() {
        write("{ responseChars: ")
        assertThat(StorySettings.responseChars(dir, global)).isEqualTo(global)
        write("")
        assertThat(StorySettings.responseChars(dir, global)).isEqualTo(global)
        write("""{"responseChars": {"min": "여덟백"}}""")
        assertThat(StorySettings.responseChars(dir, global)).isEqualTo(global)
    }

    @Test
    fun `이상한 범위는 전역 기본값으로 돌아간다`() {
        for (json in listOf(
            """{"responseChars": {"min": 3000, "max": 1000}}""", // min > max
            """{"responseChars": {"min": -100, "max": 1000}}""", // 음수
            """{"responseChars": {"min": 0, "max": 0}}""",
            """{"responseChars": {"min": 100, "max": 999999}}""", // 상한 초과
        )) {
            write(json)
            assertThat(StorySettings.responseChars(dir, global)).`as`(json).isEqualTo(global)
        }
    }

    @Test
    fun `잘못된 값마다 이유를 남긴다`() {
        assertThat(ResponseChars(0, 100).invalidReason()).contains("min이 0 이하")
        assertThat(ResponseChars(200, 100).invalidReason()).contains("min이 max보다 크다")
        assertThat(ResponseChars(200, 99_999).invalidReason()).contains("상한")
        assertThat(ResponseChars(200, 200).invalidReason()).isNull()
        assertThat(ResponseChars(200, 200).isValid()).isTrue()
    }

    @Test
    fun `폴더가 아닌 경로나 읽을 수 없는 파일은 기본값을 쓴다`() {
        Files.createDirectory(dir.resolve(StorySettings.FILE_NAME)) // 파일 자리에 폴더
        assertThat(StorySettings.responseChars(dir, global)).isEqualTo(global)
    }

    @Test
    fun `템플릿의 값은 전역 기본값과 같다`() {
        val template = Path.of("..", "data", "_templates", StorySettings.FILE_NAME)
        val settings = StorySettings.fromJson(Files.readString(template))
        assertThat(settings.responseChars).isEqualTo(ResponseChars())
    }
}
