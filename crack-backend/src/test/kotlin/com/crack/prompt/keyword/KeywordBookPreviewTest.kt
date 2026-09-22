package com.crack.prompt.keyword

import com.crack.global.config.DataPaths
import com.crack.message.service.MessageService
import com.crack.prompt.service.PromptAssembler
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.files.SampleScenario
import com.crack.story.repository.StoryRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 키워드북이 실제 조립과 `prompt-preview`에 들어가는지 (DESIGN.md §6.3, §8.3).
 * 스토리 폴더는 `fixtures/sample-scenario`(키워드북: 흑풍채, 청운객잔)를 복사해 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml의 비밀번호로 AuthFilter가 끼지 않게
class KeywordBookPreviewTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var assembler: PromptAssembler
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private lateinit var storyDir: Path
    private var storyId = 0L

    @BeforeEach
    fun setUp() {
        scenario = scenarioRepository.save(Scenario(name = "keyword-${UUID.randomUUID().toString().take(8)}", title = "키워드"))
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "스토리", dirName = "4000"))
        storyId = story.id
        storyDir = SampleScenario.copyTo(dataPaths.storyDir(scenario.name, story.dirName))
    }

    @AfterEach
    fun tearDown() {
        val dir = dataPaths.scenarioDir(scenario.name)
        if (Files.exists(dir)) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun preview(input: String? = null) =
        mockMvc.perform(get("/api/stories/$storyId/prompt-preview").apply { if (input != null) param("input", input) })
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
            .let { objectMapper.readTree(it) }

    @Test
    fun `발동한 키워드는 KEYWORDS 섹션과 activeKeywords에 파일 순서로 나온다`() {
        val body = preview(input = "객잔 주인에게 산적 이야기를 묻는다")

        assertThat(body["activeKeywords"].map { it.asText() }).containsExactly("흑풍채", "청운객잔")
        val section = body["sections"].single { it["name"].asText() == "keyword_book" }
        assertThat(section["slot"].asText()).isEqualTo("KEYWORDS")
        assertThat(section["content"].asText()).startsWith("=== 키워드 설정 ===\n### 흑풍채\n")
        assertThat(section["content"].asText()).contains("### 청운객잔\n교역로 한가운데의 객잔.")

        val names = body["sections"].map { it["name"].asText() }
        assertThat(names.indexOf("keyword_book")).isGreaterThan(names.indexOf("protagonist"))
        assertThat(body["systemPrompt"].asText()).contains("=== 키워드 설정 ===")
    }

    @Test
    fun `최근 대화에서 언급된 키워드도 발동하고, 언급이 없으면 섹션과 목록이 비어 있다`() {
        assertThat(preview(input = "설월에게 인사한다")["activeKeywords"]).isEmpty()
        assertThat(preview(input = "설월에게 인사한다")["sections"].map { it["name"].asText() }).doesNotContain("keyword_book")

        val u = messageService.appendUser(storyId, "흑풍채가 어디 있지?")
        messageService.appendAssistant(storyId, "북쪽 산맥입니다.", turnNo = u.turnNo)

        assertThat(preview(input = "그럼 가 보자")["activeKeywords"].map { it.asText() }).containsExactly("흑풍채")
    }

    @Test
    fun `키워드북 파일이 없어도 조립은 된다`() {
        Files.delete(storyDir.resolve("keywords.md"))

        val p = assembler.assemble(storyId, pendingInput = "산적이 나타났다")

        assertThat(p.activeKeywords).isEmpty()
        assertThat(p.sections.map { it.name }).doesNotContain("keyword_book").contains("base")
    }
}
