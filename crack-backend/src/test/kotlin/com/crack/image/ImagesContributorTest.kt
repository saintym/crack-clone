package com.crack.image

import com.crack.global.config.DataPathConfig
import com.crack.global.config.DataPaths
import com.crack.prompt.contributor.ActiveCharacterSelector
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptSlot
import com.crack.prompt.keyword.KeywordMatcher
import com.crack.scenario.entity.Scenario
import com.crack.story.entity.Story
import com.crack.story.files.StoryDirs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.nio.file.Files
import java.nio.file.Path

/** IMAGES 기여자 (DESIGN.md §6, §8.5, D31) */
class ImagesContributorTest {

    @TempDir lateinit var root: Path

    /**
     * @param characters 스토리 폴더에 만들 인물 문서 이름
     * @param recentText 활성 인물 선택에 쓰는 최근 대화
     */
    private fun contributor(
        images: String?,
        max: Int = 50,
        characters: List<String> = emptyList(),
        recentText: String = "",
    ): Pair<ImagesContributor, PromptContext> {
        val dataPaths = DataPaths(DataPathConfig(dataPath = root.toString()))
        val scenario = Scenario(name = "무림", title = "무림")
        val story = Story(scenarioId = 0, title = "s", dirName = "1000")
        val storyDir = dataPaths.storyDir(scenario.name, story.dirName)
        Files.createDirectories(storyDir.resolve("characters"))
        characters.forEach { Files.writeString(storyDir.resolve("characters/$it.md"), "# 캐릭터: $it\n") }
        if (images != null) Files.writeString(dataPaths.scenarioDir(scenario.name).resolve("images.md"), images)
        val storyDirs = mock<StoryDirs> {
            on { locate(7L) } doReturn StoryDirs.Location(story, scenario, storyDir)
        }
        val c = ImagesContributor(
            ImageCatalogService(storyDirs, dataPaths),
            ImageProperties(promptMaxEntries = max),
            ActiveCharacterSelector(KeywordMatcher()),
        )
        return c to PromptContext(7L, storyDir, recentText, null, null)
    }

    @Test
    fun `장면 태그와 설명을 넣고 URL은 넣지 않는다`() {
        val (c, ctx) = contributor(
            """
            - 흑풍채_전경: https://example.com/a.webp | 산 중턱의 산적 소굴
            - 객잔: https://example.com/b.webp
            """.trimIndent(),
        )

        val text = c.contribute(ctx)!!

        assertThat(c.slot).isEqualTo(PromptSlot.IMAGES)
        assertThat(c.name).isEqualTo("images")
        assertThat(text).startsWith("=== 이미지 ===")
        assertThat(text).contains("{{img:태그}}", "- 흑풍채_전경: 산 중턱의 산적 소굴", "- 객잔")
        assertThat(text).doesNotContain("https://")
    }

    @Test
    fun `활성 인물의 변형 목록을 준다`() {
        val (c, ctx) = contributor(
            """
            - 설월_기본: https://example.com/1.webp
            - 설월_당황: https://example.com/2.webp | 눈을 크게 뜬 모습
            - 무극_기본: https://example.com/3.webp
            - 객잔_밤: https://example.com/4.webp | 비 내리는 밤
            """.trimIndent(),
            characters = listOf("설월", "무극"),
            recentText = "설월이 조용히 걸어왔다",
        )

        val text = c.contribute(ctx)!!

        assertThat(text).contains("[인물: 이름/변형]", "- 설월 — 기본, 당황(눈을 크게 뜬 모습)")
        // 활성 인물이 아닌 무극은 넣지 않는다
        assertThat(text).doesNotContain("무극")
        // 인물 이미지는 장면 태그 목록에 넣지 않는다(본문에 직접 쓰지 못하게)
        assertThat(text).doesNotContain("- 설월_기본", "- 무극_기본")
        assertThat(text).contains("- 객잔_밤: 비 내리는 밤")
    }

    @Test
    fun `변형 목록은 기본을 먼저 쓴다`() {
        val (c, ctx) = contributor(
            """
            - 설월_분노: https://example.com/1.webp
            - 설월_기본: https://example.com/2.webp
            """.trimIndent(),
            characters = listOf("설월"),
            recentText = "설월",
        )

        assertThat(c.contribute(ctx)!!).contains("- 설월 — 기본, 분노")
    }

    @Test
    fun `카탈로그에 항목이 없는 활성 인물은 목록에서 뺀다`() {
        val (c, ctx) = contributor(
            "- 객잔_밤: https://example.com/4.webp",
            characters = listOf("설월"),
            recentText = "설월이 왔다",
        )

        val text = c.contribute(ctx)!!
        assertThat(text).doesNotContain("설월", "[인물:")
        assertThat(text).contains("- 객잔_밤")
    }

    @Test
    fun `스토리 폴더에 images_md가 있어도 원본만 본다`() {
        val (c, ctx) = contributor(null)
        Files.writeString(ctx.storyDir.resolve("images.md"), "- 복사본: https://example.com/x.webp")

        assertThat(c.contribute(ctx)).isNull()
    }

    @Test
    fun `파일이 없거나 항목이 없으면 섹션을 생략한다`() {
        assertThat(contributor(null).let { (c, ctx) -> c.contribute(ctx) }).isNull()
        assertThat(contributor("# 비어 있음\n").let { (c, ctx) -> c.contribute(ctx) }).isNull()
    }

    @Test
    fun `최대 개수만큼 위에서부터 넣는다`() {
        val images = (1..5).joinToString("\n") { "- t$it: https://example.com/$it.webp" }
        val text = contributor(images, max = 2).let { (c, ctx) -> c.contribute(ctx)!! }

        assertThat(text).contains("- t1", "- t2").doesNotContain("- t3")
        assertThat(ImagesContributor.render(ImageCatalogParser.parse(images), 0)).isNull()
    }
}
