package com.crack.prompt.contributor

import org.springframework.stereotype.Component

/**
 * CHARACTERS: 활성 인물 문서 전문 (DESIGN.md §6.2). 인물마다 `=== 캐릭터: 이름 ===` 머리를 붙인다.
 *
 * 문서는 두 순수 함수를 거쳐 들어간다. 원본 파일은 바뀌지 않는다.
 * - [GreetingSection]: `## 첫 인사`에 말투 참고용 라벨을 붙인다(§6.6, D46)
 * - [PrivateSections]: `(비공개)` 섹션을 라벨과 함께 뒤로 몰아 넣는다(§6.5, D38)
 */
@Component
class CharactersContributor(private val selector: ActiveCharacterSelector) : PromptContributor {
    override val slot = PromptSlot.CHARACTERS
    override val order = 0

    override fun contribute(ctx: PromptContext): String? =
        selector.select(ctx)
            .filter { it.text.isNotBlank() }
            .joinToString("\n\n") { StoryDocs.titled("캐릭터: ${it.name}", render(it.text)) }
            .ifEmpty { null }

    private fun render(text: String): String = PrivateSections.render(GreetingSection.render(text))
}
