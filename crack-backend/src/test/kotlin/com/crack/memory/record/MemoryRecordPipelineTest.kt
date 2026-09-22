package com.crack.memory.record

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.fake.FakeRecordResponder
import com.crack.ai.provider.FakeResponses
import com.crack.chat.api.awaitUntil
import com.crack.chat.flow.AfterTurnEvent
import com.crack.chat.flow.GenerationMode
import com.crack.global.config.DataPaths
import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.Chronicle
import com.crack.memory.docs.MemoryBudgets
import com.crack.memory.docs.ProtagonistDoc
import com.crack.memory.docs.StoryState
import com.crack.message.entity.MessageRole
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.files.SampleScenario
import com.crack.story.files.StoryFiles
import com.crack.story.repository.StoryRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 기억 기록 파이프라인 통합 테스트 (T14 완료 조건). Fake 프로바이더의 기록 응답([FakeRecordResponder])으로 파이프라인 전체를 돈다.
 * 기록은 배경 스레드에서 돌므로 상태가 바뀔 때까지 기다린다. 스토리는 테스트마다 새로 만든다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
class MemoryRecordPipelineTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var service: MemoryRecordService
    @Autowired lateinit var afterTurnHook: MemoryRecordAfterTurnHook
    @Autowired lateinit var recordRepository: MemoryRecordRepository
    @Autowired lateinit var fakeResponses: FakeResponses
    @Autowired lateinit var fakeRecord: FakeRecordResponder
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var messageRepository: StoryMessageRepository
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var dataPaths: DataPaths
    @Autowired lateinit var budgets: MemoryBudgets

    private lateinit var scenario: Scenario
    private lateinit var scenarioDir: Path
    private lateinit var storyDir: Path
    private var storyId: Long = 0

    @BeforeEach
    fun setUp() {
        fakeResponses.reset()
        fakeRecord.install()
        val name = "record-" + UUID.randomUUID().toString().take(8)
        scenarioDir = SampleScenario.copyTo(dataPaths.scenarioDir(name))
        scenario = scenarioRepository.save(Scenario(name = name, title = "기록 테스트"))
        storyDir = scenarioDir.resolve("stories/1700000000000")
        StoryFiles.initFromScenario(scenarioDir, storyDir)
        storyId = storyRepository.save(Story(scenarioId = scenario.id, title = "스토리", dirName = "1700000000000")).id
    }

    @AfterEach
    fun tearDown() {
        awaitUntil(message = "기록 종료") { !service.isRunning(storyId) }
        fakeResponses.reset()
        storyRepository.findByScenarioIdOrderByUpdatedAtDesc(scenario.id).forEach { storyRepository.delete(it) }
        scenarioRepository.delete(scenario)
        if (Files.exists(scenarioDir)) {
            Files.walk(scenarioDir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    // ---- 도우미 ----

    /** 유저·응답 한 쌍으로 된 턴을 [count]개 추가한다. 유저 메시지에 설월이 나온다. */
    private fun addTurns(count: Int) {
        repeat(count) {
            val user = messageService.appendUser(storyId, "설월과 함께 길을 걷는다")
            messageService.appendAssistant(storyId, "설월이 조용히 따라온다.", turnNo = user.turnNo)
        }
    }

    private fun story(): Story = storyRepository.findById(storyId).get()

    private fun awaitFinished(recordId: Long): MemoryRecord {
        awaitUntil(10_000, "기록 $recordId 종료") {
            recordRepository.findById(recordId).get().status != RecordStatus.RUNNING && !service.isRunning(storyId)
        }
        return recordRepository.findById(recordId).get()
    }

    private fun recordNow(): MemoryRecord {
        val response = service.trigger(storyId, RecordReason.MANUAL)
        assertThat(response.result).isEqualTo(TriggerResult.STARTED)
        return awaitFinished(response.record!!.id)
    }

    /** 스토리 폴더의 문서 스냅샷(기록 이력 폴더 제외) */
    private fun docs(): Map<String, String> =
        Files.walk(storyDir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            .map { storyDir.relativize(it).toString() }
            .filter { !it.startsWith("memory") }
            .associateWith { Files.readString(storyDir.resolve(it)) }

    private fun read(rel: String) = Files.readString(storyDir.resolve(rel))

    private fun isScenarioCall(req: AiRequest) = req.systemPrompt == RecordPrompts.SCENARIO_MANAGER
    private fun isCharacterCall(req: AiRequest) = req.systemPrompt == RecordPrompts.CHARACTER_MANAGER

    private fun respondRecord(responder: (AiRequest) -> String) = fakeResponses.register(AiPurpose.RECORD, responder)

    // ---- 테스트 ----

    @Test
    fun `10턴째 응답이 저장되면 자동 기록되고 인물·주인공·연대기·state가 갱신되며 원본 설정은 그대로다`() {
        val originalSeolwol = CharacterDoc.read(storyDir.resolve("characters/설월.md"))
        val originalMugeuk = read("characters/무극.md")
        val originalProtagonist = ProtagonistDoc.read(storyDir.resolve("characters/protagonist.md"))

        addTurns(9)
        // 9턴까지는 트리거하지 않는다
        afterTurnHook.afterTurn(AfterTurnEvent(storyId, 0, 9, 9, GenerationMode.SEND))
        assertThat(recordRepository.findByStoryIdOrderByIdDesc(storyId)).isEmpty()

        // 10턴째는 실제 채팅 흐름(SSE)으로 보내 AfterTurnHook을 거친다
        val result = mockMvc.perform(
            post("/api/stories/$storyId/messages").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content(objectMapper.writeValueAsString(mapOf("content" to "설월, 저기 흑풍채가 보인다")))
        ).andExpect(request().asyncStarted()).andReturn()
        result.getAsyncResult(10_000)
        mockMvc.perform(asyncDispatch(result))

        awaitUntil(10_000, "자동 기록 생성") { recordRepository.findByStoryIdOrderByIdDesc(storyId).isNotEmpty() }
        val record = awaitFinished(recordRepository.findByStoryIdOrderByIdDesc(storyId).single().id)

        assertThat(record.status).isEqualTo(RecordStatus.DONE)
        assertThat(record.reason).isEqualTo(RecordReason.AUTO)
        assertThat(record.fromTurn to record.toTurn).isEqualTo(1 to 10)
        assertThat(story().recordedThroughTurn).isEqualTo(10)
        assertThat(record.changedFileList).containsExactlyInAnyOrder(
            "chronicle.md", "state.json", "characters/설월.md", "characters/protagonist.md",
        )

        // 인물: ## 기억만 바뀌고 원본 설정 부분은 그대로
        val seolwol = CharacterDoc.read(storyDir.resolve("characters/설월.md"))
        assertThat(seolwol.memorySection).contains("- 주인공: 함께 행동함 (t10)", "- t1–10: 설월의 기록 (fake)")
        assertThat(seolwol.text.substringBefore("## 기억")).isEqualTo(originalSeolwol.text.substringBefore("## 기억"))
        assertThat(read("characters/무극.md")).isEqualTo(originalMugeuk) // 원문에 나오지 않은 인물

        // 주인공: ## 변화 기록만
        val protagonist = ProtagonistDoc.read(storyDir.resolve("characters/protagonist.md"))
        assertThat(protagonist.changesSection).contains("- 설월: 함께 행동함 (t10)")
        assertThat(protagonist.text.substringBefore("## 변화 기록")).isEqualTo(originalProtagonist.text.substringBefore("## 변화 기록"))

        // 연대기와 state
        val chronicle = Chronicle.read(storyDir.resolve("chronicle.md"))
        assertThat(chronicle.entries().map { Triple(it.number, it.fromTurn, it.toTurn) }).containsExactly(Triple(1, 1, 10))
        val state = StoryState.read(storyDir.resolve("state.json"))
        assertThat(state.companions).containsExactly("설월")
        assertThat(state.updatedAtTurn).isEqualTo(10)

        // 스냅샷
        assertThat(Files.readString(storyDir.resolve("memory/history/${record.id}/before/characters/설월.md"))).isEqualTo(originalSeolwol.text)

        // GET /messages의 story.memory와 읽음 처리
        mockMvc.perform(get("/api/stories/$storyId/messages"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.story.recordedThroughTurn").value(10))
            .andExpect(jsonPath("$.story.memory.status").value("DONE"))
            .andExpect(jsonPath("$.story.memory.lastRecordId").value(record.id))
            .andExpect(jsonPath("$.story.memory.unseen").value(true))
        mockMvc.perform(post("/api/stories/$storyId/memory/records/seen")).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/stories/$storyId/messages"))
            .andExpect(jsonPath("$.story.memory.unseen").value(false))
    }

    @Test
    fun `재생성은 자동 기록을 트리거하지 않는다`() {
        addTurns(10)
        afterTurnHook.afterTurn(AfterTurnEvent(storyId, 0, 10, 10, GenerationMode.REGENERATE))
        assertThat(recordRepository.findByStoryIdOrderByIdDesc(storyId)).isEmpty()
    }

    @Test
    fun `실패를 주입하면 1회 재시도 후 FAILED이고 파일은 하나도 바뀌지 않는다`() {
        addTurns(3)
        val before = docs()
        val scenarioCalls = AtomicInteger()
        respondRecord { req ->
            if (isScenarioCall(req)) scenarioCalls.incrementAndGet()
            if (isCharacterCall(req)) throw IllegalStateException("캐릭터 관리자 실패 주입")
            fakeRecord.respond(req)
        }

        val record = recordNow()

        assertThat(record.status).isEqualTo(RecordStatus.FAILED)
        assertThat(record.error).contains("캐릭터 관리자 실패 주입")
        assertThat(scenarioCalls.get()).isEqualTo(2) // 최초 + 재시도
        assertThat(docs()).isEqualTo(before)
        assertThat(Files.exists(storyDir.resolve("memory/history"))).isFalse()
        assertThat(story().recordedThroughTurn).isEqualTo(0)
        assertThat(service.status(storyId)).isEqualTo(MemoryStatusView("FAILED", record.id, true))
    }

    @Test
    fun `형식이 틀린 출력도 실패로 처리하고 첫 시도만 실패하면 재시도로 성공한다`() {
        addTurns(2)
        respondRecord { "형식 없는 응답" }
        assertThat(recordNow().status).isEqualTo(RecordStatus.FAILED)

        val calls = AtomicInteger()
        respondRecord { req -> if (isScenarioCall(req) && calls.incrementAndGet() == 1) "<chronicle></chronicle>" else fakeRecord.respond(req) }
        val record = recordNow()
        assertThat(record.status).isEqualTo(RecordStatus.DONE)
        assertThat(record.fromTurn to record.toTurn).isEqualTo(1 to 2)
    }

    @Test
    fun `되돌리기는 파일과 recorded_through를 복원하고 가장 최근 DONE만 허용한다`() {
        val original = docs()
        addTurns(3)
        val first = recordNow()
        val afterFirst = docs()
        addTurns(2)
        val second = recordNow()
        assertThat(second.fromTurn to second.toTurn).isEqualTo(4 to 5)
        assertThat(story().recordedThroughTurn).isEqualTo(5)

        // 최근 DONE이 아니면 400
        mockMvc.perform(post("/api/stories/$storyId/memory/records/${first.id}/revert")).andExpect(status().isBadRequest)

        mockMvc.perform(post("/api/stories/$storyId/memory/records/${second.id}/revert"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REVERTED"))
        assertThat(docs()).isEqualTo(afterFirst)
        assertThat(story().recordedThroughTurn).isEqualTo(3)
        mockMvc.perform(post("/api/stories/$storyId/memory/records/${second.id}/revert")).andExpect(status().isBadRequest)

        assertThat(service.list(storyId).map { it.id to it.revertable }).containsExactly(second.id to false, first.id to true)
        service.revert(storyId, first.id)
        assertThat(docs()).isEqualTo(original)
        assertThat(story().recordedThroughTurn).isEqualTo(0)

        // 되돌린 뒤 다시 기록하면 같은 범위를 다시 기록한다
        val again = recordNow()
        assertThat(again.fromTurn to again.toTurn).isEqualTo(1 to 5)
    }

    @Test
    fun `기록 범위를 자르는 삭제는 최근 기록부터 연쇄로 되돌린다`() {
        val original = docs()
        addTurns(10)
        val first = recordNow()
        val afterFirst = docs()
        addTurns(10)
        val second = recordNow()
        assertThat(story().recordedThroughTurn).isEqualTo(20)

        // 기록 범위 밖(턴 21 이후)이 아닌 턴 15를 지우면 두 번째 기록만 되돌린다
        val turn15 = messageRepository.findByStoryIdOrderBySeqAsc(storyId).first { it.turnNo == 15 && it.role == MessageRole.USER }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/stories/$storyId/messages/${turn15.id}"))
            .andExpect(status().isOk)
        assertThat(recordRepository.findById(second.id).get().status).isEqualTo(RecordStatus.REVERTED)
        assertThat(recordRepository.findById(first.id).get().status).isEqualTo(RecordStatus.DONE)
        assertThat(story().recordedThroughTurn).isEqualTo(10)
        assertThat(docs()).isEqualTo(afterFirst)

        // 턴 5를 지우면 첫 기록도 되돌린다
        val turn5 = messageRepository.findByStoryIdOrderBySeqAsc(storyId).first { it.turnNo == 5 && it.role == MessageRole.USER }
        messageService.truncateFrom(turn5.id)
        assertThat(recordRepository.findById(first.id).get().status).isEqualTo(RecordStatus.REVERTED)
        assertThat(story().recordedThroughTurn).isEqualTo(0)
        assertThat(docs()).isEqualTo(original)
    }

    @Test
    fun `기록 범위 밖 삭제는 기록을 건드리지 않고, 되돌릴 기록이 없으면 턴만 내린다`() {
        addTurns(5)
        val record = recordNow()
        addTurns(2)
        val turn7 = messageRepository.findByStoryIdOrderBySeqAsc(storyId).first { it.turnNo == 7 }
        messageService.truncateFrom(turn7.id)
        assertThat(recordRepository.findById(record.id).get().status).isEqualTo(RecordStatus.DONE)
        assertThat(story().recordedThroughTurn).isEqualTo(5)

        // 분기 스토리처럼 기록 이력 없이 recorded_through만 있는 경우
        storyRepository.save(story().apply { recordedThroughTurn = 6 })
        recordRepository.save(recordRepository.findById(record.id).get().apply { status = RecordStatus.REVERTED })
        val turn3 = messageRepository.findByStoryIdOrderBySeqAsc(storyId).first { it.turnNo == 3 }
        messageService.truncateFrom(turn3.id)
        assertThat(story().recordedThroughTurn).isEqualTo(2)
    }

    @Test
    fun `기록된 과거 턴을 고치면 다음 기록에서 재반영한다`() {
        addTurns(10)
        val first = recordNow()
        assertThat(first.rerecordedTurnList).isEmpty()

        val turn3 = messageRepository.findByStoryIdOrderBySeqAsc(storyId).first { it.turnNo == 3 && it.role == MessageRole.USER }
        messageService.edit(turn3.id, "설월에게 옥패를 건넨다")

        val second = recordNow()
        assertThat(second.status).isEqualTo(RecordStatus.DONE)
        assertThat(second.rerecordedTurnList).containsExactly(3)
        assertThat(second.hasNewTurns).isFalse()
        assertThat(second.fromTurn to second.toTurn).isEqualTo(11 to 10)
        assertThat(story().recordedThroughTurn).isEqualTo(10)

        // 연대기 회차 1이 고쳐지고, 새 회차는 생기지 않는다
        val chronicle = Chronicle.read(storyDir.resolve("chronicle.md"))
        assertThat(chronicle.entries()).hasSize(1)
        assertThat(chronicle.entries().single().body).contains("고친 대화를 반영함 (fake 기록, t3)")
        assertThat(read("characters/설월.md")).contains("t3: 고친 대화를 반영함 (fake)")

        // 이미 재반영한 수정은 다시 잡지 않는다
        assertThat(service.trigger(storyId, RecordReason.MANUAL).result).isEqualTo(TriggerResult.NOTHING_TO_RECORD)

        // 재반영 기록을 되돌리면 recorded_through는 그대로(빈 범위)
        service.revert(storyId, second.id)
        assertThat(story().recordedThroughTurn).isEqualTo(10)
    }

    @Test
    fun `싱글 플라이트 - 실행 중에는 새 기록과 되돌리기를 막는다`() {
        addTurns(3)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        respondRecord { req ->
            if (isScenarioCall(req)) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            fakeRecord.respond(req)
        }

        val started = service.trigger(storyId, RecordReason.MANUAL)
        assertThat(started.result).isEqualTo(TriggerResult.STARTED)
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()

        val again = service.trigger(storyId, RecordReason.AUTO)
        assertThat(again.result).isEqualTo(TriggerResult.ALREADY_RUNNING)
        assertThat(again.record!!.id).isEqualTo(started.record!!.id)
        mockMvc.perform(post("/api/stories/$storyId/memory/record"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("ALREADY_RUNNING"))
        assertThat(service.status(storyId).status).isEqualTo("RUNNING")
        assertThatThrownBy { service.revert(storyId, started.record!!.id) }.isInstanceOf(MemoryRecordConflictException::class.java)
        mockMvc.perform(post("/api/stories/$storyId/memory/records/${started.record!!.id}/revert")).andExpect(status().isConflict)

        release.countDown()
        assertThat(awaitFinished(started.record!!.id).status).isEqualTo(RecordStatus.DONE)
        assertThat(recordRepository.findByStoryIdOrderByIdDesc(storyId)).hasSize(1)
        assertThat(service.trigger(storyId, RecordReason.MANUAL).result).isEqualTo(TriggerResult.NOTHING_TO_RECORD)
    }

    @Test
    fun `실행 중에 기록 범위를 자르면 그 기록은 취소되고 파일을 쓰지 않는다`() {
        addTurns(4)
        val before = docs()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        respondRecord { req ->
            if (isScenarioCall(req)) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            fakeRecord.respond(req)
        }
        val started = service.trigger(storyId, RecordReason.MANUAL)
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()

        val turn2 = messageRepository.findByStoryIdOrderBySeqAsc(storyId).first { it.turnNo == 2 }
        messageService.truncateFrom(turn2.id)
        release.countDown()

        val record = awaitFinished(started.record!!.id)
        assertThat(record.status).isEqualTo(RecordStatus.FAILED)
        assertThat(record.error).contains("삭제")
        assertThat(docs()).isEqualTo(before)
        assertThat(story().recordedThroughTurn).isEqualTo(0)
    }

    @Test
    fun `기록 도중 사용자가 문서를 고치면 그 시도를 버리고 다시 계산해 사용자 수정을 보존한다`() {
        addTurns(2)
        val calls = AtomicInteger()
        val path = storyDir.resolve("characters/설월.md")
        respondRecord { req ->
            if (isScenarioCall(req) && calls.incrementAndGet() == 1) {
                Files.writeString(path, Files.readString(path).replace("## 성격\n", "## 성격\n사용자가 고친 줄.\n"))
            }
            fakeRecord.respond(req)
        }

        val record = recordNow()
        assertThat(record.status).isEqualTo(RecordStatus.DONE)
        assertThat(calls.get()).isEqualTo(2)
        assertThat(read("characters/설월.md")).contains("사용자가 고친 줄.", "설월의 기록 (fake)")
    }

    @Test
    fun `예산을 넘으면 압축 단계를 실행한다`() {
        addTurns(2)
        val path = storyDir.resolve("characters/설월.md")
        val longMemory = "### 관계\n" + (1..200).joinToString("\n") { "- 인물$it: 오래전 일 (t$it)" } + "\n### 사건\n### 소지품·기술·신체\n"
        Files.writeString(path, CharacterDoc.read(path).withMemorySection(longMemory).text)
        val longChronicle = (1..10).fold(Chronicle.empty()) { c, n ->
            c.append(com.crack.memory.docs.ChronicleEntry(n, 0, 0, "- " + "사건 ".repeat(budgets.chronicle / 20)))
        }
        Files.writeString(storyDir.resolve("chronicle.md"), longChronicle.text)
        val compressCalls = AtomicInteger()
        respondRecord { req ->
            if (req.systemPrompt == RecordPrompts.COMPRESS_SECTION || req.systemPrompt == RecordPrompts.COMPRESS_CHRONICLE) compressCalls.incrementAndGet()
            fakeRecord.respond(req)
        }

        assertThat(recordNow().status).isEqualTo(RecordStatus.DONE)

        assertThat(compressCalls.get()).isGreaterThanOrEqualTo(2)
        val seolwol = CharacterDoc.read(path)
        assertThat(budgets.exceeds(seolwol)).isFalse()
        val chronicle = Chronicle.read(storyDir.resolve("chronicle.md"))
        assertThat(chronicle.summary).contains("fake 기록")
        assertThat(chronicle.entries().last().fromTurn to chronicle.entries().last().toTurn).isEqualTo(1 to 2)
    }

    @Test
    fun `기록 API - 수동 기록, 목록, 상세(before와 현재)`() {
        mockMvc.perform(post("/api/stories/$storyId/memory/record"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("NOTHING_TO_RECORD"))
            .andExpect(jsonPath("$.record").doesNotExist())
        mockMvc.perform(get("/api/stories/$storyId/messages")).andExpect(jsonPath("$.story.memory.status").value("NONE"))

        addTurns(2)
        messageService.appendUser(storyId, "응답을 기다리는 유저 메시지") // 미완성 턴 3은 범위에서 빠진다
        val body = mockMvc.perform(post("/api/stories/$storyId/memory/record"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("STARTED"))
            .andExpect(jsonPath("$.record.reason").value("MANUAL"))
            .andExpect(jsonPath("$.record.toTurn").value(2))
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        val recordId = objectMapper.readTree(body)["record"]["id"].asLong()
        awaitFinished(recordId)

        mockMvc.perform(get("/api/stories/$storyId/memory/records"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(recordId))
            .andExpect(jsonPath("$[0].status").value("DONE"))
            .andExpect(jsonPath("$[0].revertable").value(true))
            .andExpect(jsonPath("$[0].changedFiles.length()").value(4))

        val detail = objectMapper.readTree(
            mockMvc.perform(get("/api/stories/$storyId/memory/records/$recordId"))
                .andExpect(status().isOk)
                .andReturn().response.getContentAsString(Charsets.UTF_8)
        )
        val files = detail["files"].associateBy { it["path"].asText() }
        assertThat(files.keys).contains("characters/설월.md", "chronicle.md")
        assertThat(files.getValue("chronicle.md")["before"].asText()).isEqualTo(StoryFiles.CHRONICLE_INITIAL)
        assertThat(files.getValue("chronicle.md")["current"].asText()).isEqualTo(read("chronicle.md"))

        mockMvc.perform(get("/api/stories/$storyId/memory/records/999999")).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/stories/999999/memory/records")).andExpect(status().isNotFound)
    }

    @Test
    fun `이전되지 않은 스토리는 기록이 실패한다`() {
        addTurns(1)
        Files.delete(storyDir.resolve(StoryFiles.STORY_JSON))
        val record = recordNow()
        assertThat(record.status).isEqualTo(RecordStatus.FAILED)
        assertThat(record.error).contains("이전되지 않은")
    }
}
