package com.crack.prompt.contributor

import com.crack.memory.docs.StoryState
import com.crack.prompt.keyword.KeywordMatcher
import com.crack.story.files.SampleScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 활성 인물 선택 중 T50(D48) 부분 — `settings.json`의 `alwaysActive`와 한 글자 이름.
 *
 * 기본 규칙(동행 인물, 별칭, 상한)은 [PromptContributorsTest]에 있다.
 */
class ActiveCharacterSelectorTest {

    @TempDir lateinit var tmp: Path
    private lateinit var storyDir: Path

    private val selector = ActiveCharacterSelector(KeywordMatcher())

    @BeforeEach
    fun setUp() {
        storyDir = SampleScenario.copyTo(tmp.resolve("story"))
    }

    private fun names(recentText: String = "") = selector.select(storyDir, recentText).map { it.name }

    private fun settings(json: String) = Files.writeString(storyDir.resolve("settings.json"), json)

    /** 이름이 한 글자인 인물을 이 스토리에 추가한다. */
    private fun addRen() = Files.writeString(
        storyDir.resolve("characters").resolve("렌.md"),
        """
        # 캐릭터: 렌

        ## 기본 정보
        - **이름**: 렌
        - **별칭**: 검은 고양이
        """.trimIndent(),
    )

    // ---- alwaysActive (T50, D48) ----

    @Test
    fun `alwaysActive에 있는 인물은 언급이 없어도 넣는다`() {
        settings("""{"alwaysActive": ["설월"]}""")
        assertThat(names()).containsExactly("설월")
    }

    @Test
    fun `alwaysActive는 동행 인물 다음 키워드 매칭 앞에 들어간다`() {
        addRen()
        StoryState(companions = listOf("무극")).write(storyDir.resolve("state.json"))
        settings("""{"alwaysActive": ["렌"]}""")

        assertThat(names("설월이 고개를 들었다")).containsExactly("무극", "렌", "설월")
    }

    @Test
    fun `alwaysActive 이름이 별칭이어도 찾고 중복은 한 번만 넣는다`() {
        StoryState(companions = listOf("무극")).write(storyDir.resolve("state.json"))
        settings("""{"alwaysActive": ["설 소저", "채주"]}""")

        assertThat(names()).containsExactly("무극", "설월")
    }

    @Test
    fun `alwaysActive의 모르는 이름은 버린다`() {
        settings("""{"alwaysActive": ["없는사람", "  설월  ", ""]}""")
        assertThat(names()).containsExactly("설월")
    }

    @Test
    fun `alwaysActive가 없거나 파일이 깨져도 예전 동작 그대로다`() {
        assertThat(names()).isEmpty()
        assertThat(names("설월이 웃었다")).containsExactly("설월")

        settings("""{"responseChars": {"min": 900, "max": 1600}}""")
        assertThat(names()).isEmpty()

        settings("{ 깨진 json")
        assertThat(names("설월이 웃었다")).containsExactly("설월")
    }

    @Test
    fun `alwaysActive도 활성 인물 상한에 걸린다`() {
        val selectorWithMax1 = ActiveCharacterSelector(
            KeywordMatcher(),
            com.crack.prompt.config.PromptProperties(maxActiveCharacters = 1),
        )
        settings("""{"alwaysActive": ["설월"]}""")
        assertThat(selectorWithMax1.select(storyDir, "무극이 웃었다").map { it.name }).containsExactly("설월")
    }

    // ---- 한 글자 이름 (T50, D48) ----

    @Test
    fun `이름이 한 글자인 인물도 낱말 처음에 나오면 넣는다`() {
        addRen()
        assertThat(names("렌이 문을 열었다")).containsExactly("렌")
        assertThat(names("사쿠라와 렌은 마주 섰다")).containsExactly("렌")
        assertThat(names("\"렌.\" 하고 불렀다")).containsExactly("렌")
    }

    @Test
    fun `한 글자 이름이 낱말 안쪽에 우연히 들어도 넣지 않는다`() {
        addRen()
        assertThat(names("카메라 렌즈처럼 차가운 눈")).containsExactly("렌") // 남는 오탐: 그 글자로 시작하는 낱말
        assertThat(names("훈련은 계속됐다")).isEmpty()
        assertThat(names("그가 마련한 자리였다")).isEmpty()
    }
}
