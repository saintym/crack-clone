package com.crack.story.clock

import com.crack.ai.dto.AiPurpose
import com.crack.ai.provider.FakeResponses
import com.crack.chat.api.awaitUntil
import com.crack.chat.flow.ChatFlowService
import com.crack.command.CommandService
import com.crack.global.config.DataPaths
import com.crack.global.exception.BadRequestException
import com.crack.memory.docs.MemoryDocs
import com.crack.message.dto.MessageView
import com.crack.message.entity.MessageRole
import com.crack.message.service.MessageService
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.dto.StoryCreateRequest
import com.crack.story.entity.Story
import com.crack.story.files.SampleScenario
import com.crack.story.repository.StoryRepository
import com.crack.story.service.StoryService
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.time.LocalDateTime
import java.util.UUID

/**
 * 이야기 속 시계의 실제 흐름 (T38, DESIGN.md §5.4, D39).
 *
 * 응답 태그 → 단조 비감소 확정 → `story_time`·`place` 칼럼과 `state.json` → `MessageView`까지 확인한다.
 * 실제 AI를 부르지 않고 Fake 프로바이더로 응답 본문을 정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml의 비밀번호로 AuthFilter가 끼지 않게
class StoryClockFlowTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var fakeResponses: FakeResponses
    @Autowired lateinit var chatFlowService: ChatFlowService
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var commandService: CommandService
    @Autowired lateinit var storyService: StoryService
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private lateinit var storyDir: Path
    private var storyId = 0L

    private val start = LocalDateTime.of(2026, 2, 15, 19, 0)

    @BeforeEach
    fun setUp() {
        scenario = scenarioRepository.save(
            Scenario(name = "clock-${UUID.randomUUID().toString().take(8)}", title = "시계"),
        )
        SampleScenario.copyTo(dataPaths.scenarioDir(scenario.name))
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "스토리", dirName = "9000"))
        storyId = story.id
        storyDir = SampleScenario.copyTo(dataPaths.storyDir(scenario.name, story.dirName))
        settings("""{"clock": {"start": "2026-02-15 19:00", "place": "후유키 심산정"}}""")
    }

    @AfterEach
    fun tearDown() {
        fakeResponses.reset()
        val dir = dataPaths.scenarioDir(scenario.name)
        if (Files.exists(dir)) {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    // ---- 도우미 ----

    private fun settings(json: String) = Files.writeString(storyDir.resolve("settings.json"), json)

    /** Fake 프로바이더의 CHAT 응답을 차례대로 정한다. 마지막 응답은 계속 반복된다 */
    private fun respondWith(vararg responses: String) {
        val queue = ArrayDeque(responses.toList())
        fakeResponses.register(AiPurpose.CHAT) { if (queue.size > 1) queue.removeFirst() else queue.first() }
    }

    /** 유저 메시지를 보내고 응답이 저장될 때까지 기다린다. SSE 연결은 바로 닫는다(저장은 서버가 끝낸다) */
    private fun send(content: String = "계속") {
        val before = messages().size
        chatFlowService.send(storyId, content, null, null).complete()
        awaitUntil(message = "응답 저장") { messages().size >= before + 2 }
        awaitUntil(message = "락 해제") { !chatFlowService.state(storyId).story.generating }
    }

    private fun messages(): List<MessageView> = messageService.list(storyId)

    private fun lastAssistant(): MessageView = messages().last { it.role == MessageRole.ASSISTANT }

    private fun state() = MemoryDocs.readState(storyDir)

    private fun tagged(time: String?, place: String? = null, body: String = "*문이 열렸다.*") = buildString {
        if (time != null) append("[시간: $time] ")
        if (place != null) append("[장소: $place] ")
        append("[감정: 평온]\n\n")
        append(body)
    }

    // ---- 태그 저장 ----

    @Test
    fun `응답 첫 줄의 시각과 장소가 메시지와 state_json에 남는다`() {
        respondWith(tagged("2026-02-15 20:30", "에미야 저택"))

        send("저택으로 간다")

        val assistant = lastAssistant()
        assertThat(assistant.storyTime).isEqualTo(LocalDateTime.of(2026, 2, 15, 20, 30))
        assertThat(assistant.place).isEqualTo("에미야 저택")
        assertThat(assistant.content).isEqualTo("*문이 열렸다.*") // 태그는 본문에 남지 않는다
        assertThat(state().clock).isEqualTo("2026-02-15T20:30")
        assertThat(state().place).isEqualTo("에미야 저택")
    }

    @Test
    fun `사용자가 일주일 후라고 쓰면 시계가 일주일 뒤로 간다`() {
        respondWith(tagged("2026-02-22 19:00", "후유키 심산정"))

        send("**그렇게 일주일이 흐른다.**")

        assertThat(lastAssistant().storyTime).isEqualTo(start.plusWeeks(1))
    }

    @Test
    fun `시각 태그가 없는 턴은 직전 시각과 장소를 잇는다`() {
        respondWith(tagged("2026-02-15 20:30", "에미야 저택"), tagged(null))

        send()
        send()

        val assistant = lastAssistant()
        assertThat(assistant.storyTime).isEqualTo(LocalDateTime.of(2026, 2, 15, 20, 30))
        assertThat(assistant.place).isEqualTo("에미야 저택")
    }

    @Test
    fun `직전보다 이른 시각은 버리고 직전 값을 쓴다`() {
        respondWith(tagged("2026-02-15 20:30", "에미야 저택"), tagged("2026-02-15 19:10", "정원"))

        send()
        send()

        val assistant = lastAssistant()
        assertThat(assistant.storyTime).isEqualTo(LocalDateTime.of(2026, 2, 15, 20, 30))
        assertThat(assistant.place).isEqualTo("정원") // 장소는 태그를 따른다
        assertThat(state().clock).isEqualTo("2026-02-15T20:30")
    }

    @Test
    fun `첫 응답은 시작 시각보다 이를 수 없다`() {
        respondWith(tagged("2020-01-01 00:00"))

        send()

        assertThat(lastAssistant().storyTime).isEqualTo(start)
    }

    @Test
    fun `알아볼 수 없는 시각 태그도 턴을 실패시키지 않는다`() {
        respondWith(tagged("그날 늦은 밤", "저택"))

        send()

        assertThat(lastAssistant().storyTime).isEqualTo(start)
        assertThat(lastAssistant().place).isEqualTo("저택")
    }

    // ---- MessageView 계약 (T51이 쓴다) ----

    @Test
    fun `MessageView에 storyTime과 place가 실려 나간다`() {
        respondWith(tagged("2026-02-15 20:30", "에미야 저택"))
        send()

        val body = mockMvc.perform(get("/api/stories/$storyId/messages"))
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        val json = objectMapper.readTree(body)

        // ASSISTANT: ISO-8601 지역 시각 문자열 + 자유 문자열 장소.
        // 초까지 붙는다(`createdAt`과 같은 형식이다). T51이 이 형식을 읽는다
        val storyTime = json.at("/messages/1/storyTime").asText()
        assertThat(storyTime).isEqualTo("2026-02-15T20:30:00")
        assertThat(LocalDateTime.parse(storyTime)).isEqualTo(LocalDateTime.of(2026, 2, 15, 20, 30))
        assertThat(json.at("/messages/1/place").asText()).isEqualTo("에미야 저택")

        // USER: 두 필드 모두 null이다
        assertThat(json.at("/messages/0/storyTime").isNull).isTrue()
        assertThat(json.at("/messages/0/place").isNull).isTrue()
    }

    // ---- 되돌리기 ----

    @Test
    fun `메시지를 지우면 시계가 그 시점으로 돌아간다`() {
        respondWith(tagged("2026-02-15 20:30", "에미야 저택"), tagged("2026-02-16 09:00", "시내"))
        send()
        send()
        assertThat(state().clock).isEqualTo("2026-02-16T09:00")

        // 두 번째 턴의 유저 메시지부터 지운다
        val secondTurnUser = messages().first { it.turn == 2 && it.role == MessageRole.USER }
        chatFlowService.truncateFrom(storyId, secondTurnUser.id)

        assertThat(state().clock).isEqualTo("2026-02-15T20:30")
        assertThat(state().place).isEqualTo("에미야 저택")
    }

    @Test
    fun `전부 지우면 시작 시각으로 돌아간다`() {
        respondWith(tagged("2026-02-15 20:30", "에미야 저택"))
        send()

        chatFlowService.truncateFrom(storyId, messages().first().id)

        assertThat(state().clock).isNull()
        assertThat(StoryClockFiles.point(storyDir)).isEqualTo(ClockPoint(start, "후유키 심산정"))
    }

    @Test
    fun `다른 후보를 고르면 그 후보의 시각으로 맞춘다`() {
        respondWith(tagged("2026-02-15 20:30", "에미야 저택"), tagged("2026-02-17 08:00", "역 앞"))
        send()
        val message = lastAssistant()
        chatFlowService.regenerate(storyId, null, null, message.id).complete()
        awaitUntil(message = "후보 추가") { messageService.list(storyId).last().variantCount == 2 }
        awaitUntil(message = "락 해제") { !chatFlowService.state(storyId).story.generating }
        assertThat(state().clock).isEqualTo("2026-02-17T08:00")

        chatFlowService.selectVariant(storyId, message.id, 0)

        assertThat(state().clock).isEqualTo("2026-02-15T20:30")
        assertThat(state().place).isEqualTo("에미야 저택")
    }

    // ---- 시계를 쓰지 않는 스토리 ----

    @Test
    fun `clock enabled가 false면 시각을 저장하지 않는다`() {
        settings("""{"clock": {"start": "2026-02-15 19:00", "enabled": false}}""")
        respondWith(tagged("2026-02-15 20:30", "에미야 저택"))

        send()

        assertThat(lastAssistant().storyTime).isNull()
        assertThat(lastAssistant().place).isNull()
        assertThat(state().clock).isNull()
    }

    @Test
    fun `시계가 없는 옛 스토리는 예전과 똑같이 동작한다`() {
        Files.deleteIfExists(storyDir.resolve("settings.json"))
        respondWith(tagged("2026-02-15 20:30", "에미야 저택"))

        send()

        val assistant = lastAssistant()
        assertThat(assistant.content).isEqualTo("*문이 열렸다.*") // 태그는 그래도 본문에서 빠진다
        assertThat(assistant.storyTime).isNull()
        assertThat(assistant.place).isNull()
        assertThat(state().clock).isNull()
    }

    // ---- 프롤로그로 시계 시작 ----

    @Test
    fun `settings에 시작 시각이 없으면 프롤로그 태그로 시계를 시작한다`() {
        val scenarioDir = dataPaths.scenarioDir(scenario.name)
        Files.writeString(scenarioDir.resolve("settings.json"), "{}")
        Files.writeString(
            scenarioDir.resolve("prologue.md"),
            "[시간: 2026-02-15 19:00] [장소: 후유키 심산정]\n\n*비가 그친 저녁이었다.*\n",
        )

        val created = storyService.create(scenario.id, StoryCreateRequest("프롤로그 시계"))
        val newDir = dataPaths.storyDir(scenario.name, storyRepository.findById(created.id).get().dirName)

        assertThat(MemoryDocs.readState(newDir).clock).isEqualTo("2026-02-15T19:00")
        assertThat(StoryClockFiles.point(newDir)).isEqualTo(ClockPoint(start, "후유키 심산정"))
        // 프롤로그 메시지에도 값이 남는다
        assertThat(messageService.list(created.id).single().storyTime).isEqualTo(start)
    }

    // ---- `/시간` 명령 ----

    @Test
    fun `시간 명령으로 절대 시각을 맞춘다`() {
        val response = commandService.runSystem(storyId, "시간", "2026-10-05 08:00")

        assertThat(response.name).isEqualTo("시간")
        assertThat(response.clock?.storyTime).isEqualTo(LocalDateTime.of(2026, 10, 5, 8, 0))
        assertThat(state().clock).isEqualTo("2026-10-05T08:00")
        assertThat(response.clock?.place).isEqualTo("후유키 심산정")
    }

    @Test
    fun `시간 명령으로 상대 이동을 맞춘다`() {
        commandService.runSystem(storyId, "시간", "+3일")

        assertThat(state().clock).isEqualTo("2026-02-18T19:00")
    }

    @Test
    fun `시간 명령은 뒤로도 갈 수 있다 — AI가 어긋나게 찍었을 때의 탈출구다`() {
        commandService.runSystem(storyId, "시간", "+1개월")
        commandService.runSystem(storyId, "시간", "2026-02-15 20:00")

        assertThat(state().clock).isEqualTo("2026-02-15T20:00")
    }

    @Test
    fun `시간 명령의 인자가 없거나 이상하면 400이다`() {
        assertThatThrownBy { commandService.runSystem(storyId, "시간", null) }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { commandService.runSystem(storyId, "시간", "내일 아침") }
            .isInstanceOf(BadRequestException::class.java)
        assertThat(state().clock).isNull()
    }

    @Test
    fun `시계를 끈 스토리에서는 시간 명령이 400이다`() {
        settings("""{"clock": {"enabled": false}}""")

        assertThatThrownBy { commandService.runSystem(storyId, "시간", "2026-10-05 08:00") }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `명령 목록에 시간이 있다`() {
        assertThat(commandService.list(storyId).map { it.name }).contains("시간")
    }
}
