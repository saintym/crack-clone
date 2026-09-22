package com.crack.image

import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptContributor
import com.crack.prompt.contributor.PromptSlot
import org.springframework.stereotype.Component

/**
 * IMAGES: 쓸 수 있는 이미지 태그와 설명 (DESIGN.md §6, §8.5, D14).
 *
 * URL은 넣지 않는다(토큰 절약, 프론트가 카탈로그로 바꾼다). 첫 줄 감정 태그(§5.3, D19)와 어울리는 이미지를 고르게 안내한다.
 * 감정 태그는 서버가 떼어 내므로 사용자에게 보이지 않는다.
 */
@Component
class ImagesContributor(
    private val catalog: ImageCatalogService,
    private val properties: ImageProperties,
) : PromptContributor {
    override val slot = PromptSlot.IMAGES
    override val order = 0

    override fun contribute(ctx: PromptContext): String? = render(catalog.forStory(ctx.storyId), properties.promptMaxEntries)

    companion object {
        const val GUIDE = """장면에 잘 맞는 이미지가 있을 때만 본문의 알맞은 자리에 `{{img:태그}}`를 **한 줄에 단독으로** 쓰세요.
- 아래 목록에 있는 태그만 쓰세요. 맞는 이미지가 없으면 쓰지 마세요.
- 한 응답에 1~2개까지만 쓰세요. 첫 줄 감정 태그 줄에는 쓰지 마세요.
- 첫 줄 감정 태그에 적은 감정과 지금 장면의 인물, 분위기에 가장 어울리는 이미지를 고르세요.
- 이미지 태그를 문장 안에 섞거나 이미지에 대해 설명하지 마세요."""

        /** 항목이 없거나 [max]가 0 이하면 null(섹션 생략). */
        fun render(entries: List<ImageEntry>, max: Int): String? {
            val shown = entries.take(max.coerceAtLeast(0))
            if (shown.isEmpty()) return null
            val list = shown.joinToString("\n") { e ->
                if (e.description.isEmpty()) "- ${e.tag}" else "- ${e.tag}: ${e.description}"
            }
            return "=== 이미지 ===\n$GUIDE\n\n사용할 수 있는 이미지 태그:\n$list"
        }
    }
}
