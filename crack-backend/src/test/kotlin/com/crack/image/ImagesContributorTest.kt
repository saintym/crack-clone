package com.crack.image

import com.crack.global.config.DataPathConfig
import com.crack.global.config.DataPaths
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptSlot
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

/** IMAGES 기여자 (DESIGN.md §6, §8.5) */
class ImagesContributorTest {

    @TempDir lateinit var root: Path

    private fun contributor(images: String?, max: Int = 50): Pair<ImagesContributor, PromptContext> {
        val dataPaths = DataPaths(DataPathConfig(dataPath = root.toString()))
        val scenario = Scenario(name = "무림", title = "무림")
        val story = Story(scenarioId = 0, title = "s", dirName = "1000")
        val storyDir = dataPaths.storyDir(scenario.name, story.dirName)
        Files.createDirectories(storyDir)
        if (images != null) Files.writeString(dataPaths.scenarioDir(scenario.name).resolve("images.md"), images)
        val storyDirs = mock<StoryDirs> {
            on { locate(7L) } doReturn StoryDirs.Location(story, scenario, storyDir)
        }
        val c = ImagesContributor(ImageCatalogService(storyDirs, dataPaths), ImageProperties(promptMaxEntries = max))
        return c to PromptContext(7L, storyDir, "", null, null)
    }

    @Test
    fun `시나리오 원본의 태그와 설명을 넣고 URL은 넣지 않는다`() {
        val (c, ctx) = contributor(
            """
            - 설월_미소: https://example.com/a.webp | 설월이 옅게 웃는 모습
            - 객잔: https://example.com/b.webp
            """.trimIndent(),
        )

        val text = c.contribute(ctx)!!

        assertThat(c.slot).isEqualTo(PromptSlot.IMAGES)
        assertThat(c.name).isEqualTo("images")
        assertThat(text).startsWith("=== 이미지 ===")
        assertThat(text).contains("{{img:태그}}", "감정", "- 설월_미소: 설월이 옅게 웃는 모습", "- 객잔")
        assertThat(text).doesNotContain("https://")
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
