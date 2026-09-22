package com.crack.chat.api

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.MessageRole as AiRole
import com.crack.ai.provider.FakeResponses
import com.crack.chat.flow.ChatFlowService
import com.crack.chat.flow.ConversationBuilder
import com.crack.chat.flow.GenerationMode
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.repository.MessageVariantRepository
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
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
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `/api/stories/{id}/messages*` 통합 테스트. 기본은 Fake 프로바이더, 실패·대기 상황은 [ScriptedAiProvider]를 쓴다.
 * 저장이 프로바이더 스레드에서 커밋되므로 테스트 트랜잭션을 쓰지 않고, 테스트마다 새 시나리오·스토리를 만든다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml에 비밀번호가 있으면 AuthFilter가 401을 낸다. 인증은 이 테스트 대상이 아니다
@Import(ChatTestConfig::class)
class StoryMessageApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var fakeResponses: FakeResponses
    @Autowired lateinit var scripted: ScriptedAiProvider
    @Autowired lateinit var hook: RecordingAfterTurnHook
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var messageRepository: StoryMessageRepository
    @Autowired lateinit var variantRepository: MessageVariantRepository
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var chatFlowService: ChatFlowService

    private var storyId: Long = 0
    private val requests = CopyOnWriteArrayList<AiRequest>()

    @BeforeEach
    fun setUp() {
        val scenario = scenarioRepository.save(Scenario(name = "chat-${UUID.randomUUID()}", title = "채팅 테스트"))
        storyId = storyRepository.save(Story(scenarioId = scenario.id, title = "스토리", dirName = "1000")).id
        respondWith("응답")
    }

    @AfterEach
    fun tearDown() {
        fakeResponses.reset()
        hook.events.clear()
    }

    // ---- 도우미 ----

    /** Fake 프로바이더의 CHAT 응답을 정하고, 들어온 요청을 [requests]에 모은다. */
    private fun respondWith(vararg responses: String) {
        val queue = ArrayDeque(responses.toList())
        fakeResponses.register(AiPurpose.CHAT) { req ->
            requests += req
            if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    private fun base() = "/api/stories/$storyId/messages"

    private fun json(body: Any) = objectMapper.writeValueAsString(body)

    private fun start(builder: RequestBuilder): MvcResult =
        mockMvc.perform(builder).andExpect(request().asyncStarted()).andReturn()

    /**
     * SSE가 끝날 때까지 기다린 뒤 async dispatch까지 마친다. 실제 서블릿 컨테이너처럼 요청 수명을 끝내야
     * OSIV(테스트 프로필에서는 `open-in-view` 기본값 true)가 잡은 DB 커넥션이 풀로 돌아간다.
     * 생략하면 SSE 요청마다 커넥션이 새어 10개쯤 뒤에 풀이 바닥난다.
     */
    private fun finish(result: MvcResult): List<SseEvent> {
        result.getAsyncResult(10_000)
        val body = result.response.getContentAsString(Charsets.UTF_8)
        mockMvc.perform(asyncDispatch(result))
        return parseSse(body)
    }

    private fun sse(builder: RequestBuilder): List<SseEvent> = finish(start(builder))

    private fun sendReq(content: String, provider: String? = null) =
        post(base()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
            .content(json(mapOf("content" to content, "provider" to provider)))

    private fun regenReq(body: Map<String, Any?> = emptyMap()) =
        post("${base()}/regenerate").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
            .content(json(body))

    private fun continueReq() =
        post("${base()}/continue").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
            .content("{}")

    private fun send(content: String): List<SseEvent> = sse(sendReq(content))

    private fun List<SseEvent>.done(): JsonNode =
        objectMapper.readTree(single { it.name == "done" }.data)

    private fun List<SseEvent>.deltaText() = filter { it.name == "delta" }.joinToString("") { it.data }

    private fun messages() = messageRepository.findByStoryIdOrderBySeqAsc(storyId)

    // ---- 전송 ----

    @Test
    fun `전송하면 유저 메시지 저장, user 이벤트, delta, 응답 저장 후 done 순서로 온다`() {
        respondWith("*비가 내렸다.* \"왔구나.\" 그녀가 말했다. 긴 응답이 여러 조각으로 나뉘어 흘러간다.")

        val events = send("안녕")

        assertThat(events.first().name).isEqualTo("user")
        assertThat(events.last().name).isEqualTo("done")
        assertThat(events.count { it.name == "delta" }).isGreaterThan(1)
        assertThat(events.deltaText()).isEqualTo("*비가 내렸다.* \"왔구나.\" 그녀가 말했다. 긴 응답이 여러 조각으로 나뉘어 흘러간다.")

        val user = objectMapper.readTree(events.first().data)
        assertThat(user["role"].asText()).isEqualTo("USER")
        assertThat(user["content"].asText()).isEqualTo("안녕")
        assertThat(user["turn"].asInt()).isEqualTo(1)

        val done = events.done()
        assertThat(done["role"].asText()).isEqualTo("ASSISTANT")
        assertThat(done["turn"].asInt()).isEqualTo(1)
        assertThat(done["variantIndex"].asInt()).isEqualTo(0)
        assertThat(done["variantCount"].asInt()).isEqualTo(1)

        val saved = messages()
        assertThat(saved.map { it.role }).containsExactly(MessageRole.USER, MessageRole.ASSISTANT)
        assertThat(saved[1].id).isEqualTo(done["id"].asLong())
        assertThat(hook.events.single().mode).isEqualTo(GenerationMode.SEND)
        assertThat(hook.events.single().turnCount).isEqualTo(1)

        mockMvc.perform(get(base()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.story.turnCount").value(1))
            .andExpect(jsonPath("$.story.recordedThroughTurn").value(0))
            .andExpect(jsonPath("$.story.generating").value(false))
            .andExpect(jsonPath("$.messages.length()").value(2))
            .andExpect(jsonPath("$.messages[1].content").value(saved[1].content))
    }

    @Test
    fun `AI 요청에는 저장된 대화 전체가 들어가고 마지막은 이번 유저 메시지다`() {
        send("첫 마디")
        send("둘째 마디")

        val last = requests.last()
        assertThat(last.purpose).isEqualTo(AiPurpose.CHAT)
        assertThat(last.messages.map { it.role to it.content }).containsExactly(
            AiRole.USER to "첫 마디", AiRole.ASSISTANT to "응답", AiRole.USER to "둘째 마디",
        )
    }

    @Test
    fun `빈 내용은 400이고 아무것도 저장하지 않는다`() {
        mockMvc.perform(sendReq("  ")).andExpect(status().isBadRequest)
        assertThat(messages()).isEmpty()
    }

    @Test
    fun `없는 스토리는 404`() {
        mockMvc.perform(
            post("/api/stories/999999/messages").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content(json(mapOf("content" to "a")))
        ).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/stories/999999/messages")).andExpect(status().isNotFound)
    }

    // ---- 감정 태그 ----

    @Test
    fun `감정 태그는 delta와 저장 본문 어디에도 없고 emotion 칼럼에만 저장된다`() {
        // Fake는 3~5조각으로 나눈다. 태그가 길어서 첫 조각 경계에 걸려 여러 조각으로 쪼개진다.
        val tag = "[감정: 경계심, 호기심, 두려움, 망설임, 그리움, 분노]"
        val body = "*그녀는 한참을 말없이 창밖을 보았다.*\n\"...늦었네.\""
        respondWith("$tag\n\n$body")

        val events = send("문을 연다")

        val deltas = events.filter { it.name == "delta" }
        assertThat(deltas.size).isGreaterThan(1)
        assertThat(deltas).noneMatch { it.data.contains("감정") || it.data.contains("[") }
        assertThat(events.deltaText()).isEqualTo(body)

        val done = events.done()
        assertThat(done["content"].asText()).isEqualTo(body)
        assertThat(done.has("emotion")).isFalse()

        val assistant = messages().last()
        assertThat(assistant.content).isEqualTo(body)
        assertThat(assistant.emotion).isEqualTo("경계심, 호기심, 두려움, 망설임, 그리움, 분노")
        assertThat(variantRepository.findByMessageIdAndVariantIndex(assistant.id, 0)!!.emotion)
            .isEqualTo(assistant.emotion)

        val listJson = mockMvc.perform(get(base())).andReturn().response.getContentAsString(Charsets.UTF_8)
        assertThat(listJson).doesNotContain("emotion").doesNotContain("[감정")

        // 다음 요청의 대화 기록에도 태그가 없다
        send("다음")
        assertThat(requests.last().messages).noneMatch { it.content.contains("[감정") }
    }

    @Test
    fun `태그가 없는 응답은 그대로 저장하고 emotion은 비어 있다`() {
        respondWith("태그 없는 응답")
        send("안녕")
        assertThat(messages().last().content).isEqualTo("태그 없는 응답")
        assertThat(messages().last().emotion).isNull()
    }

    // ---- 재생성 ----

    @Test
    fun `재생성하면 후보가 쌓이고 지시가 기록되며 후보를 골라 되돌릴 수 있다`() {
        respondWith("[감정: 평온]\n첫 답", "[감정: 긴장]\n둘째 답", "셋째 답")
        send("안녕")
        val assistantId = messages().last().id

        val second = sse(regenReq(mapOf("instruction" to "더 긴장감 있게", "messageId" to assistantId))).done()
        assertThat(second["id"].asLong()).isEqualTo(assistantId)
        assertThat(second["content"].asText()).isEqualTo("둘째 답")
        assertThat(second["variantIndex"].asInt()).isEqualTo(1)
        assertThat(second["variantCount"].asInt()).isEqualTo(2)

        // 재생성 요청에는 이전 응답이 빠지고, 지시는 마지막 유저 메시지 앞에 붙는다
        assertThat(requests.last().messages.map { it.role to it.content }).containsExactly(
            AiRole.USER to "[지시]\n더 긴장감 있게\n\n안녕",
        )
        assertThat(variantRepository.findByMessageIdAndVariantIndex(assistantId, 1)!!.instruction).isEqualTo("더 긴장감 있게")
        assertThat(messageRepository.findById(assistantId).get().emotion).isEqualTo("긴장")

        val third = sse(regenReq()).done()
        assertThat(third["variantCount"].asInt()).isEqualTo(3)
        assertThat(third["content"].asText()).isEqualTo("셋째 답")
        assertThat(hook.events.map { it.mode }).containsExactly(
            GenerationMode.SEND, GenerationMode.REGENERATE, GenerationMode.REGENERATE,
        )

        mockMvc.perform(
            put("${base()}/$assistantId/variant").contentType(MediaType.APPLICATION_JSON).content(json(mapOf("index" to 0)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").value("첫 답"))
            .andExpect(jsonPath("$.variantIndex").value(0))
            .andExpect(jsonPath("$.variantCount").value(3))
        val selected = messageRepository.findById(assistantId).get()
        assertThat(selected.content).isEqualTo("첫 답")
        assertThat(selected.emotion).isEqualTo("평온")

        // 선택된 후보가 다음 요청의 기록이 된다
        send("다음")
        assertThat(requests.last().messages[1].content).isEqualTo("첫 답")
    }

    @Test
    fun `과거 메시지 재생성과 후보 선택은 400`() {
        send("하나")
        val oldAssistant = messages().last()
        val oldUser = messages().first()
        send("둘")

        mockMvc.perform(regenReq(mapOf("messageId" to oldAssistant.id))).andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value(400))
        mockMvc.perform(regenReq(mapOf("messageId" to oldUser.id))).andExpect(status().isBadRequest)
        mockMvc.perform(
            put("${base()}/${oldAssistant.id}/variant").contentType(MediaType.APPLICATION_JSON).content(json(mapOf("index" to 0)))
        ).andExpect(status().isBadRequest)

        assertThat(variantRepository.findByMessageIdOrderByVariantIndexAsc(oldAssistant.id)).hasSize(1)
        // 락이 풀려 있어 최신 응답 재생성은 된다
        val latest = messages().last()
        assertThat(sse(regenReq(mapOf("messageId" to latest.id))).done()["variantCount"].asInt()).isEqualTo(2)
    }

    @Test
    fun `프롤로그 재생성과 빈 스토리 재생성은 400`() {
        mockMvc.perform(regenReq()).andExpect(status().isBadRequest)
        messageService.appendAssistant(storyId, "프롤로그", kind = MessageKind.PROLOGUE)
        mockMvc.perform(regenReq()).andExpect(status().isBadRequest)
    }

    @Test
    fun `프롤로그만 있는 스토리에서 프롤로그 ID로 재생성해도 400이고 수정은 된다`() {
        val prologue = messageService.appendAssistant(storyId, "프롤로그", kind = MessageKind.PROLOGUE)

        mockMvc.perform(regenReq(mapOf("messageId" to prologue.id))).andExpect(status().isBadRequest)
        assertThat(variantRepository.findByMessageIdOrderByVariantIndexAsc(prologue.id)).hasSize(1)
        assertThat(requests).isEmpty()

        mockMvc.perform(
            patch("${base()}/${prologue.id}").contentType(MediaType.APPLICATION_JSON).content(json(mapOf("content" to "고친 프롤로그")))
        ).andExpect(status().isOk).andExpect(jsonPath("$.content").value("고친 프롤로그"))
        assertThat(messageRepository.findById(prologue.id).get().content).isEqualTo("고친 프롤로그")
    }

    @Test
    fun `다른 스토리의 메시지 ID로 재생성하면 404`() {
        send("하나")
        val otherStory = storyRepository.save(Story(scenarioId = 1L, title = "다른", dirName = "2000"))
        val foreign = messageService.appendUser(otherStory.id, "남의 메시지")
        mockMvc.perform(regenReq(mapOf("messageId" to foreign.id))).andExpect(status().isNotFound)
    }

    // ---- 실패 시 원본 보존 ----

    @Test
    fun `재생성 스트림이 실패하면 error를 보내고 원본은 그대로다`() {
        send("안녕")
        val assistant = messages().last()

        fakeResponses.register(AiPurpose.CHAT) { throw IllegalStateException("provider down") }
        val events = sse(regenReq())

        assertThat(events.map { it.name }).containsExactly("error")
        assertThat(events.single().data).isEqualTo("provider down")
        val after = messageRepository.findById(assistant.id).get()
        assertThat(after.content).isEqualTo("응답")
        assertThat(after.selectedVariant).isEqualTo(0)
        assertThat(variantRepository.findByMessageIdOrderByVariantIndexAsc(assistant.id)).hasSize(1)
        assertThat(hook.events).hasSize(1)
    }

    @Test
    fun `조각을 보낸 뒤 실패해도 아무것도 저장하지 않는다`() {
        send("안녕")
        scripted.script = { _, listener ->
            listener.onDelta("반쯤 쓰다가")
            listener.onError(RuntimeException("stream broken"))
        }

        val events = sse(regenReq(mapOf("provider" to ScriptedAiProvider.NAME)))

        assertThat(events.map { it.name }).containsExactly("delta", "error")
        assertThat(messages().last().content).isEqualTo("응답")
        assertThat(variantRepository.findByMessageIdOrderByVariantIndexAsc(messages().last().id)).hasSize(1)
    }

    @Test
    fun `전송이 실패하면 유저 메시지는 남고 재생성으로 같은 턴의 첫 응답을 만든다`() {
        fakeResponses.register(AiPurpose.CHAT) { throw IllegalStateException("provider down") }
        val events = send("안녕")
        assertThat(events.map { it.name }).containsExactly("user", "error")
        assertThat(messages().map { it.role }).containsExactly(MessageRole.USER)

        respondWith("복구된 응답")
        val done = sse(regenReq()).done()

        assertThat(done["content"].asText()).isEqualTo("복구된 응답")
        assertThat(done["turn"].asInt()).isEqualTo(1)
        assertThat(done["variantCount"].asInt()).isEqualTo(1)
        assertThat(messages().map { it.role }).containsExactly(MessageRole.USER, MessageRole.ASSISTANT)
    }

    @Test
    fun `빈 응답은 저장하지 않고 error를 보낸다`() {
        respondWith("[감정: 무표정]\n   ")
        val events = send("안녕")
        assertThat(events.map { it.name }).containsExactly("user", "error")
        assertThat(messages().map { it.role }).containsExactly(MessageRole.USER)
    }

    // ---- 수정, 삭제 ----

    @Test
    fun `유저와 AI 메시지를 수정하면 다음 요청 컨텍스트에 반영된다`() {
        send("원래 대사")
        val (user, assistant) = messages()

        mockMvc.perform(
            patch("${base()}/${user.id}").contentType(MediaType.APPLICATION_JSON).content(json(mapOf("content" to "고친 대사")))
        ).andExpect(status().isOk).andExpect(jsonPath("$.edited").value(true))
        mockMvc.perform(
            patch("${base()}/${assistant.id}").contentType(MediaType.APPLICATION_JSON).content(json(mapOf("content" to "고친 응답")))
        ).andExpect(status().isOk).andExpect(jsonPath("$.content").value("고친 응답"))

        send("다음")

        assertThat(requests.last().messages.map { it.content }).containsExactly("고친 대사", "고친 응답", "다음")
        mockMvc.perform(get(base()))
            .andExpect(jsonPath("$.messages[0].edited").value(true))
            .andExpect(jsonPath("$.messages[2].edited").value(false))
    }

    @Test
    fun `빈 내용으로 수정하면 400, 다른 스토리의 메시지는 404`() {
        send("안녕")
        val user = messages().first()
        mockMvc.perform(
            patch("${base()}/${user.id}").contentType(MediaType.APPLICATION_JSON).content(json(mapOf("content" to "")))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            patch("/api/stories/999999/messages/${user.id}").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("content" to "x")))
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `삭제하면 그 메시지부터 끝까지 지우고 턴 수를 다시 계산한다`() {
        send("하나")
        send("둘")
        val secondUser = messages()[2]

        mockMvc.perform(delete("${base()}/${secondUser.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deletedCount").value(2))
            .andExpect(jsonPath("$.turnCount").value(1))
        assertThat(messages()).hasSize(2)
    }

    // ---- 이어쓰기 ----

    @Test
    fun `이어쓰기는 지시문을 저장하지 않고 CONTINUATION 새 턴을 만든다`() {
        respondWith("첫 응답", "[감정: 몰입]\n이어진 장면")
        send("안녕")

        val events = sse(continueReq())

        assertThat(events.map { it.name }.first()).isEqualTo("delta")
        val done = events.done()
        assertThat(done["kind"].asText()).isEqualTo("CONTINUATION")
        assertThat(done["turn"].asInt()).isEqualTo(2)
        assertThat(done["content"].asText()).isEqualTo("이어진 장면")

        val saved = messages()
        assertThat(saved.map { it.role }).containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT)
        assertThat(saved).noneMatch { it.content.contains("[지시]") || it.content.contains(ConversationBuilder.CONTINUE_INSTRUCTION) }

        val request = requests.last()
        assertThat(request.messages.last().role).isEqualTo(AiRole.USER)
        assertThat(request.messages.last().content).contains(ConversationBuilder.CONTINUE_INSTRUCTION)
        assertThat(hook.events.last().mode).isEqualTo(GenerationMode.CONTINUE)

        // 다음 전송의 대화 기록에도 지시문이 없다
        send("다음")
        assertThat(requests.last().messages).noneMatch { it.content.contains(ConversationBuilder.CONTINUE_INSTRUCTION) }
    }

    @Test
    fun `이어쓰기 응답을 재생성하면 이어쓰기 지시를 다시 넣고 후보로 쌓는다`() {
        respondWith("첫 응답", "이어쓰기 A", "이어쓰기 B")
        send("안녕")
        sse(continueReq())

        val done = sse(regenReq(mapOf("instruction" to "대사 위주로"))).done()

        assertThat(done["kind"].asText()).isEqualTo("CONTINUATION")
        assertThat(done["variantCount"].asInt()).isEqualTo(2)
        val last = requests.last().messages.last()
        assertThat(last.role).isEqualTo(AiRole.USER)
        assertThat(last.content).isEqualTo("[지시]\n${ConversationBuilder.CONTINUE_INSTRUCTION}\n대사 위주로")
        assertThat(requests.last().messages).noneMatch { it.content == "이어쓰기 A" }
    }

    @Test
    fun `마지막이 유저 메시지거나 대화가 없으면 이어쓰기는 400`() {
        mockMvc.perform(continueReq()).andExpect(status().isBadRequest)
        messageService.appendUser(storyId, "응답 없는 유저 메시지")
        mockMvc.perform(continueReq()).andExpect(status().isBadRequest)
    }

    @Test
    fun `프롤로그만 있어도 이어쓰기할 수 있다`() {
        messageService.appendAssistant(storyId, "프롤로그", kind = MessageKind.PROLOGUE)
        val done = sse(continueReq()).done()
        assertThat(done["turn"].asInt()).isEqualTo(1)
        assertThat(done["kind"].asText()).isEqualTo("CONTINUATION")
    }

    // ---- 동시성, 연결 끊김 ----

    @Test
    fun `생성 중에는 다른 생성과 수정이 409이고 끝나면 다시 된다`() {
        send("안녕")
        val assistant = messages().last()
        val release = CountDownLatch(1)
        scripted.script = { _, listener ->
            listener.onDelta("생각 중")
            release.await(10, TimeUnit.SECONDS)
            listener.onComplete("느린 응답")
        }

        val running = start(sendReq("느린 요청", ScriptedAiProvider.NAME))

        mockMvc.perform(sendReq("끼어들기")).andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value(409))
        mockMvc.perform(regenReq()).andExpect(status().isConflict)
        mockMvc.perform(continueReq()).andExpect(status().isConflict)
        mockMvc.perform(
            patch("${base()}/${assistant.id}").contentType(MediaType.APPLICATION_JSON).content(json(mapOf("content" to "x")))
        ).andExpect(status().isConflict)
        mockMvc.perform(delete("${base()}/${assistant.id}")).andExpect(status().isConflict)
        mockMvc.perform(get(base())).andExpect(jsonPath("$.story.generating").value(true))

        release.countDown()
        val events = finish(running)
        assertThat(events.done()["content"].asText()).isEqualTo("느린 응답")
        assertThat(messages().map { it.content }).containsExactly("안녕", "응답", "느린 요청", "느린 응답")

        mockMvc.perform(get(base())).andExpect(jsonPath("$.story.generating").value(false))
        send("이제 된다")
        assertThat(messages()).hasSize(6)
    }

    @Test
    fun `클라이언트가 끊겨도 응답은 끝까지 저장된다`() {
        val release = CountDownLatch(1)
        scripted.script = { _, listener ->
            release.await(10, TimeUnit.SECONDS)
            listener.onDelta("끊긴 뒤에 ")
            listener.onDelta("온 응답")
            listener.onComplete("끊긴 뒤에 온 응답")
        }

        val emitter = chatFlowService.send(storyId, "안녕", ScriptedAiProvider.NAME, null)
        emitter.complete() // 클라이언트 연결이 먼저 닫힌 상황: 이후 전송은 모두 실패한다
        release.countDown()

        awaitUntil(message = "응답 저장") { messages().size == 2 }
        assertThat(messages().last().content).isEqualTo("끊긴 뒤에 온 응답")
        awaitUntil(message = "락 해제") { !chatFlowService.state(storyId).story.generating }
    }

    // ---- 내보내기 ----

    @Test
    fun `마크다운으로 내보낸다`() {
        send("안녕")
        mockMvc.perform(get("${base()}/export"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType("text", "markdown")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("## 턴 1 · USER")))
    }
}
