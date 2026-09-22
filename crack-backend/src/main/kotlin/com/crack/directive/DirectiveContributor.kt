package com.crack.directive

import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptContributor
import com.crack.prompt.contributor.PromptSlot
import org.springframework.stereotype.Component

/**
 * BOTTOM(0): 켜진 지속 OOC 지시 (DESIGN.md §6, §8.1, D10). 섹션 이름은 `directives`.
 *
 * 매 턴 마지막 유저 메시지 앞 `[지시]` 블록의 맨 앞에 들어간다. 이번 턴 지시(order 100)보다 먼저다.
 * 대화가 길어져도 원문 범위와 무관하게 해제할 때까지 항상 들어가므로, 20턴 뒤에 지시가 잊히는 문제를 막는다.
 * ```
 * 다음 지시는 해제될 때까지 항상 지켜라:
 * 1. 말투는 반말
 * 2. …
 * ```
 */
@Component
class DirectiveContributor(private val directiveService: DirectiveService) : PromptContributor {
    override val slot = PromptSlot.BOTTOM
    override val order = 0
    override val name = NAME

    override fun contribute(ctx: PromptContext): String? {
        val enabled = directiveService.enabledIn(ctx.storyDir)
        if (enabled.isEmpty()) return null
        return HEADER + "\n" + enabled.mapIndexed { i, d -> "${i + 1}. ${d.text.trim()}" }.joinToString("\n")
    }

    companion object {
        const val NAME = "directives"
        const val HEADER = "다음 지시는 해제될 때까지 항상 지켜라:"
    }
}
