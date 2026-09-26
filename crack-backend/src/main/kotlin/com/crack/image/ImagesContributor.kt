package com.crack.image

import com.crack.memory.docs.MemoryDocs
import com.crack.prompt.contributor.ActiveCharacterSelector
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptContributor
import com.crack.prompt.contributor.PromptSlot
import org.springframework.stereotype.Component

/**
 * IMAGES: 쓸 수 있는 이미지 (DESIGN.md §6, §8.5, D14·D31·D34).
 *
 * 두 부분으로 나눠서 준다. URL은 넣지 않는다(토큰 절약, 프론트가 카탈로그로 바꾼다).
 * 1. **활성 인물 변형 목록**: 본문에 `{{img:이름_변형}}`으로 넣을 수 있는 이름과 변형(D34).
 *    인물 이미지는 **그 인물이 말하는 자리 바로 앞**에 들어가야 하므로 AI가 본문에 직접 넣는다.
 *    첫 줄 `[인물: 이름/변형]` 태그(§5.3)는 본문에 태그가 없을 때 쓰는 폴백으로 남는다.
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
            """인물 이미지는 본문에 `{{img:이름_변형}}`을 **한 줄에 단독으로** 넣어 보여 줍니다(화면에서 이미지로 바뀝니다).
- **넣는 자리가 중요합니다.** 그 인물의 대사나 등장 묘사 **바로 앞 줄**에 넣으세요. 독자가 말하는 사람의 얼굴을 보면서 그 대사를 읽게 됩니다.
- **말하는 인물이 바뀌면 반드시 새로 넣습니다.** 앞에서 이미 이미지가 나온 인물이라도, 다른 인물이 말한 뒤에 다시 말하면 다시 넣습니다.
- 같은 인물이 연달아 말할 때만 생략합니다.
- 한 응답에 3개까지만 넣으세요. 화자가 네 번 이상 바뀌면 뒤쪽은 생략합니다.
- 아래 목록에 있는 이름과 변형만 쓰고, 태그는 밑줄로 이어 씁니다(목록에 `설월 — 기본, 분노`가 있으면 `{{img:설월_분노}}`).
- 변형은 그 대사를 말하는 순간의 감정에 가장 가까운 것을 고릅니다. 마땅한 변형이 없으면 `{{img:이름_기본}}`을 씁니다.
- 아래에 없는 인물, 주인공, 인물이 나오지 않는 장면에는 넣지 않습니다.
- 이미지 태그를 문장 안에 섞거나 이미지에 대해 설명하지 마세요."""

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
