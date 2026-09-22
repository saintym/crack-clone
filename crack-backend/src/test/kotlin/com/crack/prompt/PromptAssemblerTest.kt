package com.crack.prompt

import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole as AiRole
import com.crack.chat.flow.ConversationBuilder
import com.crack.global.config.DataPaths
import com.crack.memory.docs.StoryState
import com.crack.message.entity.MessageKind
import com.crack.message.service.MessageService
import com.crack.prompt.context.RecordedTurnSource
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptContributor
import com.crack.prompt.contributor.PromptSlot
import com.crack.prompt.service.PromptAssembler
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.files.SampleScenario
import com.crack.story.repository.StoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 프롬프트 조립 v2 통합 테스트 (DESIGN.md §6). 스토리 폴더는 `fixtures/sample-scenario`(설월·무극·주인공)를 복사해 쓴다.
 *
 * [PromptTestConfig]가 T14·T16 자리를 흉내 낸다: 마지막 기록 턴([RecordedTurnSource])과 지속 지시(BOTTOM order 0).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PromptAssemblerTest.PromptTestConfig::class)
class PromptAssemblerTest {

    @TestConfiguration
    class PromptTestConfig {
        @Bean fun recordedTurns() = MutableRecordedTurns()
        @Bean fun directives() = TestDirectiveContributor()
    }

    class MutableRecordedTurns : RecordedTurnSource {
        val values = ConcurrentHashMap<Long, Int>()
        override fun recordedThroughTurn(storyId: Long) = values[storyId] ?: 0
    }

    /** T16 지속 지시 기여자 흉내. [text]가 null이면 생략한다. */
    class TestDirectiveContributor : PromptContributor {
        @Volatile var text: String? = null
        override val slot = PromptSlot.BOTTOM
        override val order = 0
        override fun contribute(ctx: PromptContext) = text
    }

    @Autowired lateinit var assembler: PromptAssembler
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths
    @Autowired lateinit var recordedTurns: MutableRecordedTurns
    @Autowired lateinit var directives: TestDirectiveContributor

    private lateinit var scenario: Scenario
    private lateinit var storyDir: Path
    private var storyId = 0L

