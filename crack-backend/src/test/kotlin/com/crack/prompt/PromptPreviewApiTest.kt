package com.crack.prompt

import com.crack.global.config.DataPaths
import com.crack.message.service.MessageService
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
import java.util.UUID

/** `GET /api/stories/{id}/prompt-preview` (DESIGN.md §6.3) */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml의 비밀번호로 AuthFilter가 끼지 않게
class PromptPreviewApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private var storyId = 0L

    @BeforeEach
    fun setUp() {
        scenario = scenarioRepository.save(Scenario(name = "preview-${UUID.randomUUID().toString().take(8)}", title = "미리보기"))
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "스토리", dirName = "3000"))
        storyId = story.id
        SampleScenario.copyTo(dataPaths.storyDir(scenario.name, story.dirName))
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
    fun `섹션별 크기와 전문, 최종 메시지를 돌려준다`() {
        val u = messageService.appendUser(storyId, "안녕")
        messageService.appendAssistant(storyId, "응답", turnNo = u.turnNo)

        val body = preview(input = "설월에게 묻는다")

        assertThat(body["storyId"].asLong()).isEqualTo(storyId)
        val sections = body["sections"].toList()
        assertThat(sections.map { it["name"].asText() }).containsExactly("base", "world", "scenario", "protagonist", "characters")
        assertThat(sections.map { it["slot"].asText() }).containsExactly("BASE", "WORLD", "SCENARIO", "PROTAGONIST", "CHARACTERS")
        sections.forEach { assertThat(it["chars"].asInt()).isEqualTo(it["content"].asText().length) }
        assertThat(body["activeCharacters"].map { it.asText() }).containsExactly("설월")

        val systemPrompt = body["systemPrompt"].asText()
        assertThat(body["systemChars"].asInt()).isEqualTo(systemPrompt.length)
        val messages = body["messages"].toList()
        assertThat(messages.map { it["role"].asText() to it["content"].asText() }).containsExactly(
            "USER" to "안녕", "ASSISTANT" to "응답", "USER" to "설월에게 묻는다",
        )
        assertThat(body["messageChars"].asInt()).isEqualTo(2 + 2 + "설월에게 묻는다".length)
        assertThat(body["totalChars"].asInt()).isEqualTo(body["systemChars"].asInt() + body["messageChars"].asInt())
        assertThat(body["rawWindow"]["recordedThroughTurn"].asInt()).isEqualTo(0)
        assertThat(body["rawWindow"]["messageCount"].asInt()).isEqualTo(2)
    }

    @Test
    fun `가상 입력은 저장하지 않는다`() {
        preview(input = "저장되면 안 됨")
        assertThat(messageService.list(storyId)).isEmpty()
    }

    @Test
    fun `없는 스토리는 404`() {
        mockMvc.perform(get("/api/stories/999999/prompt-preview")).andExpect(status().isNotFound)
    }
}
