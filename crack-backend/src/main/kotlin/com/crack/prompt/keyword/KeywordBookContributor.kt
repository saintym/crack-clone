package com.crack.prompt.keyword

import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptContributor
import com.crack.prompt.contributor.PromptSlot
import org.springframework.stereotype.Component

/**
 * KEYWORDS: 이번 턴에 발동한 키워드북 항목 (DESIGN.md §8.3). 섹션 이름은 `keyword_book`.
 *
 * ```
 * === 키워드 설정 ===
 * ### 흑풍채
 * 북쪽 산맥의 산적 소굴. …
 *
 * ### 청운객잔
 * …
 * ```
 * 발동한 항목이 없으면 섹션을 생략한다.
 */
@Component
class KeywordBookContributor(private val keywordBook: KeywordBook) : PromptContributor {
    override val slot = PromptSlot.KEYWORDS
    override val order = 0

    override fun contribute(ctx: PromptContext): String? {
        val active = keywordBook.select(ctx.storyDir, ctx.recentText)
        if (active.isEmpty()) return null
        return "=== $TITLE ===\n" + active.joinToString("\n\n") { "### ${it.id}\n${it.content}" }
    }

    companion object {
        const val TITLE = "키워드 설정"
    }
}
