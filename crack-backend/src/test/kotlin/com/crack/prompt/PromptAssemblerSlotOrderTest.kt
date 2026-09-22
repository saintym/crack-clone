package com.crack.prompt

import com.crack.chat.flow.ConversationBuilder
import com.crack.message.service.MessageService
import com.crack.prompt.config.PromptProperties
import com.crack.prompt.contributor.ActiveCharacterSelector
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptContributor
import com.crack.prompt.contributor.PromptSlot
import com.crack.prompt.service.PromptAssembler
import com.crack.story.files.StoryDirs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.nio.file.Path

/** 기여자 순서와 생략 규칙 (DESIGN.md §6). DB 없이 [PromptAssembler.buildSections]만 본다. */
class PromptAssemblerSlotOrderTest {

    private class Fixed(
        override val slot: PromptSlot,
        override val order: Int,
        override val name: String,
        private val text: String? = name,
    ) : PromptContributor {
        override fun contribute(ctx: PromptContext): String? = text
    }

    private class Failing : PromptContributor {
        override val slot = PromptSlot.WORLD
        override val order = 50
        override fun contribute(ctx: PromptContext): String = error("boom")
    }

    private fun assembler(contributors: List<PromptContributor>) = PromptAssembler(
        contributors, mock<StoryDirs>(), mock<MessageService>(), mock<ConversationBuilder>(),
        mock<ActiveCharacterSelector>(), PromptProperties(),
    )

    private val ctx = PromptContext(1L, Path.of("unused"), "", null, null)

    @Test
    fun `슬롯 선언 순서, 같은 슬롯은 order 순으로 모은다`() {
        // 일부러 뒤섞어 등록한다
        val contributors = listOf(
            Fixed(PromptSlot.BOTTOM, 100, "turn"),
            Fixed(PromptSlot.IMAGES, 0, "images"),
            Fixed(PromptSlot.SCENARIO, 100, "chronicle"),
            Fixed(PromptSlot.BASE, 0, "base"),
            Fixed(PromptSlot.USER_NOTE, 0, "note"),
            Fixed(PromptSlot.KEYWORDS, 0, "keywords"),
            Fixed(PromptSlot.BOTTOM, 0, "directives"),
            Fixed(PromptSlot.CHARACTERS, 0, "characters"),
            Fixed(PromptSlot.PROTAGONIST, 0, "protagonist"),
            Fixed(PromptSlot.SCENARIO, 0, "scenario"),
            Fixed(PromptSlot.WORLD, 0, "world"),
        )

        val names = assembler(contributors).buildSections(ctx).map { it.name }

        assertThat(names).containsExactly(
            "base", "world", "scenario", "chronicle", "protagonist", "characters",
            "keywords", "note", "images", "directives", "turn",
        )
    }

    @Test
    fun `null, 공백, 예외는 섹션에서 뺀다`() {
        val contributors = listOf(
            Fixed(PromptSlot.BASE, 0, "base"),
            Fixed(PromptSlot.WORLD, 0, "null", text = null),
            Fixed(PromptSlot.WORLD, 10, "blank", text = "  \n"),
            Failing(),
            Fixed(PromptSlot.SCENARIO, 0, "scenario", text = "시나리오\n\n"),
        )

        val sections = assembler(contributors).buildSections(ctx)

        assertThat(sections.map { it.name }).containsExactly("base", "scenario")
        assertThat(sections.last().content).isEqualTo("시나리오")
        assertThat(sections.last().chars).isEqualTo(4)
    }
}
