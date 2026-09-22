package com.crack.prompt

import com.crack.chat.flow.ConversationBuilder
import com.crack.memory.docs.MemoryBudgets
import com.crack.message.service.MessageService
import com.crack.prompt.config.PromptProperties
import com.crack.prompt.contributor.ActiveCharacterSelector
import com.crack.prompt.contributor.BaseContributor
import com.crack.prompt.contributor.CharactersContributor
import com.crack.prompt.contributor.ChronicleContributor
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.ProtagonistContributor
import com.crack.prompt.contributor.ScenarioContributor
import com.crack.prompt.contributor.TurnInstructionContributor
import com.crack.prompt.contributor.UserNoteContributor
import com.crack.prompt.contributor.WorldContributor
import com.crack.prompt.keyword.KeywordMatcher
import com.crack.prompt.service.LegacyPromptAssembler
import com.crack.prompt.service.PromptAssembler
import com.crack.story.files.SampleScenario
import com.crack.story.files.StoryDirs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.nio.file.Path

/**
 * v1(모든 인물 주입) 대비 v2 시스템 프롬프트 크기 (T13 측정, Plan-roadmap.md §2.4).
 *
 * 결과는 표준 출력에 `[prompt-size]`로 남긴다. 픽스처는 인물 2명뿐이라 차이가 작다. 실제 데이터 측정값은 T13 작업 로그에 있다.
 */
@Suppress("DEPRECATION")
class PromptSizeComparisonTest {

    @TempDir lateinit var tmp: Path

    private val selector = ActiveCharacterSelector(KeywordMatcher())
    private val assembler = PromptAssembler(
        listOf(
            BaseContributor(), WorldContributor(), ScenarioContributor(), ChronicleContributor(MemoryBudgets()),
            ProtagonistContributor(), CharactersContributor(selector), UserNoteContributor(), TurnInstructionContributor(),
        ),
        mock<StoryDirs>(), mock<MessageService>(), mock<ConversationBuilder>(), selector, PromptProperties(),
    )

    private fun sections(dir: Path, recentText: String) =
        assembler.buildSections(PromptContext(1L, dir, recentText, recentText, null)).filter { it.slot.inSystemPrompt }

    private fun v2(dir: Path, recentText: String): Int = sections(dir, recentText).joinToString("\n\n") { it.content }.length

    private fun v2Characters(dir: Path, recentText: String): Int =
        sections(dir, recentText).filter { it.name == "characters" }.sumOf { it.chars }

    @Test
    fun `샘플 픽스처에서 v2는 언급된 인물만 넣는다`() {
        val dir = SampleScenario.copyTo(tmp.resolve("story"))
        val v1 = LegacyPromptAssembler().assembleSystemPrompt(dir, dir).length
        val v1Characters = v2Characters(dir, "설월과 무극") // 인물 2명 전부 = v1이 넣는 인물 섹션

        println(
            "[prompt-size] sample-scenario system v1(모든 인물)=$v1 " +
                "v2(언급 없음)=${v2(dir, "객잔에 들어선다")} v2(1명)=${v2(dir, "설월에게 말을 건다")} v2(2명)=${v2(dir, "설월과 무극")} " +
                "| 인물 섹션 전부=$v1Characters v2(1명)=${v2Characters(dir, "설월에게 말을 건다")}",
        )
        assertThat(v2Characters(dir, "객잔에 들어선다")).isZero()
        assertThat(v2Characters(dir, "설월에게 말을 건다")).isGreaterThan(0).isLessThan(v1Characters)
        // BASE에 유저 입력 규칙이 늘어서, 인물이 작은 픽스처에서는 전체 크기가 v1과 비슷하다
        assertThat(v2(dir, "객잔에 들어선다")).isLessThan(v2(dir, "설월에게 말을 건다"))
    }
}
