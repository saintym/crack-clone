package com.crack.prompt.contributor

import org.springframework.stereotype.Component

/** CHARACTERS: 활성 인물 문서 전문 (DESIGN.md §6.2). 인물마다 `=== 캐릭터: 이름 ===` 머리를 붙인다. */
@Component
class CharactersContributor(private val selector: ActiveCharacterSelector) : PromptContributor {
    override val slot = PromptSlot.CHARACTERS
    override val order = 0

    override fun contribute(ctx: PromptContext): String? =
        selector.select(ctx)
            .filter { it.text.isNotBlank() }
            .joinToString("\n\n") { StoryDocs.titled("캐릭터: ${it.name}", it.text) }
            .ifEmpty { null }
}