    @BeforeEach
    fun setUp() {
        scenario = scenarioRepository.save(Scenario(name = "prompt-${UUID.randomUUID().toString().take(8)}", title = "프롬프트"))
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "스토리", dirName = "2000"))
        storyId = story.id
        storyDir = SampleScenario.copyTo(dataPaths.storyDir(scenario.name, story.dirName))
    }

    @AfterEach
    fun tearDown() {
        directives.text = null
        recordedTurns.values.clear()
        val dir = dataPaths.scenarioDir(scenario.name)
        if (Files.exists(dir)) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun turn(user: String, assistant: String = "응답") {
        val u = messageService.appendUser(storyId, user)
        messageService.appendAssistant(storyId, assistant, turnNo = u.turnNo)
    }

    @Test
    fun `시스템 프롬프트는 슬롯 순서대로 스토리 폴더 문서를 담는다`() {
        messageService.appendUser(storyId, "설월에게 말을 건다")

        val p = assembler.assemble(storyId)

        assertThat(p.sections.map { it.name }).containsExactly("base", "world", "scenario", "protagonist", "characters")
        val s = p.systemPrompt
        val order = listOf("당신은 몰입형", "=== 세계관 ===", "=== 시나리오 ===", "=== 주인공(사용자) ===", "=== 캐릭터: 설월 ===")
        assertThat(order.map { s.indexOf(it) }).allMatch { it >= 0 }.isSorted
        assertThat(p.systemChars).isEqualTo(s.length)
    }

    @Test
    fun `언급 없는 인물은 빼고 최근 메시지나 이번 입력에 나온 인물만 넣는다`() {
        messageService.appendUser(storyId, "객잔에 들어선다")
        assertThat(assembler.assemble(storyId).activeCharacters).isEmpty()
        assertThat(assembler.assemble(storyId).systemPrompt).doesNotContain("=== 캐릭터:")

        // 별칭(월아)으로도 찾는다. 이번 입력(가상)
        val p = assembler.assemble(storyId, pendingInput = "월아, 이쪽이오")
        assertThat(p.activeCharacters).containsExactly("설월")
        assertThat(p.systemPrompt).contains("=== 캐릭터: 설월 ===").doesNotContain("=== 캐릭터: 무극 ===")
        // 주인공은 언제나 들어간다
        assertThat(p.systemPrompt).contains("=== 주인공(사용자) ===")
    }

    @Test
    fun `동행 인물은 언급이 없어도 넣는다`() {
        StoryState(companions = listOf("무극")).write(storyDir.resolve("state.json"))
        messageService.appendUser(storyId, "설 소저를 부른다")

        assertThat(assembler.assemble(storyId).activeCharacters).containsExactly("무극", "설월")
    }

    @Test
    fun `키워드 스캔은 최근 N개 메시지와 이번 입력만 본다`() {
        turn("무극이 나타났다") // 턴 1: 오래된 언급
        repeat(3) { turn("조용한 밤", "바람") } // 6개 메시지
        messageService.appendUser(storyId, "길을 재촉한다")

        // 이번 입력을 뺀 최근 6개 = 턴 2~4. 턴 1의 무극은 범위 밖
        assertThat(assembler.assemble(storyId).activeCharacters).isEmpty()
        // 원문에는 여전히 있다(원문 범위와 스캔 범위는 별개)
        assertThat(assembler.assemble(storyId).messages.first().content).isEqualTo("무극이 나타났다")
    }

    @Test
    fun `BOTTOM은 마지막 유저 메시지 앞에 지시 블록으로 붙는다`() {
        turn("안녕")
        messageService.appendUser(storyId, "문을 연다")
        directives.text = "다음 지시는 해제될 때까지 항상 지켜라:\n1. 반말"

        val p = assembler.assemble(storyId, turnInstruction = "대사 위주로")

        assertThat(p.messages.last()).isEqualTo(
            ChatMessage(AiRole.USER, "[지시]\n다음 지시는 해제될 때까지 항상 지켜라:\n1. 반말\n\n대사 위주로\n\n문을 연다"),
        )
        assertThat(p.systemPrompt).doesNotContain("[지시]\n").doesNotContain("반말").doesNotContain("대사 위주로")
        assertThat(p.sections.filter { it.slot == PromptSlot.BOTTOM }.map { it.name })
            .containsExactly("test_directive", "turn_instruction")
    }

    @Test
    fun `마지막이 ASSISTANT면 지시만 담은 USER 메시지를 덧붙인다`() {
        turn("안녕", "첫 응답")

        val p = assembler.assemble(storyId, turnInstruction = ConversationBuilder.CONTINUE_INSTRUCTION)

        assertThat(p.messages.map { it.role }).containsExactly(AiRole.USER, AiRole.ASSISTANT, AiRole.USER)
        assertThat(p.messages.last().content).isEqualTo("[지시]\n${ConversationBuilder.CONTINUE_INSTRUCTION}")
    }

    @Test
    fun `지시가 없으면 지시 블록도 없다`() {
        messageService.appendUser(storyId, "문을 연다")
        assertThat(assembler.assemble(storyId).messages).containsExactly(ChatMessage(AiRole.USER, "문을 연다"))
    }

    @Test
    fun `원문은 마지막 기록 턴 - overlap 이후만 넣는다`() {
        messageService.appendAssistant(storyId, "프롤로그", kind = MessageKind.PROLOGUE)
        (1..12).forEach { turn("u$it", "a$it") }

        val before = assembler.assemble(storyId)
        assertThat(before.messages.first().content).isEqualTo("프롤로그")
        assertThat(before.rawMessageCount).isEqualTo(25)

        recordedTurns.values[storyId] = 10
        val after = assembler.assemble(storyId)
        assertThat(after.messages.first().content).isEqualTo("u9")
        assertThat(after.rawWindow.recordedThroughTurn).isEqualTo(10)
        assertThat(after.rawWindow.afterTurn).isEqualTo(8)
        assertThat(after.rawMessageCount).isEqualTo(8)
    }

    @Test
    fun `연대기와 유저노트는 제 슬롯에 들어간다`() {
        Files.writeString(storyDir.resolve("chronicle.md"), "# 연대기\n## 회차 1 (턴 1–10)\n- 흑풍채 습격\n")
        Files.writeString(storyDir.resolve("user_note.md"), "설월은 검을 못 쓴다")
        messageService.appendUser(storyId, "간다")

        val p = assembler.assemble(storyId)

        assertThat(p.sections.map { it.name }).containsExactly("base", "world", "scenario", "chronicle", "protagonist", "user_note")
        assertThat(p.systemPrompt.indexOf("흑풍채 습격")).isGreaterThan(p.systemPrompt.indexOf("=== 시나리오 ==="))
    }
}
