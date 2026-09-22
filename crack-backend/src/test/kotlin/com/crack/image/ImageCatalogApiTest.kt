package com.crack.image

import com.crack.global.config.DataPaths
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

/** `GET /api/stories/{id}/images`와 프롬프트 IMAGES 섹션 (DESIGN.md §8.5) */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml의 비밀번호로 AuthFilter가 끼지 않게
class ImageCatalogApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private var storyId = 0L

    @BeforeEach
    fun setUp() {
        scenario = scenarioRepository.save(Scenario(name = "images-${UUID.randomUUID().toString().take(8)}", title = "이미지"))
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "스토리", dirName = "4000"))
        storyId = story.id
        SampleScenario.copyTo(dataPaths.storyDir(scenario.name, story.dirName))
    }

    @AfterEach
    fun tearDown() {
        val dir = dataPaths.scenarioDir(scenario.name)
        if (Files.exists(dir)) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun getJson(path: String) =
        mockMvc.perform(get(path)).andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
            .let { objectMapper.readTree(it) }

    private fun writeCatalog(text: String) =
        Files.writeString(dataPaths.scenarioDir(scenario.name).resolve("images.md"), text)

    @Test
    fun `시나리오 원본 카탈로그를 파일 순서로 돌려준다`() {
        writeCatalog(
            """
            # 이미지
            - 설월_미소: https://example.com/a.webp | 설월이 옅게 웃는 모습
            - 나쁜것: javascript:alert(1)
            - 객잔: https://example.com/b.webp
            """.trimIndent(),
        )

        val body = getJson("/api/stories/$storyId/images")

        assertThat(body.map { it["tag"].asText() }).containsExactly("설월_미소", "객잔")
        assertThat(body[0]["url"].asText()).isEqualTo("https://example.com/a.webp")
        assertThat(body[0]["description"].asText()).isEqualTo("설월이 옅게 웃는 모습")
        assertThat(body[1]["description"].asText()).isEmpty()
    }

    @Test
    fun `카탈로그가 없으면 빈 목록, 스토리가 없으면 404`() {
        assertThat(getJson("/api/stories/$storyId/images").size()).isZero()
        mockMvc.perform(get("/api/stories/999999/images")).andExpect(status().isNotFound)
    }

    @Test
    fun `프롬프트 미리보기에 IMAGES 섹션이 들어간다`() {
        writeCatalog("- 설월_미소: https://example.com/a.webp | 설월이 옅게 웃는 모습")

        val sections = getJson("/api/stories/$storyId/prompt-preview")["sections"]
        val images = sections.single { it["name"].asText() == "images" }

        assertThat(images["slot"].asText()).isEqualTo("IMAGES")
        assertThat(images["content"].asText()).contains("{{img:태그}}", "- 설월_미소: 설월이 옅게 웃는 모습")
        assertThat(images["content"].asText()).doesNotContain("https://example.com")
        // 시스템 프롬프트의 마지막 섹션이다(USER_NOTE 뒤)
        assertThat(sections.last { it["slot"].asText() != "BOTTOM" }["name"].asText()).isEqualTo("images")
    }
}
