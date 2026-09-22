package com.crack.prompt.contributor

import com.crack.memory.docs.ChronicleEntry
import com.crack.memory.docs.MemoryBudgets
import com.crack.memory.docs.StoryState
import com.crack.prompt.keyword.KeywordMatcher
import com.crack.story.files.SampleScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PromptContributorsTest {

    @TempDir lateinit var tmp: Path
    private lateinit var storyDir: Path

    private val selector = ActiveCharacterSelector(KeywordMatcher())

    @BeforeEach
    fun setUp() {
        storyDir = SampleScenario.copyTo(tmp.resolve("story"))
    }

    private fun ctx(recentText: String = "", turnInstruction: String? = null) =
        PromptContext(storyId = 1L, storyDir = storyDir, recentText = recentText, userInput = null, turnInstruction = turnInstruction)

    private fun names(recentText: String) = selector.select(ctx(recentText)).map { it.name }

    // ---- 활성 인물 (D9) ----

    @Test
    fun `언급도 동행도 없는 인물은 넣지 않는다`() {
        assertThat(names("객잔에 비가 내린다")).isEmpty()
        assertThat(CharactersContributor(selector).contribute(ctx("객잔에 비가 내린다"))).isNull()
    }

    @Test
    fun `파일명이나 별칭이 최근 대화에 나오면 넣는다`() {
        assertThat(names("설월이 고개를 들었다")).containsExactly("설월")
        assertThat(names("월아, 괜찮소?")).containsExactly("설월")
        assertThat(names("채주가 웃었다")).containsExactly("무극")
    }

    @Test
    fun `동행 인물은 언급이 없어도 넣고 동행이 먼저 온다`() {
        StoryState(companions = listOf("무극")).write(storyDir.resolve("state.json"))

        assertThat(names("")).containsExactly("무극")
        assertThat(names("설 소저, 이쪽입니다")).containsExactly("무극", "설월")
        assertThat(names("무극과 설월")).containsExactly("무극", "설월") // 중복은 한 번만
    }

    @Test
    fun `동행 이름이 별칭이면 그 인물을 쓰고 모르는 이름은 버린다`() {
        StoryState(companions = listOf("설 소저", "없는사람")).write(storyDir.resolve("state.json"))
        assertThat(names("")).containsExactly("설월")
    }

    @Test
    fun `주인공 이름이 나와도 인물 목록에는 넣지 않는다`() {
        assertThat(names("한유가 검을 뽑았다")).isEmpty()
    }

    @Test
    fun `state json이 깨져 있으면 동행 없이 키워드만 쓴다`() {
        Files.writeString(storyDir.resolve("state.json"), "{ not json")
        assertThat(names("무극")).containsExactly("무극")
    }

    @Test
    fun `인물 섹션은 인물마다 머리를 붙인 전문이다`() {
        val section = CharactersContributor(selector).contribute(ctx("설월과 무극"))!!
        // 키워드로 찾은 인물은 파일명 순(무극 < 설월)
        assertThat(section).startsWith("=== 캐릭터: 무극 ===\n# 캐릭터: 무극")
        assertThat(section).contains("\n\n=== 캐릭터: 설월 ===\n# 캐릭터: 설월")
    }

    // ---- 문서 기여자 ----

    @Test
    fun `BASE는 유저 입력 규칙과 감정 태그 출력 형식을 담는다`() {
        val base = BaseContributor().contribute(ctx())
        assertThat(base).contains("`**…**`").contains("상황 묘사").contains("`\"…\"`").contains("대사")
        assertThat(base).contains("[감정: (현재 감정 1~3개)]").contains("[지시]")
    }

    @Test
    fun `스토리 폴더의 문서를 머리와 함께 넣고 없으면 생략한다`() {
        assertThat(WorldContributor().contribute(ctx())).startsWith("=== 세계관 ===\n")
        assertThat(ScenarioContributor().contribute(ctx())).startsWith("=== 시나리오 ===\n# 시나리오")
        assertThat(ProtagonistContributor().contribute(ctx())).startsWith("=== 주인공(사용자) ===\n# 주인공")

        Files.delete(storyDir.resolve("world.md"))
        Files.writeString(storyDir.resolve("scenario.md"), "  \n")
        assertThat(WorldContributor().contribute(ctx())).isNull()
        assertThat(ScenarioContributor().contribute(ctx())).isNull()
    }

    @Test
    fun `유저노트는 user_note md, 없으면 옛 must_remember md`() {
        val contributor = UserNoteContributor()
        assertThat(contributor.contribute(ctx())).isNull()

        Files.createDirectories(storyDir.resolve("memory"))
        Files.writeString(storyDir.resolve("memory/must_remember.md"), "옛 노트")
        assertThat(contributor.contribute(ctx())).isEqualTo("=== 유저노트 ===\n옛 노트")

        Files.writeString(storyDir.resolve("user_note.md"), "새 노트\n")
        assertThat(contributor.contribute(ctx())).isEqualTo("=== 유저노트 ===\n새 노트")
    }

    @Test
    fun `이번 턴 지시는 BOTTOM에 들어가고 비면 생략한다`() {
        val contributor = TurnInstructionContributor()
        assertThat(contributor.slot).isEqualTo(PromptSlot.BOTTOM)
        assertThat(contributor.contribute(ctx(turnInstruction = "  더 짧게 "))).isEqualTo("더 짧게")
        assertThat(contributor.contribute(ctx(turnInstruction = " "))).isNull()
    }

    // ---- 연대기 ----

    @Test
    fun `연대기는 장 요약과 예산 안의 최근 회차를 오래된 것부터 넣는다`() {
        val a = "가".repeat(40)
        Files.writeString(
            storyDir.resolve("chronicle.md"),
            """
            # 연대기
            ## 장 요약
            오래전 이야기
            ## 회차 1 (턴 1–10)
            - 회차1 $a
            ## 회차 2 (턴 11–20)
            - 회차2 $a
            ## 회차 3 (턴 21–30)
            - 회차3 $a
            """.trimIndent(),
        )

        val section = ChronicleContributor(MemoryBudgets(chronicle = 130)).contribute(ctx())!!

        assertThat(section).startsWith("=== 연대기 (지금까지의 이야기) ===\n## 장 요약\n오래전 이야기")
        assertThat(section).doesNotContain("회차1").contains("회차2").contains("회차3")
        assertThat(section.indexOf("회차2")).isLessThan(section.indexOf("회차3"))
    }

    @Test
    fun `가장 최근 회차는 예산을 넘어도 넣는다`() {
        val entries = listOf(
            ChronicleEntry(1, 1, 10, "짧음"),
            ChronicleEntry(2, 11, 20, "길다".repeat(100)),
        )
        assertThat(ChronicleContributor.recentWithinBudget(entries, 10).map { it.number }).containsExactly(2)
        assertThat(ChronicleContributor.recentWithinBudget(entries, 10_000).map { it.number }).containsExactly(1, 2)
    }

    @Test
    fun `연대기가 없거나 비어 있으면 생략한다`() {
        val contributor = ChronicleContributor(MemoryBudgets())
        assertThat(contributor.contribute(ctx())).isNull()
        Files.writeString(storyDir.resolve("chronicle.md"), "# 연대기\n")
        assertThat(contributor.contribute(ctx())).isNull()
    }

    // ---- 이름 ----

    @Test
    fun `기본 섹션 이름은 클래스 이름을 snake_case로 바꾼 값이다`() {
        assertThat(BaseContributor().name).isEqualTo("base")
        assertThat(UserNoteContributor().name).isEqualTo("user_note")
        assertThat(TurnInstructionContributor().name).isEqualTo("turn_instruction")
        assertThat(PromptContributor.defaultName(KeywordBookContributor::class.java)).isEqualTo("keyword_book")
    }

    private class KeywordBookContributor
}
