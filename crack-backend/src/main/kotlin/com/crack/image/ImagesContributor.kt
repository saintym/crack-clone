package com.crack.image

import com.crack.memory.docs.MemoryDocs
import com.crack.prompt.contributor.ActiveCharacterSelector
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptContributor
import com.crack.prompt.contributor.PromptSlot
import org.springframework.stereotype.Component

/**
 * IMAGES: 쓸 수 있는 이미지 (DESIGN.md §6, §8.5, D14·D31).
 *
 * 두 부분으로 나눠서 준다. URL은 넣지 않는다(토큰 절약, 프론트가 카탈로그로 바꾼다).
 * 1. **활성 인물 변형 목록**: 응답 첫 줄 `[인물: 이름/변형]` 태그(§5.3)에 쓸 수 있는 이름과 변형.
 *    인물 이미지는 서버가 저장한 태그로 **프론트가 렌더 시** 고르므로 본문에 `{{img:…}}`를 쓰게 하지 않는다.
 * 2. **장면·배경 태그**: 인물 이미지가 아닌 항목. 필요할 때만 본문에 `{{img:태그}}`로 직접 넣는다.
 *
 * 활성 인물은 [ActiveCharacterSelector](§6.2)가 고른다. 기억 기록 회차 동안 고정되므로(D30)
 * 이 섹션도 회차 안에서 바뀌지 않아 캐시 접두사가 깨지지 않는다.
 */
@Component
class ImagesContributor(
    private val catalog: ImageCatalogService,
    private val properties: ImageProperties,
    private val activeCharacterSelector: ActiveCharacterSelector,
) : PromptContributor {
    override val slot = PromptSlot.IMAGES
    override val order = 0

    override fun contribute(ctx: PromptContext): String? {
        val entries = catalog.forStory(ctx.storyId)
        if (entries.isEmpty()) return null
        val characterNames = MemoryDocs.readCharacters(ctx.storyDir).map { it.name }
        val active = activeCharacterSelector.select(ctx).map { it.name }
        return render(entries, properties.promptMaxEntries, active, characterNames)
    }

    companion object {
        /** 변형을 적지 않았을 때 쓰는 기본 이미지 이름. 프론트의 폴백 대상이다(§8.5) */
        const val DEFAULT_VARIANT = "기본"

        const val CHARACTER_GUIDE =
            """인물 이미지는 응답 **첫 줄**의 `[인물: 이름/변형]` 태그로 고릅니다. 본문에 직접 넣지 마세요.
- 아래에 있는 이름과 변형만 쓰세요. 변형은 지금 장면과 감정에 가장 가까운 것을 고릅니다.
- 마땅한 변형이 없으면 `/변형` 없이 `[인물: 이름]`만 쓰면 됩니다.
- 그 인물이 장면에 나오면 되도록 태그를 쓰세요. 아래에 없는 인물이거나 주인공만 나오면 태그를 생략합니다."""

        const val SCENE_GUIDE =
            """장면·배경 이미지는 꼭 필요할 때만 본문의 알맞은 자리에 `{{img:태그}}`를 **한 줄에 단독으로** 쓰세요.
- 아래 목록에 있는 태그만 쓰세요. 맞는 이미지가 없으면 쓰지 마세요.
- 한 응답에 1개까지만 쓰세요. 첫 줄 태그 줄에는 쓰지 마세요.
- 이미지 태그를 문장 안에 섞거나 이미지에 대해 설명하지 마세요."""

        /**
         * [max]개까지(위에서부터) 담아 섹션을 만든다. 넣을 것이 없으면 null.
         *
         * @param activeCharacters 이번 턴 활성 인물 이름(§6.2). 이 인물의 이미지만 변형 목록에 넣는다
         * @param characterNames 시나리오의 모든 인물 이름. `{이름}_{변형}` 태그를 장면 태그와 가르는 데 쓴다
         */
        fun render(
            entries: List<ImageEntry>,
            max: Int,
            activeCharacters: List<String> = emptyList(),
            characterNames: Collection<String> = activeCharacters,
        ): String? {
            val shown = entries.take(max.coerceAtLeast(0))
            if (shown.isEmpty()) return null

            val characterBlock = renderCharacters(shown, activeCharacters)
            val sceneEntries = shown.filter { e -> characterNames.none { e.tag.startsWith("${it}_") } }
            val sceneBlock = if (sceneEntries.isEmpty()) null else {
                val list = sceneEntries.joinToString("\n") { e ->
                    if (e.description.isEmpty()) "- ${e.tag}" else "- ${e.tag}: ${e.description}"
                }
                "$SCENE_GUIDE\n\n쓸 수 있는 장면 태그:\n$list"
            }

            val blocks = listOfNotNull(characterBlock, sceneBlock)
            if (blocks.isEmpty()) return null
            return "=== 이미지 ===\n" + blocks.joinToString("\n\n")
        }

        /** 활성 인물별로 쓸 수 있는 변형 이름. 카탈로그에 항목이 없는 인물은 뺀다. */
        private fun renderCharacters(entries: List<ImageEntry>, activeCharacters: List<String>): String? {
            val lines = activeCharacters.mapNotNull { name ->
                val prefix = "${name}_"
                val variants = entries.filter { it.tag.startsWith(prefix) && it.tag.length > prefix.length }
                    .map { it.tag.substring(prefix.length) to it.description }
                if (variants.isEmpty()) return@mapNotNull null
                val sorted = variants.sortedBy { if (it.first == DEFAULT_VARIANT) 0 else 1 }
                val text = sorted.joinToString(", ") { (variant, description) ->
                    if (description.isEmpty()) variant else "$variant($description)"
                }
                "- $name — $text"
            }
            if (lines.isEmpty()) return null
            return "$CHARACTER_GUIDE\n\n쓸 수 있는 인물과 변형:\n" + lines.joinToString("\n")
        }
    }
}
