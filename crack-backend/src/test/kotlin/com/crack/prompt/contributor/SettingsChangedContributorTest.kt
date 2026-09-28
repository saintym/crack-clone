package com.crack.prompt.contributor

import com.crack.memory.docs.MemoryDocs
import com.crack.memory.docs.StoryState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** 설정 변경 신호 (T39, D41) */
class SettingsChangedContributorTest {

    @TempDir lateinit var storyDir: Path

    private val contributor = SettingsChangedContributor()

    private fun ctx() = PromptContext(1L, storyDir, "", null, null)

    private fun state(vararg changed: String) =
        MemoryDocs.writeState(storyDir, StoryState(changedDocs = changed.toList()))

    @Test
    fun `바뀐 문서가 없으면 아무것도 넣지 않는다`() {
        state()
        assertThat(contributor.contribute(ctx())).isNull()
    }

    @Test
    fun `state_json이 없어도 터지지 않는다`() {
        assertThat(contributor.contribute(ctx())).isNull()
    }

    @Test
    fun `바뀐 문서를 나열하고 문서가 우선이라고 못 박는다`() {
        state("characters/설월.md", "world.md")

        val text = contributor.contribute(ctx())!!

        assertThat(text).contains("`characters/설월.md`", "`world.md`")
        assertThat(text).contains("이번 응답부터 따른다", "문서가 우선이다")
        // 이야기 안에서 언급하지 않게 한다
        assertThat(text).contains("언급하지 않는다")
    }

    @Test
    fun `BOTTOM 슬롯이라 캐시 접두사를 깨지 않는다`() {
        assertThat(contributor.slot).isEqualTo(PromptSlot.BOTTOM)
        assertThat(PromptSlot.BOTTOM.inSystemPrompt).isFalse()
    }

    @Test
    fun `문서가 너무 많으면 개수로 줄인다`() {
        val many = (1..SettingsChangedContributor.MAX_LISTED + 3).map { "characters/c$it.md" }
        state(*many.toTypedArray())

        val text = contributor.contribute(ctx())!!

        assertThat(text).contains("외 3개")
        assertThat(text).doesNotContain("characters/c${SettingsChangedContributor.MAX_LISTED + 1}.md")
    }

    @Test
    fun `깨진 state_json이면 신호를 건너뛴다`() {
        Files.writeString(MemoryDocs.statePath(storyDir), "{ 깨짐")

        assertThat(contributor.contribute(ctx())).isNull()
    }
}
