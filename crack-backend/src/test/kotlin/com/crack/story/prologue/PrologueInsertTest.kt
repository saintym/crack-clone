package com.crack.story.prologue

import com.crack.global.config.DataPaths
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.service.MessageService
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.dto.StoryCreateRequest
import com.crack.story.files.SampleScenario
import com.crack.story.repository.StoryRepository
import com.crack.story.service.StoryService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 스토리를 만들 때 첫 메시지(프롤로그)가 턴 0으로 들어가는지 확인한다 (D16, DESIGN.md §3 턴 규칙).
 * 설정을 [com.crack.story.StoryIsolationTest]와 맞춰 Spring 컨텍스트를 공유한다.
 */
@SpringBootTest(properties = ["crack.auth.password="])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrologueInsertTest {

    @Autowired lateinit var storyService: StoryService
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private lateinit var scenarioDir: Path

    @BeforeEach
    fun setUp() {
        val name = "prologue-" + UUID.randomUUID().toString().take(8)
        scenarioDir = SampleScenario.copyTo(dataPaths.scenarioDir(name))
        scenario = scenarioRepository.save(Scenario(name = name, title = "프롤로그 테스트"))
    }

    @AfterEach
    fun tearDown() {
        storyRepository.findByScenarioIdOrderByUpdatedAtDesc(scenario.id).forEach { storyRepository.delete(it) }
        scenarioRepository.delete(scenario)
        if (Files.exists(scenarioDir)) {
            Files.walk(scenarioDir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun createStory(): Long = storyService.create(scenario.id, StoryCreateRequest("새 이야기")).id

    @Test
    fun `스토리를 만들면 prologue가 턴 0 첫 메시지로 들어가고 턴 수는 0이다`() {
        val response = storyService.create(scenario.id, StoryCreateRequest("새 이야기"))

        val messages = messageService.list(response.id)
        assertEquals(1, messages.size)
        val prologue = messages.single()
        assertEquals(0, prologue.seq)
        assertEquals(0, prologue.turn)
        assertEquals(MessageRole.ASSISTANT, prologue.role)
        assertEquals(MessageKind.PROLOGUE, prologue.kind)
        assertEquals(0, prologue.variantIndex)
        assertEquals(1, prologue.variantCount)
        assertEquals(Files.readString(SampleScenario.source.resolve("prologue.md")).trim(), prologue.content)

        assertEquals(0, response.turnCount)
        assertEquals(0, storyRepository.findById(response.id).get().turnCount)
    }

    @Test
    fun `user 자리표시자를 주인공 이름으로 바꾼다`() {
        Files.writeString(scenarioDir.resolve("prologue.md"), "\"{{user}} 대협, 오랜만이에요.\"\n")

        val storyId = createStory()

        assertEquals("\"한유 대협, 오랜만이에요.\"", messageService.list(storyId).single().content)
    }

    @Test
    fun `주인공 이름이 없으면 당신으로 바꾼다`() {
        Files.writeString(scenarioDir.resolve("characters/protagonist.md"), "# 주인공\n\n## 기본 정보\n- **이름**:\n")
        Files.writeString(scenarioDir.resolve("prologue.md"), "{{user}}은 눈을 떴다.")

        val storyId = createStory()

        assertEquals("당신은 눈을 떴다.", messageService.list(storyId).single().content)
    }

    @Test
    fun `prologue 파일이 없으면 메시지를 넣지 않는다`() {
        Files.delete(scenarioDir.resolve("prologue.md"))

        val storyId = createStory()

        assertTrue(messageService.list(storyId).isEmpty())
        assertEquals(0, storyRepository.findById(storyId).get().turnCount)
    }

    @Test
    fun `prologue가 비었거나 주석뿐이면 메시지를 넣지 않는다`() {
        Files.writeString(scenarioDir.resolve("prologue.md"), "\n  \n")
        assertTrue(messageService.list(createStory()).isEmpty())

        Files.writeString(scenarioDir.resolve("prologue.md"), "<!-- 첫 메시지를 여기에 쓴다 -->\n")
        assertTrue(messageService.list(createStory()).isEmpty())
    }

    @Test
    fun `프롤로그 다음 유저 메시지는 턴 1을 연다`() {
        val storyId = createStory()

        val user = messageService.appendUser(storyId, "안녕하시오")
        val reply = messageService.appendAssistant(storyId, "……네.", turnNo = user.turnNo)

        assertEquals(1, user.turnNo)
        assertEquals(1, user.seq)
        assertEquals(1, reply.turnNo)
        assertEquals(1, storyRepository.findById(storyId).get().turnCount)
    }

    @Test
    fun `프롤로그는 일반 메시지처럼 수정할 수 있다`() {
        val storyId = createStory()
        val prologue = messageService.list(storyId).single()

        messageService.edit(prologue.id, "고친 첫 메시지")

        val edited = messageService.list(storyId).single()
        assertEquals("고친 첫 메시지", edited.content)
        assertTrue(edited.edited)
        assertEquals(MessageKind.PROLOGUE, edited.kind)
        assertEquals(0, storyRepository.findById(storyId).get().turnCount)
    }
}
