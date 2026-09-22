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
import com.crack.prompt.service.PromptAssembler
import com.crack.story.files.SampleScenario
import com.crack.story.files.StoryDirs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.nio.file.Path

/**
 * v2 시스템 프롬프트가 언급된 인물만 넣는지와 크기 (T13 측정, Plan-roadmap.md §2.4).
 *
 * v1(모든 인물 주입) 조립기는 T12 정리 후 삭제했다. v1 대비 실측값(192,013B → 56~79KB)은 T13 작업 로그와 로드맵에 있다.
 * 결과는 표준 출력에 `[prompt-size]`로 남긴다.
 */
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
        val allCharacters = v2Characters(dir, "설월과 무극") // 인물 2명 전부 = v1이 넣던 인물 섹션

        println(
            "[prompt-size] sample-scenario system " +
                "v2(언급 없음)=${v2(dir, "객잔에 들어선다")} v2(1명)=${v2(dir, "설월에게 말을 건다")} v2(2명)=${v2(dir, "설월과 무극")} " +
                "| 인물 섹션 전부=$allCharacters v2(1명)=${v2Characters(dir, "설월에게 말을 건다")}",
        )
        assertThat(v2Characters(dir, "객잔에 들어선다")).isZero()
        assertThat(v2Characters(dir, "설월에게 말을 건다")).isGreaterThan(0).isLessThan(allCharacters)
        assertThat(v2(dir, "객잔에 들어선다")).isLessThan(v2(dir, "설월에게 말을 건다"))
    }
}
