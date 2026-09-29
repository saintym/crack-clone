package com.crack.story.clock

import com.crack.prompt.contributor.BaseContributor
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptSlot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/** 시계가 프롬프트에 들어가는 방식 (T38, DESIGN.md §5.4, §6) */
class StoryClockPromptTest {

    @TempDir
    lateinit var dir: Path

    private fun ctx() = PromptContext(
        storyId = 1L,
        storyDir = dir,
        recentText = "",
        userInput = null,
        turnInstruction = null,
    )

    private fun clockOn() =
        Files.writeString(dir.resolve("settings.json"), """{"clock": {"start": "2026-02-15 19:00", "place": "후유키 심산정"}}""")

    // ---- BOTTOM: 현재 시각·장소 ----

    @Test
    fun `시계를 쓰면 현재 시각과 장소를 넣는다`() {
        clockOn()

        val section = StoryClockContributor().contribute(ctx())

        assertThat(section).isEqualTo("[현재 시각] 2026-02-15 19:00\n[현재 장소] 후유키 심산정")
    }

    @Test
    fun `시계를 쓰지 않는 스토리에는 아무것도 넣지 않는다`() {
        assertThat(StoryClockContributor().contribute(ctx())).isNull()

        Files.writeString(dir.resolve("settings.json"), """{"clock": {"enabled": false}}""")
        assertThat(StoryClockContributor().contribute(ctx())).isNull()
    }

    @Test
    fun `장소가 없으면 시각만 넣는다`() {
        assertThat(StoryClockContributor.render(ClockPoint(LocalDateTime.of(2026, 2, 15, 19, 0))))
            .isEqualTo("[현재 시각] 2026-02-15 19:00")
    }

    @Test
    fun `매 턴 바뀌는 값이므로 BOTTOM에 둔다`() {
        val contributor = StoryClockContributor()

        assertThat(contributor.slot).isEqualTo(PromptSlot.BOTTOM)
        assertThat(contributor.name).isEqualTo("story_clock")
        // 이번 턴 지시(100)보다 먼저, 지속 지시(0)보다 나중
        assertThat(contributor.order).isBetween(1, 99)
    }

    // ---- BASE: 태그 규칙 ----

    @Test
    fun `시계를 쓰면 BASE 출력 형식에 시각·장소 규칙이 붙는다`() {
        clockOn()

        val base = BaseContributor().contribute(ctx())

        assertThat(base).contains("[시간: (이 장면이 도달한 시각)]").contains("[장소: (장면이 끝난 시점의 장소)]")
        assertThat(base).contains("이 장면이 도달한 시각")
        assertThat(base).contains("장면의 길이는 **내용에 맞게** 판단하세요")
        assertThat(base).contains("유저 입력에 시간 표현이 있으면 반드시 그것을 따릅니다")
        assertThat(base).contains("시각은 되돌아가지 않습니다")
        assertThat(base).contains("[시간: 2026-09-28 23:40]") // 예시
        assertThat(base).doesNotContain("{{CLOCK")
    }

    @Test
    fun `시계를 쓰지 않으면 BASE는 예전과 같다`() {
        val base = BaseContributor().contribute(ctx())

        assertThat(base).doesNotContain("[시간:").doesNotContain("[장소:").doesNotContain("{{CLOCK")
        assertThat(base).contains("태그 줄 형식: [감정: (현재 감정 1~3개)] [인물: (중심 인물 이름)/(이미지 변형)]\n\n- `[감정: …]`은 매번 씁니다.")
        assertThat(base).contains("\n예시:\n[감정: 경계심, 호기심] [인물: 설월/경계]")
    }
}
