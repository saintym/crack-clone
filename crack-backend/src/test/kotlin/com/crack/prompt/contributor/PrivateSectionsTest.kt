package com.crack.prompt.contributor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 비공개 섹션 분리 (DESIGN.md §6.5, D38) */
class PrivateSectionsTest {

    @Test
    fun `비공개 섹션이 없으면 원문 그대로다`() {
        val text = "# 캐릭터: 설월\n\n## 성격\n조용하다.\n"

        assertThat(PrivateSections.render(text)).isEqualTo(text)
        assertThat(PrivateSections.split(text).hasPrivate).isFalse()
    }

    @Test
    fun `비공개 섹션을 뒤로 몰고 라벨을 붙인다`() {
        val text = """
            # 캐릭터: 설월

            ## 성격
            조용하다.

            ## 진짜 정체 (비공개)
            마교의 첩자다.

            ## 말투
            존댓말.
        """.trimIndent() + "\n"

        val rendered = PrivateSections.render(text)

        // 공개 부분은 순서를 지킨다
        assertThat(rendered.indexOf("## 성격")).isLessThan(rendered.indexOf("## 말투"))
        // 비공개는 라벨 뒤로 간다
        assertThat(rendered.indexOf(PrivateSections.LABEL)).isLessThan(rendered.indexOf("마교의 첩자다"))
        assertThat(rendered.indexOf("## 말투")).isLessThan(rendered.indexOf(PrivateSections.LABEL))
        // 내용은 하나도 빠지지 않는다
        assertThat(rendered).contains("조용하다.", "존댓말.", "마교의 첩자다.", "## 진짜 정체 (비공개)")
    }

    @Test
    fun `비공개 섹션이 여러 개면 순서대로 모은다`() {
        val text = """
            ## 하나 (비공개)
            가

            ## 공개
            나

            ## 둘 (비공개)
            다
        """.trimIndent() + "\n"

        val split = PrivateSections.split(text)

        assertThat(split.public).contains("## 공개", "나").doesNotContain("가", "다")
        assertThat(split.private.indexOf("가")).isLessThan(split.private.indexOf("다"))
    }

    @Test
    fun `하위 제목은 비공개 섹션에 딸려 간다`() {
        val text = """
            ## 배경 (비공개)
            앞줄

            ### 세부
            딸린 내용

            ## 공개
            남는다
        """.trimIndent() + "\n"

        val split = PrivateSections.split(text)

        assertThat(split.private).contains("### 세부", "딸린 내용")
        assertThat(split.public).doesNotContain("딸린 내용")
    }

    @Test
    fun `제목 중간에 있는 표시는 비공개가 아니다`() {
        val text = "## (비공개) 아닌 제목\n내용\n"

        assertThat(PrivateSections.split(text).hasPrivate).isFalse()
    }

    @Test
    fun `문서 전체가 비공개면 라벨과 본문만 남는다`() {
        val text = "## 전부 (비공개)\n비밀\n"

        val rendered = PrivateSections.render(text)

        assertThat(rendered).startsWith(PrivateSections.LABEL)
        assertThat(rendered).contains("비밀")
    }
}
