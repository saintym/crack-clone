package com.crack.story

import com.crack.global.config.DataPaths
import com.crack.message.entity.MessageKind
import com.crack.message.repository.MessageVariantRepository
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.files.SampleScenario
import com.crack.story.files.StoryFiles
import com.crack.story.repository.StoryRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 분기 통합 테스트 (T09). 스토리는 `StoryService.create`를 거치지 않고 `StoryFiles.initFromScenario`로 직접 만든다
 * (create에 프롤로그 삽입이 붙어도 메시지 수 검증이 흔들리지 않게).
 */
@SpringBootTest(properties = ["crack.auth.password="])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StoryBranchTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var messageRepository: StoryMessageRepository
    @Autowired lateinit var variantRepository: MessageVariantRepository
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private lateinit var scenarioDir: Path
    private lateinit var source: Story
    private lateinit var sourceDir: Path

    /** seq 0..5: U1 A1 U2 A2 U3 A3 (턴 1,1,2,2,3,3) */
    private val ids = mutableListOf<Long>()

    @BeforeEach
    fun setUp() {
        val name = "branch-" + UUID.randomUUID().toString().take(8)
        scenarioDir = SampleScenario.copyTo(dataPaths.scenarioDir(name))
        scenario = scenarioRepository.save(Scenario(name = name, title = "분기 테스트"))
        sourceDir = scenarioDir.resolve("stories/1700000000000")
        StoryFiles.initFromScenario(scenarioDir, sourceDir)
        source = storyRepository.save(Story(scenarioId = scenario.id, title = "원본", dirName = "1700000000000"))

        ids += messageService.appendUser(source.id, "U1").id
        val a1 = messageService.appendAssistant(source.id, "A1", "기쁨")
        messageService.addVariant(a1.id, "A1-두번째", "놀람", "더 짧게")
        ids += a1.id
        ids += messageService.appendUser(source.id, "U2").id
        messageService.edit(ids[2], "U2-수정")
        ids += messageService.appendAssistant(source.id, "A2").id
        ids += messageService.appendUser(source.id, "U3").id
        ids += messageService.appendAssistant(source.id, "A3").id

        // 스토리 폴더에서 바뀐 문서, 기록 스냅샷, 옛 파일, 임시 파일
        Files.writeString(sourceDir.resolve("characters/설월.md"), "# 설월\n\n원본 스토리에서 갱신됨\n")
        write(sourceDir.resolve("memory/history/1/before/characters/설월.md"), "스냅샷")
        write(sourceDir.resolve("legacy/chat/chat_latest.md"), "# 최근 대화\n")
        write(sourceDir.resolve(".chronicle.md.123.tmp"), "임시")
    }

    @AfterEach
    fun tearDown() {
        storyRepository.findByScenarioIdOrderByUpdatedAtDesc(scenario.id).forEach { storyRepository.delete(it) }
        scenarioRepository.delete(scenario)
        if (Files.exists(scenarioDir)) {
            Files.walk(scenarioDir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun snapshot(dir: Path): Map<String, String> =
        Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            .associate { dir.relativize(it).toString() to Files.readString(it) }

    private fun branch(url: String, body: Map<String, Any?>): ResultActions =
        mockMvc.perform(
            post(url).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
        )

    private fun branchedStory(): Story =
        storyRepository.findByScenarioIdOrderByUpdatedAtDesc(scenario.id).single { it.id != source.id }

    @Test
    fun `기준 메시지의 seq까지 메시지와 후보를 복사하고 스토리 폴더를 통째로 복사한다`() {
        val sourceFiles = snapshot(sourceDir)

        branch("/api/stories/${source.id}/branch", mapOf("messageId" to ids[3], "title" to "분기"))
            .andExpect(status().isCreated)

        val branched = branchedStory()
        assertEquals("분기", branched.title)
        assertEquals(2, branched.turnCount)
        assertNotEquals(source.dirName, branched.dirName)

        // 메시지: U1 A1 U2 A2
        val messages = messageRepository.findByStoryIdOrderBySeqAsc(branched.id)
        assertEquals(listOf(0, 1, 2, 3), messages.map { it.seq })
        assertEquals(listOf(1, 1, 2, 2), messages.map { it.turnNo })
        assertEquals(listOf("U1", "A1-두번째", "U2-수정", "A2"), messages.map { it.content })
        assertEquals(listOf(MessageKind.NORMAL, MessageKind.NORMAL, MessageKind.NORMAL, MessageKind.NORMAL), messages.map { it.kind })
        assertEquals(1, messages[1].selectedVariant)
        assertEquals("놀람", messages[1].emotion)
        assertNotNull(messages[2].editedAt, "수정 표시는 그대로")
        messages.forEach { assertFalse(ids.contains(it.id), "새 메시지 행이어야 한다") }
        val variants = variantRepository.findByMessageIdOrderByVariantIndexAsc(messages[1].id)
        assertEquals(listOf("A1", "A1-두번째"), variants.map { it.content })
        assertEquals("더 짧게", variants[1].instruction)

        // 파일: 원본 스토리의 현재 문서를 복사, 기록 스냅샷·옛 파일·임시 파일·story.json은 제외
        val dir = dataPaths.storyDir(scenario.name, branched.dirName)
        assertEquals("# 설월\n\n원본 스토리에서 갱신됨\n", Files.readString(dir.resolve("characters/설월.md")))
        for (name in listOf("world.md", "scenario.md", "prologue.md", "user_note.md", "chronicle.md", "directives.json", "state.json", "characters/무극.md")) {
            assertEquals(sourceFiles[name], Files.readString(dir.resolve(name)), name)
        }
        assertFalse(Files.exists(dir.resolve("memory/history")))
        assertFalse(Files.exists(dir.resolve("legacy")))
        assertFalse(Files.exists(dir.resolve(".chronicle.md.123.tmp")))
        assertEquals(scenario.name, StoryFiles.readMeta(dir)!!.scenarioName)

        // 원본 불변
        assertEquals(sourceFiles, snapshot(sourceDir))
        val sourceMessages = messageRepository.findByStoryIdOrderBySeqAsc(source.id)
        assertEquals(ids, sourceMessages.map { it.id })
        assertEquals(3, storyRepository.findById(source.id).get().turnCount)
    }

    @Test
    fun `분기한 스토리와 원본은 서로 영향을 주지 않는다`() {
        branch("/api/stories/${source.id}/branch", mapOf("messageId" to ids[1], "title" to "분기"))
            .andExpect(status().isCreated)
        val branched = branchedStory()
        val dir = dataPaths.storyDir(scenario.name, branched.dirName)

        Files.writeString(dir.resolve("characters/설월.md"), "분기에서 바꿈")
        messageService.appendUser(branched.id, "분기의 U2")

        assertEquals("# 설월\n\n원본 스토리에서 갱신됨\n", Files.readString(sourceDir.resolve("characters/설월.md")))
        assertEquals(6, messageRepository.findByStoryIdOrderBySeqAsc(source.id).size)
        assertEquals(listOf(1, 1, 2), messageRepository.findByStoryIdOrderBySeqAsc(branched.id).map { it.turnNo })
    }

    @Test
    fun `분기 스토리의 recorded_through_turn은 원본값과 기준 턴 중 작은 값이다`() {
        storyRepository.save(storyRepository.findById(source.id).get().apply { recordedThroughTurn = 3 })
        branch("/api/stories/${source.id}/branch", mapOf("messageId" to ids[3], "title" to "기준 턴이 작음"))
            .andExpect(status().isCreated)
        assertEquals(2, branchedStory().recordedThroughTurn)
        storyRepository.delete(branchedStory())

        storyRepository.save(storyRepository.findById(source.id).get().apply { recordedThroughTurn = 1 })
        branch("/api/stories/${source.id}/branch", mapOf("messageId" to ids[5], "title" to "원본값이 작음"))
            .andExpect(status().isCreated)
        assertEquals(1, branchedStory().recordedThroughTurn)
    }

    @Test
    fun `시나리오 경로로도 분기한다`() {
        branch("/api/scenarios/${scenario.id}/stories/${source.id}/branch", mapOf("messageId" to ids[1], "title" to "시나리오 경로"))
            .andExpect(status().isCreated)

        val branched = branchedStory()
        assertEquals(listOf("U1", "A1-두번째"), messageRepository.findByStoryIdOrderBySeqAsc(branched.id).map { it.content })
        assertEquals(1, branched.turnCount)
    }

    @Test
    fun `잘못된 요청은 거부하고 스토리를 만들지 않는다`() {
        val url = "/api/stories/${source.id}/branch"
        branch(url, mapOf("title" to "기준 없음")).andExpect(status().isBadRequest)
        branch(url, mapOf("messageId" to ids[1], "title" to " ")).andExpect(status().isBadRequest)
        // 옛 과도기 필드만 보내면 기준 메시지가 없는 것으로 본다
        branch(url, mapOf("messageIndex" to 1, "title" to "옛 형식")).andExpect(status().isBadRequest)

        // 다른 스토리의 메시지
        val otherDir = scenarioDir.resolve("stories/1700000000001")
        StoryFiles.initFromScenario(scenarioDir, otherDir)
        val other = storyRepository.save(Story(scenarioId = scenario.id, title = "다른", dirName = "1700000000001"))
        val foreign = messageService.appendUser(other.id, "남의 메시지")
        branch(url, mapOf("messageId" to foreign.id, "title" to "남의 것")).andExpect(status().isNotFound)

        assertEquals(2, storyRepository.findByScenarioIdOrderByUpdatedAtDesc(scenario.id).size)
        Files.list(scenarioDir.resolve("stories")).use { assertEquals(2, it.count()) }
    }

    @Test
    fun `이전되지 않은 옛 스토리는 분기할 수 없다`() {
        val legacy = storyRepository.save(Story(scenarioId = scenario.id, title = "옛", dirName = DataPaths.LEGACY_DIR_NAME))
        branch("/api/stories/${legacy.id}/branch", mapOf("messageId" to ids[0], "title" to "x")).andExpect(status().isBadRequest)

        val oldDir = scenarioDir.resolve("stories/1600000000000")
        write(oldDir.resolve("chat/chat_latest.md"), "# 최근 대화\n")
        val old = storyRepository.save(Story(scenarioId = scenario.id, title = "옛2", dirName = "1600000000000"))
        branch("/api/stories/${old.id}/branch", mapOf("messageId" to ids[0], "title" to "x")).andExpect(status().isBadRequest)

        assertTrue(storyRepository.findByScenarioIdOrderByUpdatedAtDesc(scenario.id).none { it.title == "x" })
    }
}
