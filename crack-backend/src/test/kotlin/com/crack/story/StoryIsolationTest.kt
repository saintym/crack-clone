package com.crack.story

import com.crack.global.config.DataPaths
import com.crack.prompt.service.LegacyPromptAssembler
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.dto.StoryCreateRequest
import com.crack.story.entity.Story
import com.crack.story.files.SampleScenario
import com.crack.story.repository.StoryRepository
import com.crack.story.service.StoryService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 스토리 격리(D12, critical) 통합 테스트. 시나리오 원본은 `fixtures/sample-scenario`를 임시 데이터 폴더에 복사해 쓴다.
 *
 * 로컬에 gitignore된 `application.yml`이 있으면 `crack.auth.password`가 설정되어 API가 401을 돌려준다.
 * 리모트 환경과 같은 조건이 되도록 비밀번호를 비운다.
 */
@SpringBootTest(properties = ["crack.auth.password="])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StoryIsolationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var storyService: StoryService
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths
    @Autowired lateinit var promptAssembler: LegacyPromptAssembler

    private lateinit var scenario: Scenario
    private lateinit var scenarioDir: Path

    @BeforeEach
    fun setUp() {
        val name = "isolation-" + UUID.randomUUID().toString().take(8)
        scenarioDir = SampleScenario.copyTo(dataPaths.scenarioDir(name))
        scenario = scenarioRepository.save(Scenario(name = name, title = "격리 테스트"))
    }

    @AfterEach
    fun tearDown() {
        storyRepository.findByScenarioIdOrderByUpdatedAtDesc(scenario.id).forEach { storyRepository.delete(it) }
        scenarioRepository.delete(scenario)
        if (Files.exists(scenarioDir)) {
            Files.walk(scenarioDir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun createStory(title: String): Long = storyService.create(scenario.id, StoryCreateRequest(title)).id

    private fun storyDir(storyId: Long): Path =
        dataPaths.storyDir(scenario.name, storyRepository.findById(storyId).get().dirName)

    private fun putDoc(storyId: Long, path: String, content: String) =
        mockMvc.perform(
            put("/api/stories/$storyId/documents/content")
                .param("path", path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("content" to content)))
        )

    private fun getDoc(storyId: Long, path: String) =
        mockMvc.perform(get("/api/stories/$storyId/documents/content").param("path", path))

    private fun prompt(storyId: Long): String =
        promptAssembler.assembleSystemPrompt(scenarioDir, storyDir(storyId))

    private fun original(rel: String): String = Files.readString(SampleScenario.source.resolve(rel))

    @Test
    fun `스토리 A에서 인물 문서를 수정해도 원본과 스토리 B는 그대로다`() {
        val a = createStory("A")
        val b = createStory("B")

        putDoc(a, "characters/설월.md", "# 캐릭터: 설월\n스토리 A 전용 STORY_A_EDIT\n")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.path").value("characters/설월.md"))
            .andExpect(jsonPath("$.kind").value("characters"))

        getDoc(a, "characters/설월.md").andExpect(jsonPath("$.content").value("# 캐릭터: 설월\n스토리 A 전용 STORY_A_EDIT\n"))
        getDoc(b, "characters/설월.md").andExpect(jsonPath("$.content").value(original("characters/설월.md")))
        assertEquals(original("characters/설월.md"), Files.readString(scenarioDir.resolve("characters/설월.md")))

        assertTrue(prompt(a).contains("STORY_A_EDIT"))
        assertFalse(prompt(b).contains("STORY_A_EDIT"))
    }

    @Test
    fun `원본을 수정해도 기존 스토리는 그대로이고 새 스토리에는 반영된다`() {
        val old = createStory("기존")

        // 시나리오 원본 편집 API로 원본을 고친다
        mockMvc.perform(
            put("/api/scenarios/${scenario.name}/documents/world")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("content" to "# 세계관\n바뀐 원본 NEW_WORLD\n")))
        ).andExpect(status().isOk)
        Files.writeString(scenarioDir.resolve("characters/무극.md"), "# 캐릭터: 무극\nNEW_MUGEUK\n")

        val fresh = createStory("새")

        getDoc(old, "world.md").andExpect(jsonPath("$.content").value(original("world.md")))
        assertFalse(prompt(old).contains("NEW_WORLD"))
        assertFalse(prompt(old).contains("NEW_MUGEUK"))

        getDoc(fresh, "world.md").andExpect(jsonPath("$.content").value("# 세계관\n바뀐 원본 NEW_WORLD\n"))
        assertTrue(prompt(fresh).contains("NEW_WORLD"))
        assertTrue(prompt(fresh).contains("NEW_MUGEUK"))
    }

    @Test
    fun `문서 목록은 스토리 폴더의 화이트리스트 문서만 돌려준다`() {
        val id = createStory("목록")
        Files.writeString(storyDir(id).resolve("memo.txt"), "목록에 나오면 안 됨")

        val body = mockMvc.perform(get("/api/stories/$id/documents"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val items: List<Map<String, Any>> = objectMapper.readValue(
            body, objectMapper.typeFactory.constructCollectionType(List::class.java, Map::class.java)
        )

        assertEquals(
            listOf(
                "world.md" to "world", "scenario.md" to "scenario", "prologue.md" to "prologue",
                "characters/protagonist.md" to "protagonist",
                "characters/무극.md" to "characters", "characters/설월.md" to "characters",
                "chronicle.md" to "chronicle", "user_note.md" to "user_note",
                "keywords.md" to "keywords", "commands.md" to "commands",
            ),
            items.map { it["path"] to it["kind"] }
        )
        val worldSize = (items.first { it["path"] == "world.md" }["size"] as Number).toLong()
        assertEquals(Files.size(scenarioDir.resolve("world.md")), worldSize)
    }

    @Test
    fun `경로 조작과 화이트리스트 밖 경로는 400으로 거부한다`() {
        val a = createStory("A")
        val b = createStory("B")
        val bDir = dataPaths.storyDir(scenario.name, storyRepository.findById(b).get().dirName)
        val attacks = listOf(
            "../world.md",
            "../${bDir.fileName}/world.md",
            "../../world.md",
            "characters/../../../world.md",
            scenarioDir.resolve("world.md").toAbsolutePath().toString(),
            "/etc/passwd",
            "images.md",
            "story.json",
        )
        for (path in attacks) {
            putDoc(a, path, "HACKED").andExpect(status().isBadRequest)
            getDoc(a, path).andExpect(status().isBadRequest)
        }
        mockMvc.perform(get("/api/stories/$a/documents/content")).andExpect(status().isBadRequest)

        assertEquals(original("world.md"), Files.readString(scenarioDir.resolve("world.md")))
        assertEquals(original("world.md"), Files.readString(bDir.resolve("world.md")))
        Files.walk(scenarioDir).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { assertNotEquals("HACKED", Files.readString(it), it.toString()) }
        }
    }

    @Test
    fun `없는 문서는 404, 새 인물 문서는 PUT으로 만든다`() {
        val id = createStory("새 인물")
        getDoc(id, "characters/새인물.md").andExpect(status().isNotFound)

        putDoc(id, "characters/새인물.md", "# 캐릭터: 새인물\n").andExpect(status().isOk)

        assertTrue(Files.exists(storyDir(id).resolve("characters/새인물.md")))
        assertFalse(Files.exists(scenarioDir.resolve("characters/새인물.md")), "원본에는 생기지 않는다")
    }

    @Test
    fun `스토리를 삭제하면 스토리 폴더만 지운다`() {
        val a = createStory("A")
        val b = createStory("B")
        val aDir = storyDir(a)
        val bDir = storyDir(b)

        mockMvc.perform(delete("/api/scenarios/${scenario.id}/stories/$a")).andExpect(status().isNoContent)

        assertFalse(Files.exists(aDir))
        assertTrue(Files.exists(bDir.resolve("story.json")))
        Files.walk(SampleScenario.source).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { src ->
                val rel = SampleScenario.source.relativize(src).toString()
                assertEquals(Files.readString(src), Files.readString(scenarioDir.resolve(rel)), "원본 불변: $rel")
            }
        }
    }

    @Test
    fun `_legacy 스토리는 읽을 수 있지만 원본 보호를 위해 쓸 수 없다`() {
        val legacy = storyRepository.save(Story(scenarioId = scenario.id, title = "옛 기본", dirName = DataPaths.LEGACY_DIR_NAME))

        getDoc(legacy.id, "world.md").andExpect(status().isOk).andExpect(jsonPath("$.content").value(original("world.md")))
        putDoc(legacy.id, "world.md", "HACKED").andExpect(status().isBadRequest)

        assertEquals(original("world.md"), Files.readString(scenarioDir.resolve("world.md")))
    }

    @Test
    fun `없는 스토리는 404`() {
        mockMvc.perform(get("/api/stories/987654/documents")).andExpect(status().isNotFound)
    }
}
