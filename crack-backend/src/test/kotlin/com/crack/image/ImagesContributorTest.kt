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

/** IMAGES 기여자 (DESIGN.md §6, §8.5, D31·D34, T40) */
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

        // 본문에 직접 넣는 방법을 안내한다(D34)
        assertThat(text).contains("{{img:이름_변형}}", "기본, 당황(눈을 크게 뜬 모습)")
        // 변형은 감정만이 아니라 상황·자세일 수도 있다(T40)
        assertThat(text).contains("상황이나 자세")
        // 활성 인물이 아닌 무극은 넣지 않는다
        assertThat(text).doesNotContain("무극")
        // 변형 목록은 인물별 한 줄이다. 장면 태그처럼 낱개로 늘어놓지 않는다
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

        assertThat(c.contribute(ctx)!!).contains("설월", "기본, 분노")
    }

    @Test
    fun `카탈로그에 항목이 없는 활성 인물은 목록에서 뺀다`() {
        val (c, ctx) = contributor(
            "- 객잔_밤: https://example.com/4.webp",
            characters = listOf("설월"),
            recentText = "설월이 왔다",
        )

        val text = c.contribute(ctx)!!
        assertThat(text).doesNotContain("설월", "{{img:이름_변형}}")
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

    // ── 예산 순서 (T40) ──

    @Test
    fun `예산은 걸른 뒤에 쓴다 - 파일 뒤쪽 활성 인물의 변형도 들어간다`() {
        // 앞쪽에 비활성 인물 변형이 예산보다 많고, 활성 인물은 파일 맨 뒤에 있다
        val images = (1..10).joinToString("\n") { "- 무극_v$it: https://example.com/$it.webp" } +
            "\n- 설월_기본: https://example.com/s.webp"
        val (c, ctx) = contributor(
            images,
            max = 5,
            characters = listOf("무극", "설월"),
            recentText = "설월이 조용히 걸어왔다",
        )

        val text = c.contribute(ctx)!!

        // 자르기를 먼저 했다면 설월은 예산 안에 들어오지 못했다
        assertThat(text).contains("설월")
        assertThat(text).doesNotContain("무극")
    }

    @Test
    fun `인물 변형이 예산을 먼저 쓰고 남은 것을 장면 태그가 쓴다`() {
        val images = listOf(
            "- 객잔_밤: https://example.com/1.webp",
            "- 객잔_낮: https://example.com/2.webp",
            "- 설월_기본: https://example.com/3.webp",
            "- 설월_분노: https://example.com/4.webp",
        ).joinToString("\n")
        val (c, ctx) = contributor(images, max = 3, characters = listOf("설월"), recentText = "설월")

        val text = c.contribute(ctx)!!

        assertThat(text).contains("설월", "기본, 분노")
        // 인물이 2칸을 쓰고 남은 1칸만 장면 태그가 쓴다
        assertThat(text).contains("- 객잔_밤").doesNotContain("- 객잔_낮")
    }

    // ── 한 응답 상한 (T40) ──

    @Test
    fun `한 응답 상한을 안내 문구에 넣는다`() {
        assertThat(ImagesContributor.characterGuide(3)).contains("한 응답에 3개까지만")
        assertThat(ImagesContributor.characterGuide(8)).contains("한 응답에 8개까지만")
    }

    @Test
    fun `스토리 설정이 한 응답 상한을 이긴다`() {
        val (c, ctx) = contributor(
            "- 설월_기본: https://example.com/1.webp",
            characters = listOf("설월"),
            recentText = "설월",
        )
        Files.writeString(ctx.storyDir.resolve("settings.json"), "{\"maxCharacterImages\": 7}")

        assertThat(c.contribute(ctx)!!).contains("한 응답에 7개까지만")
    }

    @Test
    fun `범위를 벗어난 설정은 무시하고 전역 기본값을 쓴다`() {
        val (c, ctx) = contributor(
            "- 설월_기본: https://example.com/1.webp",
            characters = listOf("설월"),
            recentText = "설월",
        )
        Files.writeString(ctx.storyDir.resolve("settings.json"), "{\"maxCharacterImages\": 999}")

        assertThat(c.contribute(ctx)!!).contains("한 응답에 3개까지만")
    }

    // ── 변형 집합이 같은 인물 묶기 (T44) ──

    @Test
    fun `변형이 같은 인물은 한 줄로 묶고 변형을 한 번만 나열한다`() {
        val variants = listOf("기본", "미소", "분노", "당황")
        val images = listOf("가가", "나나", "다다").flatMap { name ->
            variants.map { "- ${name}_$it: https://example.com/$name-$it.webp | $it" }
        }.joinToString("\n")
        val (c, ctx) = contributor(
            images,
            max = 50,
            characters = listOf("가가", "나나", "다다"),
            recentText = "가가 나나 다다 가 모두 나왔다",
        )

        val text = c.contribute(ctx)!!

        assertThat(text).contains("쓸 수 있는 인물: 가가, 나나, 다다")
        // 설명이 변형 이름과 같으면 되풀이라 빠진다
        assertThat(text).contains("이 인물들은 모두 같은 변형을 가진다: 기본, 미소, 분노, 당황")
        // 변형 목록이 인물마다 되풀이되지 않는다
        assertThat(text.split("미소").size - 1).isEqualTo(1)
    }

    @Test
    fun `변형 집합이 다르면 집합마다 한 줄이 된다`() {
        val images = listOf(
            "- 가가_기본: https://example.com/1.webp",
            "- 가가_미소: https://example.com/2.webp",
            "- 나나_기본: https://example.com/3.webp",
            "- 나나_미소: https://example.com/4.webp",
            "- 다다_기본: https://example.com/5.webp",
        ).joinToString("\n")
        val (c, ctx) = contributor(
            images,
            max = 50,
            characters = listOf("가가", "나나", "다다"),
            recentText = "가가 나나 다다 가 모두 나왔다",
        )

        val text = c.contribute(ctx)!!

        // 같은 집합인 가가·나나가 한 줄로 묶이고, 다다는 따로 나온다
        assertThat(text).contains("- 가가, 나나 — 기본, 미소")
        assertThat(text).contains("- 다다 — 기본")
        assertThat(text).contains("쓸 수 있는 인물과 변형:")
    }

    @Test
    fun `묶으면 같은 예산으로 훨씬 많은 인물을 담는다`() {
        val variants = (1..19).map { if (it == 1) "기본" else "v$it" }
        val names = (1..6).map { "인물$it" }
        val images = names.flatMap { name ->
            variants.map { "- ${name}_$it: https://example.com/$name-$it.webp" }
        }.joinToString("\n")
        // 예산 20이면 예전 방식으로는 한 인물(19변형)밖에 못 담는다
        val (c, ctx) = contributor(
            images,
            max = 20,
            characters = names,
            recentText = names.joinToString(" "),
        )

        val text = c.contribute(ctx)!!

        // 여섯 명 전부 이름이 들어가고 변형은 한 번만 나열된다
        names.forEach { assertThat(text).contains(it) }
        assertThat(text.split("v19").size - 1).isEqualTo(1)
    }
}
