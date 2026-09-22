package com.crack.command

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.fake.FakeRecordResponder
import com.crack.ai.provider.FakeResponses
import com.crack.chat.api.SseEvent
import com.crack.chat.api.awaitUntil
import com.crack.chat.api.parseSse
import com.crack.global.config.DataPaths
import com.crack.memory.record.MemoryRecordRepository
import com.crack.memory.record.MemoryRecordService
import com.crack.memory.record.RecordReason
import com.crack.memory.record.RecordStatus
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.ResultMatcher
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `/` 명령 (DESIGN.md §8.2): 목록, 시스템 명령(`/기록`, `/ooc`), `POST /messages`의 사용자 정의 명령.
 * 스토리 폴더는 `fixtures/sample-scenario`(명령: `/일기`)를 복사해 쓴다. AI는 Fake 프로바이더다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml의 비밀번호로 AuthFilter가 끼지 않게
class CommandApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var fakeResponses: FakeResponses
    @Autowired lateinit var fakeRecord: FakeRecordResponder
    @Autowired lateinit var memoryRecordService: MemoryRecordService
    @Autowired lateinit var recordRepository: MemoryRecordRepository
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var messageRepository: StoryMessageRepository
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private lateinit var storyDir: Path
    private var storyId = 0L
    private val chatRequests = CopyOnWriteArrayList<AiRequest>()

    @BeforeEach
    fun setUp() {
        fakeResponses.reset()
        fakeResponses.register(AiPurpose.CHAT) { req ->
            chatRequests += req
            "응답"
        }
        scenario = scenarioRepository.save(Scenario(name = "command-${UUID.randomUUID().toString().take(8)}", title = "명령"))
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "스토리", dirName = "6000"))
        storyId = story.id
        storyDir = SampleScenario.copyTo(dataPaths.storyDir(scenario.name, story.dirName))
    }

    @AfterEach
    fun tearDown() {
        awaitUntil(10_000, "기록 종료") { !memoryRecordService.isRunning(storyId) }
        fakeResponses.reset()
        chatRequests.clear()
        val dir = dataPaths.scenarioDir(scenario.name)
        if (Files.exists(dir)) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    // ---- 도우미 ----

    private fun json(body: Any) = objectMapper.writeValueAsString(body)

    private fun perform(builder: RequestBuilder, expect: ResultMatcher): JsonNode? =
        mockMvc.perform(builder).andExpect(expect).andReturn().response.getContentAsString(Charsets.UTF_8)
            .takeIf { it.isNotBlank() }?.let { objectMapper.readTree(it) }

    private fun system(name: String, args: String? = null, expect: ResultMatcher = status().isOk): JsonNode? =
        perform(
            post("/api/stories/$storyId/commands/system").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("name" to name, "args" to args))),
            expect,
        )

    private fun sendReq(content: String, command: String? = null) =
        post("/api/stories/$storyId/messages").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
            .content(json(mapOf("content" to content, "command" to command)))

    private fun regenReq() =
        post("/api/stories/$storyId/messages/regenerate").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
            .content("{}")

    /** SSE를 끝까지 받고 asyncDispatch까지 마친다(안 하면 DB 커넥션이 샌다). */
    private fun sse(builder: RequestBuilder): List<SseEvent> {
        val result = mockMvc.perform(builder).andExpect(request().asyncStarted()).andReturn()
        result.getAsyncResult(10_000)
        val body = result.response.getContentAsString(Charsets.UTF_8)
        mockMvc.perform(asyncDispatch(result))
        return parseSse(body).also { events -> assertThat(events.map { it.name }).contains("done") }
    }

    private fun lastUserContent(req: AiRequest) = req.messages.last().content

    private fun preview(): JsonNode = perform(get("/api/stories/$storyId/prompt-preview"), status().isOk)!!

    // ---- 목록 ----

    @Test
    fun `목록은 시스템 명령 뒤에 사용자 정의 명령이 온다`() {
        val body = perform(get("/api/stories/$storyId/commands"), status().isOk)!!

        assertThat(body.map { it["name"].asText() to it["type"].asText() })
            .containsExactly("기록" to "SYSTEM", "ooc" to "SYSTEM", "일기" to "CUSTOM")
        assertThat(body[2]["description"].asText()).isEqualTo("주인공의 하루를 일기 형식으로 정리")
    }

    @Test
    fun `commands md가 없으면 시스템 명령만 나온다`() {
        Files.delete(storyDir.resolve("commands.md"))
        val body = perform(get("/api/stories/$storyId/commands"), status().isOk)!!
        assertThat(body.map { it["type"].asText() }).containsOnly("SYSTEM")
    }

    // ---- 시스템 명령 ----

    @Test
    fun `기록 명령은 수동 기억 기록을 시작한다`() {
        assertThat(system("/기록")!!["record"]["result"].asText()).isEqualTo("NOTHING_TO_RECORD")

        fakeRecord.install()
        repeat(2) {
            val u = messageService.appendUser(storyId, "설월과 함께 길을 걷는다")
            messageService.appendAssistant(storyId, "설월이 조용히 따라온다.", turnNo = u.turnNo)
        }

        val body = system("기록")!!
        assertThat(body["name"].asText()).isEqualTo("기록")
        assertThat(body["directive"].isNull).isTrue()
        assertThat(body["record"]["result"].asText()).isEqualTo("STARTED")
        val recordId = body["record"]["record"]["id"].asLong()
        val record = recordRepository.findById(recordId).get()
        assertThat(record.reason).isEqualTo(RecordReason.MANUAL)
        assertThat(record.storyId).isEqualTo(storyId)

        awaitUntil(10_000, "기록 종료") {
            recordRepository.findById(recordId).get().status != RecordStatus.RUNNING && !memoryRecordService.isRunning(storyId)
        }
        // 메시지를 남기지 않는다
        assertThat(messageRepository.findByStoryIdOrderBySeqAsc(storyId)).hasSize(4)
    }

    @Test
    fun `ooc 명령은 지속 지시를 추가하고 다음 프롬프트에 들어간다`() {
        val body = system("OOC", "  말투는 반말  ")!!

        assertThat(body["name"].asText()).isEqualTo("ooc")
        assertThat(body["record"].isNull).isTrue()
        assertThat(body["directive"]["text"].asText()).isEqualTo("말투는 반말")
        assertThat(body["directive"]["enabled"].asBoolean()).isTrue()

        val directives = perform(get("/api/stories/$storyId/directives"), status().isOk)!!
        assertThat(directives.map { it["id"].asText() }).containsExactly(body["directive"]["id"].asText())

        sse(sendReq("안녕"))
        assertThat(lastUserContent(chatRequests.last()))
            .isEqualTo("[지시]\n다음 지시는 해제될 때까지 항상 지켜라:\n1. 말투는 반말\n\n안녕")
    }

    @Test
    fun `ooc 인자가 없거나 모르는 시스템 명령이면 400`() {
        system("ooc", "   ", status().isBadRequest)
        system("ooc", null, status().isBadRequest)
        system("일기", "사용자 정의 명령은 시스템 명령이 아니다", status().isBadRequest)
        system("없는명령", null, status().isBadRequest)
        assertThat(Files.exists(storyDir.resolve("directives.json"))).isFalse()
    }

    // ---- 사용자 정의 명령 ----

    @Test
    fun `사용자 정의 명령은 COMMAND로 저장되고 지시는 그 턴에만 들어간다`() {
        val events = sse(sendReq("/일기 오늘은 짧게", command = "일기"))

        val user = objectMapper.readTree(events.single { it.name == "user" }.data)
        assertThat(user["kind"].asText()).isEqualTo("COMMAND")
        assertThat(user["content"].asText()).isEqualTo("/일기 오늘은 짧게")
        val saved = messageRepository.findByStoryIdOrderBySeqAsc(storyId)
        assertThat(saved.map { it.role to it.kind }).containsExactly(
            MessageRole.USER to MessageKind.COMMAND,
            MessageRole.ASSISTANT to MessageKind.NORMAL,
        )
        val instruction = "[/일기 명령] 지금까지의 일을 주인공 시점의 일기로 써라. 이야기는 진행하지 마라.\n요청: 오늘은 짧게"
        assertThat(lastUserContent(chatRequests.last())).isEqualTo("[지시]\n$instruction\n\n/일기 오늘은 짧게")

        // 다음 턴에는 넣지 않는다(대화 원문에는 유저 메시지가 그대로 남는다)
        sse(sendReq("일기를 덮는다"))
        val next = chatRequests.last()
        assertThat(next.messages.joinToString("\n") { it.content }).doesNotContain("[/일기 명령]").contains("/일기 오늘은 짧게")
        assertThat(lastUserContent(next)).isEqualTo("일기를 덮는다")
        assertThat(preview()["sections"].map { it["name"].asText() }).doesNotContain("turn_instruction")
    }

    @Test
    fun `명령 턴을 재생성하면 같은 지시가 다시 들어간다`() {
        sse(sendReq("/일기", command = "/일기"))
        assertThat(lastUserContent(chatRequests.last())).startsWith("[지시]\n[/일기 명령] 지금까지의 일을")

        sse(regenReq())

        val regen = chatRequests.last()
        assertThat(lastUserContent(regen))
            .isEqualTo("[지시]\n[/일기 명령] 지금까지의 일을 주인공 시점의 일기로 써라. 이야기는 진행하지 마라.\n\n/일기")
        val assistant = messageRepository.findByStoryIdOrderBySeqAsc(storyId).last()
        assertThat(messageService.view(assistant).variantCount).isEqualTo(2)
    }

    @Test
    fun `모르는 명령이나 시스템 명령을 메시지로 보내면 400이고 저장하지 않는다`() {
        perform(sendReq("/없는명령 해", command = "없는명령"), status().isBadRequest)
        perform(sendReq("/기록", command = "기록"), status().isBadRequest)
        perform(sendReq("/ooc 반말", command = "ooc"), status().isBadRequest)

        assertThat(messageRepository.findByStoryIdOrderBySeqAsc(storyId)).isEmpty()
        assertThat(chatRequests).isEmpty()
    }

    @Test
    fun `빈 command는 일반 메시지로 보낸다`() {
        val events = sse(sendReq("안녕", command = ""))
        assertThat(objectMapper.readTree(events.single { it.name == "user" }.data)["kind"].asText()).isEqualTo("NORMAL")
    }
}
