package com.crack.directive

import com.crack.global.config.DataPaths
import com.crack.message.service.MessageService
import com.crack.prompt.contributor.PromptSlot
import com.crack.prompt.service.PromptAssembler
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
import org.springframework.test.web.servlet.ResultMatcher
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 지속 OOC 지시 API와 BOTTOM 주입 (DESIGN.md §8.1, D10).
 * 주입은 `prompt-preview`로 확인한다(AI를 부르지 않고 실제 조립 결과를 본다).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml의 비밀번호로 AuthFilter가 끼지 않게
class DirectiveApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var assembler: PromptAssembler
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private lateinit var storyDir: Path
    private var storyId = 0L

    @BeforeEach
    fun setUp() {
        scenario = scenarioRepository.save(Scenario(name = "directive-${UUID.randomUUID().toString().take(8)}", title = "지시"))
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "스토리", dirName = "5000"))
        storyId = story.id
        storyDir = SampleScenario.copyTo(dataPaths.storyDir(scenario.name, story.dirName))
    }

    @AfterEach
    fun tearDown() {
        val dir = dataPaths.scenarioDir(scenario.name)
        if (Files.exists(dir)) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    // ---- 도우미 ----

    private fun base() = "/api/stories/$storyId/directives"

    private fun read(body: String): JsonNode = objectMapper.readTree(body)

    private fun perform(builder: org.springframework.test.web.servlet.RequestBuilder, expect: ResultMatcher): JsonNode? =
        mockMvc.perform(builder).andExpect(expect).andReturn().response.getContentAsString(Charsets.UTF_8)
            .takeIf { it.isNotBlank() }?.let(::read)

    private fun add(text: String, enabled: Boolean? = null): JsonNode =
        perform(
            post(base()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("text" to text, "enabled" to enabled))),
            status().isCreated,
        )!!

    private fun patchDirective(id: String, body: Map<String, Any?>, expect: ResultMatcher = status().isOk) =
        perform(patch("${base()}/$id").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)), expect)

    private fun list(): JsonNode = perform(get(base()), status().isOk)!!

    private fun preview(input: String? = null): JsonNode =
        perform(get("/api/stories/$storyId/prompt-preview").apply { if (input != null) param("input", input) }, status().isOk)!!

    private fun addTurns(count: Int) {
        repeat(count) { i ->
            val u = messageService.appendUser(storyId, "유저 입력 ${i + 1}")
            messageService.appendAssistant(storyId, "응답 ${i + 1}", turnNo = u.turnNo)
        }
    }

    // ---- CRUD ----

    @Test
    fun `지시를 추가하고 목록으로 보고 고치고 지운다`() {
        assertThat(list()).isEmpty()

        val first = add("  말투는 반말  ")
        assertThat(first["text"].asText()).isEqualTo("말투는 반말")
        assertThat(first["enabled"].asBoolean()).isTrue()
        assertThat(first["id"].asText()).isNotBlank()
        assertThat(first["createdAt"].asText()).isNotBlank()
        val second = add("설월은 존댓말을 쓴다", enabled = false)
        assertThat(second["enabled"].asBoolean()).isFalse()

        assertThat(list().map { it["text"].asText() }).containsExactly("말투는 반말", "설월은 존댓말을 쓴다")

        val id = first["id"].asText()
        val disabled = patchDirective(id, mapOf("enabled" to false))!!
        assertThat(disabled["enabled"].asBoolean()).isFalse()
        assertThat(disabled["text"].asText()).isEqualTo("말투는 반말")
        val renamed = patchDirective(id, mapOf("text" to "말투는 존댓말"))!!
        assertThat(renamed["text"].asText()).isEqualTo("말투는 존댓말")
        assertThat(renamed["enabled"].asBoolean()).isFalse()

        perform(delete("${base()}/$id"), status().isNoContent)
        assertThat(list().map { it["id"].asText() }).containsExactly(second["id"].asText())

        // 파일 형식: DESIGN.md §8.1의 배열
        val file = read(Files.readString(storyDir.resolve("directives.json")))
        assertThat(file.isArray).isTrue()
        assertThat(file[0].fieldNames().asSequence().toList()).containsExactlyInAnyOrder("id", "text", "enabled", "createdAt")
    }

    @Test
    fun `빈 내용은 400, 없는 id는 404`() {
        perform(post(base()).contentType(MediaType.APPLICATION_JSON).content("""{"text":"  "}"""), status().isBadRequest)
        val id = add("반말")["id"].asText()
        patchDirective(id, mapOf("text" to ""), status().isBadRequest)
        patchDirective("nope", mapOf("enabled" to false), status().isNotFound)
        perform(delete("${base()}/nope"), status().isNotFound)
        perform(get("/api/stories/999999/directives"), status().isNotFound)
    }

    @Test
    fun `_legacy 스토리는 읽기만 되고 쓰기는 400`() {
        val legacy = storyRepository.save(Story(scenarioId = scenario.id, title = "옛 스토리", dirName = DataPaths.LEGACY_DIR_NAME))
        perform(get("/api/stories/${legacy.id}/directives"), status().isOk)
        perform(
            post("/api/stories/${legacy.id}/directives").contentType(MediaType.APPLICATION_JSON).content("""{"text":"반말"}"""),
            status().isBadRequest,
        )
    }

    @Test
    fun `깨진 파일은 덮어쓰지 않고 500, 주입은 건너뛴다`() {
        Files.writeString(storyDir.resolve("directives.json"), "{ 깨진 json")

        perform(post(base()).contentType(MediaType.APPLICATION_JSON).content("""{"text":"반말"}"""), status().isInternalServerError)
        assertThat(Files.readString(storyDir.resolve("directives.json"))).isEqualTo("{ 깨진 json")

        val names = preview(input = "안녕")["sections"].map { it["name"].asText() }
        assertThat(names).doesNotContain(DirectiveContributor.NAME).contains("base")
    }

    // ---- 주입 ----

    @Test
    fun `켜진 지시는 30턴 뒤에도 BOTTOM의 지시 블록 맨 앞에 들어간다`() {
        add("말투는 반말")
        add("주인공의 대사를 대신 쓰지 마라")
        addTurns(35)

        val body = preview(input = "다음 행동")

        val bottom = body["sections"].filter { it["slot"].asText() == "BOTTOM" }
        assertThat(bottom.map { it["name"].asText() }).containsExactly(DirectiveContributor.NAME)
        assertThat(bottom.single()["content"].asText()).isEqualTo(
            "다음 지시는 해제될 때까지 항상 지켜라:\n1. 말투는 반말\n2. 주인공의 대사를 대신 쓰지 마라",
        )
        // 시스템 프롬프트가 아니라 마지막 유저 메시지 앞 [지시] 블록에 있다
        assertThat(body["systemPrompt"].asText()).doesNotContain("말투는 반말")
        val last = body["messages"].last()
        assertThat(last["role"].asText()).isEqualTo("USER")
        assertThat(last["content"].asText()).startsWith("[지시]\n다음 지시는 해제될 때까지 항상 지켜라:\n1. 말투는 반말")
        assertThat(last["content"].asText()).endsWith("다음 행동")
        // 원문 범위(최대 30턴)에서 첫 턴이 빠질 만큼 대화가 길어도 지시는 남는다
        assertThat(body["messages"].joinToString("\n") { it["content"].asText() }).doesNotContain("유저 입력 1\n")
        assertThat(body["rawWindow"]["afterTurn"].asInt()).isGreaterThan(0)
    }

    @Test
    fun `끈 지시는 빠지고, 모두 끄면 섹션이 없다`() {
        val a = add("말투는 반말")["id"].asText()
        add("설월은 존댓말", enabled = false)
        val c = add("묘사는 짧게")["id"].asText()

        val content = preview(input = "안녕")["sections"].single { it["name"].asText() == DirectiveContributor.NAME }["content"].asText()
        assertThat(content).isEqualTo("다음 지시는 해제될 때까지 항상 지켜라:\n1. 말투는 반말\n2. 묘사는 짧게")

        patchDirective(a, mapOf("enabled" to false))
        patchDirective(c, mapOf("enabled" to false))
        val body = preview(input = "안녕")
        assertThat(body["sections"].map { it["name"].asText() }).doesNotContain(DirectiveContributor.NAME)
        assertThat(body["messages"].last()["content"].asText()).isEqualTo("안녕")
    }

    @Test
    fun `이번 턴 지시가 있으면 지속 지시 뒤에 온다`() {
        add("말투는 반말")
        addTurns(1)

        val p = assembler.assemble(storyId, turnInstruction = "이어서 써라")

        assertThat(p.sections.filter { it.slot == PromptSlot.BOTTOM }.map { it.name })
            .containsExactly(DirectiveContributor.NAME, "turn_instruction")
        assertThat(p.messages.last().content)
            .isEqualTo("[지시]\n다음 지시는 해제될 때까지 항상 지켜라:\n1. 말투는 반말\n\n이어서 써라")
    }
}
