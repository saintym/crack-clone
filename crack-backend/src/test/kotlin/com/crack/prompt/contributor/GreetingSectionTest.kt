package com.crack.prompt.contributor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `## 첫 인사` 말투 참고용 라벨 (DESIGN.md §6.6, D46) */
class GreetingSectionTest {

    private val labeled = "## ${GreetingSection.TITLE} ${GreetingSection.LABEL}"

    @Test
    fun `첫 인사 섹션이 없으면 원문 그대로다`() {
        val text = "# 캐릭터: 설월\n\n## 성격\n조용하다.\n"

        assertThat(GreetingSection.render(text)).isEqualTo(text)
    }

    @Test
    fun `첫 인사 제목에 라벨을 붙이고 본문은 건드리지 않는다`() {
        val text = """
            # 캐릭터: 설월

            ## 말투
            존댓말.

            ## 첫 인사
            "……사쿠라의 손님이 아니군요. 용건을 말하세요. 짧게."

            ## 기억
        """.trimIndent() + "\n"

        val rendered = GreetingSection.render(text)

        assertThat(rendered).contains(labeled)
        assertThat(rendered).doesNotContain("## 첫 인사\n")
        // 제목 줄만 바뀐다. 본문과 다른 섹션은 그대로다
        assertThat(rendered).contains("\"……사쿠라의 손님이 아니군요. 용건을 말하세요. 짧게.\"")
        assertThat(rendered.replace(labeled, "## 첫 인사")).isEqualTo(text)
    }

    @Test
    fun `장면 스크립트가 들어 있어도 내용은 그대로 두고 라벨만 붙인다`() {
        val scene = "홍무 15년 8월 13일, 정주 외곽 관도의 낡은 주막. 천마가 돌아왔다는 소문에 술꾼들이 수군댔다."
        val text = "# 캐릭터: 무극\n\n## 첫 인사\n$scene\n"

        val rendered = GreetingSection.render(text)

        assertThat(rendered).isEqualTo("# 캐릭터: 무극\n\n$labeled\n$scene\n")
    }

    @Test
    fun `제목이 파일 끝에 줄바꿈 없이 있어도 깨지지 않는다`() {
        assertThat(GreetingSection.render("# 캐릭터: 무극\n\n## 첫 인사"))
            .isEqualTo("# 캐릭터: 무극\n\n$labeled")
    }

    @Test
    fun `이미 라벨이 붙어 있으면 다시 붙이지 않는다`() {
        val text = "# 캐릭터: 설월\n\n$labeled\n\"짧게.\"\n"

        assertThat(GreetingSection.render(text)).isEqualTo(text)
    }

    @Test
    fun `다른 수준의 제목이나 비슷한 제목은 건드리지 않는다`() {
        val text = "# 캐릭터: 설월\n\n### 첫 인사\n\"안녕.\"\n\n## 첫 인사말\n\"반갑소.\"\n"

        assertThat(GreetingSection.render(text)).isEqualTo(text)
    }

    @Test
    fun `비공개 섹션 분리와 함께 써도 둘 다 동작한다`() {
        val text = """
            # 캐릭터: 설월

            ## 첫 인사
            "용건을 말하세요."

            ## 진짜 정체 (비공개)
            마교의 첩자다.
        """.trimIndent() + "\n"

        val rendered = PrivateSections.render(GreetingSection.render(text))

        assertThat(rendered).contains(labeled)
        assertThat(rendered.indexOf(PrivateSections.LABEL)).isLessThan(rendered.indexOf("마교의 첩자다"))
        assertThat(rendered.indexOf(labeled)).isLessThan(rendered.indexOf(PrivateSections.LABEL))
    }
}
