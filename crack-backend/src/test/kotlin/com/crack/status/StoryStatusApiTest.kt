package com.crack.status

import com.crack.global.config.DataPaths
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.files.SampleScenario
import com.crack.story.repository.StoryRepository
import com.fasterxml.jackson.databind.JsonNode
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

/** `GET /api/stories/{id}/status` (DESIGN.md §7.5). JSON 필드 이름과 형식을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml의 비밀번호로 AuthFilter가 끼지 않게
class StoryStatusApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private lateinit var storyDir: Path
    private var storyId = 0L

    @BeforeEach
    fun setUp() {
        scenario = scenarioRepository.save(Scenario(name = "status-${UUID.randomUUID().toString().take(8)}", title = "상태"))
        val story = storyRepository.save(
            Story(scenarioId = scenario.id, title = "스토리", dirName = "6000", recordedThroughTurn = 30),
        )
        storyId = story.id
        storyDir = SampleScenario.copyTo(dataPaths.storyDir(scenario.name, story.dirName))
    }

    @AfterEach
    fun tearDown() {
        val dir = dataPaths.scenarioDir(scenario.name)
        if (Files.exists(dir)) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun appendMemory(name: String, body: String) {
        val path = storyDir.resolve("characters/$name.md")
        Files.writeString(path, Files.readString(path).trimEnd() + "\n\n" + body + "\n")
    }

    private fun getStatus(): JsonNode {
        val body = mockMvc.perform(get("/api/stories/$storyId/status"))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        return objectMapper.readTree(body)
    }

    @Test
    fun `기억 문서를 JSON으로 돌려준다`() {
        Files.writeString(
            storyDir.resolve("state.json"),
            """{"companions": ["월아"], "location": "흑풍채 근처 숲", "time": "3일차 밤", "updatedAtTurn": 30}""",
        )
        appendMemory("protagonist", "### 관계\n- 설월: 가까워짐 (t21)\n### 스탯·기술\n- 검기 발현 (t25)\n### 소지품\n### 신체\n")
        appendMemory("무극", "### 관계\n- 주인공: 경계함 (t3)\n")
        appendMemory("설월", "### 관계\n- 주인공: 신뢰 (t21)\n### 사건\n- t18–21: 습격\n### 소지품·기술·신체\n- 옥패 (t21)\n")

        val json = getStatus()

        assertThat(json["recordedThroughTurn"].asInt()).isEqualTo(30)
        assertThat(json["state"]["companions"].map { it.asText() }).containsExactly("월아")
        assertThat(json["state"]["location"].asText()).isEqualTo("흑풍채 근처 숲")
        assertThat(json["state"]["time"].asText()).isEqualTo("3일차 밤")
        assertThat(json["state"]["updatedAtTurn"].asInt()).isEqualTo(30)

        val p = json["protagonist"]
        assertThat(p["name"].asText()).isEqualTo("한유")
        assertThat(p["relations"][0]["target"].asText()).isEqualTo("설월")
        assertThat(p["relations"][0]["description"].asText()).isEqualTo("가까워짐 (t21)")
        assertThat(p["relations"][0]["turns"].map { it.asInt() }).containsExactly(21)
        assertThat(p["statsAndSkills"][0]["text"].asText()).isEqualTo("검기 발현 (t25)")
        assertThat(p["possessions"].isArray && p["possessions"].isEmpty).isTrue()
        assertThat(p["body"].isArray && p["body"].isEmpty).isTrue()

        val characters = json["characters"]
        assertThat(characters.map { it["name"].asText() }).containsExactly("설월", "무극") // 동행(별칭 "월아") 먼저
        val seol = characters[0]
        assertThat(seol["companion"].asBoolean()).isTrue()
        assertThat(seol["events"][0]["fromTurn"].asInt()).isEqualTo(18)
        assertThat(seol["events"][0]["toTurn"].asInt()).isEqualTo(21)
        assertThat(seol["events"][0]["description"].asText()).isEqualTo("습격")
        assertThat(seol["possessions"][0]["text"].asText()).isEqualTo("옥패 (t21)")
        assertThat(characters[1]["companion"].asBoolean()).isFalse()
        assertThat(characters[1]["events"].isEmpty).isTrue()
    }

    @Test
    fun `기록 전에는 빈 상태와 빈 주인공이고 인물은 없다`() {
        val json = getStatus()

        assertThat(json["state"]["companions"].isEmpty).isTrue()
        assertThat(json["state"]["location"].isNull).isTrue()
        assertThat(json["protagonist"]["name"].asText()).isEqualTo("한유")
        assertThat(json["characters"].isEmpty).isTrue()
    }

    @Test
    fun `스토리 폴더만 읽는다 - 시나리오 원본에 기억이 있어도 보이지 않는다`() {
        val original = SampleScenario.copyTo(dataPaths.scenarioDir(scenario.name))
        val path = original.resolve("characters/무극.md")
        Files.writeString(path, Files.readString(path).trimEnd() + "\n\n### 관계\n- 주인공: 원본에만 있음\n")

        assertThat(getStatus()["characters"].isEmpty).isTrue()
    }

    @Test
    fun `없는 스토리는 404`() {
        mockMvc.perform(get("/api/stories/999999/status")).andExpect(status().isNotFound)
    }
}
