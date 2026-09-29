package com.crack.scenario.imports.guided

import com.crack.ai.dto.AiPurpose
import com.crack.ai.provider.FakeResponses
import com.crack.chat.api.parseSse
import com.crack.global.config.DataPaths
import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.MarkdownSections
import com.crack.memory.docs.ProtagonistDoc
import com.crack.prompt.contributor.PrivateSections
import com.crack.scenario.repository.ScenarioRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.util.UUID

/**
 * 질문으로 시나리오 만들기 전 흐름 (DESIGN.md §11.6).
 *
 * 씨앗 → 질문 → 답 → 미리보기 → SSE 생성까지 HTTP로 끝까지 돌린다.
 * AI는 [FakeResponses]다. **실제 AI를 부르지 않는다.**
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml의 비밀번호로 AuthFilter가 끼지 않게
class ScenarioCreateApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var fakeResponses: FakeResponses
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var dataPaths: DataPaths
    @Autowired lateinit var properties: CreateProperties

    private val name = "create-" + UUID.randomUUID().toString().take(8)

    @BeforeEach
    fun setUp() {
        fakeResponses.reset()
        fakeResponses.register(AiPurpose.CHAT) { request ->
            val message = request.messages.last().content
            when {
                message.startsWith("1단계") -> WORLD
                message.startsWith("2단계") ->
                    Regex("""대상 \(\d+명\): (.*)""").find(message)!!.groupValues[1]
                        .split(", ").joinToString("\n") { character(it.trim()) }
                message.startsWith("3단계") -> PROTAGONIST
                message.startsWith("마지막 단계다") -> FINAL_ROUND
                else -> QUESTIONS
            }
        }
    }

    @AfterEach
    fun tearDown() {
        fakeResponses.reset()
        scenarioRepository.findByName(name)?.let { scenarioRepository.delete(it) }
        val dir = dataPaths.scenarioDir(name)
        if (Files.exists(dir)) {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun json(body: Any) = objectMapper.writeValueAsString(body)

    private fun postJson(path: String, body: Any): JsonNode =
        objectMapper.readTree(
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8),
        )

    @Test
    fun `설정 기본값이 DESIGN과 같다`() {
        assertThat(properties.maxRounds).isEqualTo(3)
        assertThat(properties.maxQuestionsPerRound).isEqualTo(5)
        assertThat(properties.jobTtlMinutes).isEqualTo(30)
    }

    @Test
    fun `빈 씨앗은 400, 없는 jobId는 404다`() {
        mockMvc.perform(
            post("/api/scenarios/create/start").contentType(MediaType.APPLICATION_JSON).content("""{"seed":"  "}"""),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/scenarios/create/없는-job/answer").contentType(MediaType.APPLICATION_JSON).content("""{"answers":{}}"""),
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/api/scenarios/create/없는-job/confirm").contentType(MediaType.APPLICATION_JSON).content("""{"name":"x"}"""),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `씨앗 한 줄로 시작해 질문 답 미리보기 생성까지 끝난다`() {
        // 1. 씨앗
        val start = postJson(
            "/api/scenarios/create/start",
            mapOf("seed" to "무협. 주인공은 몰락한 가문의 검객. 분위기는 어둡고 건조하게."),
        )
        val jobId = start.get("jobId").asText()
        assertThat(start.get("round").asInt()).isEqualTo(1)
        assertThat(start.get("done").asBoolean()).isFalse()
        assertThat(start.get("questions")).isNotEmpty

        // 2. 라운드 상한까지 답한다. 빈 답을 줘도 막히지 않는다
        var round = postJson("/api/scenarios/create/$jobId/answer", mapOf("answers" to mapOf("q1" to "우혁규")))
        assertThat(round.get("done").asBoolean()).isFalse()
        round = postJson("/api/scenarios/create/$jobId/answer", mapOf("answers" to emptyMap<String, String>()))
        assertThat(round.get("done").asBoolean()).isFalse()
        round = postJson("/api/scenarios/create/$jobId/answer", mapOf("answers" to emptyMap<String, String>()))

        // 3. 미리보기
        assertThat(round.get("done").asBoolean()).isTrue()
        assertThat(round.get("round").asInt()).isEqualTo(properties.maxRounds)
        val preview = round.get("preview")
        assertThat(preview.get("characters").map { it.get("name").asText() }).containsExactly("설월", "무극")
        assertThat(round.get("estimatedSeconds").asInt()).isGreaterThan(0)

        // 4. 생성 (SSE). 인물 하나를 지우고 보낸다
        val result = mockMvc.perform(
            post("/api/scenarios/create/$jobId/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(
                    json(
                        mapOf(
                            "name" to name,
                            "title" to "몰락한 검객",
                            "characters" to listOf(mapOf("name" to "설월", "role" to "화산파 일대제자", "note" to "악연")),
                        ),
                    ),
                ),
        ).andExpect(request().asyncStarted()).andReturn()
        result.getAsyncResult(30_000)
        val body = result.response.getContentAsString(Charsets.UTF_8)
        mockMvc.perform(asyncDispatch(result)) // 안 하면 요청이 끝나지 않아 DB 커넥션이 샌다

        val events = parseSse(body)
        assertThat(events.map { it.name }).contains("step", "done").doesNotContain("error")
        val done = objectMapper.readTree(events.last { it.name == "done" }.data)
        assertThat(done.get("name").asText()).isEqualTo(name)
        assertThat(done.get("characterCount").asInt()).isEqualTo(1)

        // 5. 문서와 DB
        assertThat(scenarioRepository.findByName(name)).isNotNull
        val dir = dataPaths.scenarioDir(name)
        assertThat(Files.exists(dir.resolve("characters/무극.md"))).isFalse() // 지운 인물은 만들지 않는다
        val seolwol = CharacterDoc.read(dir.resolve("characters/설월.md"))
        assertThat(seolwol.memoryLength()).isZero()
        assertThat(MarkdownSections.find(seolwol.text, "알고 있는 것")).isNotNull()
        val protagonist = ProtagonistDoc.read(dir.resolve("characters/protagonist.md"))
        assertThat(protagonist.changesLength()).isZero()
        assertThat(PrivateSections.split(protagonist.text).hasPrivate).isTrue()
        assertThat(Files.readString(dir.resolve("images.md"))).contains("설월_기본")
    }

    companion object {
        private val QUESTIONS = """
            {"title":"몰락한 검객","suggestedName":"검객","done":false,
             "questions":[{"id":"q1","text":"주인공의 이름은?","placeholder":"예: 우혁규"}]}
        """.trimIndent()

        private val FINAL_ROUND = """
            {"title":"몰락한 검객","suggestedName":"검객","done":true,"questions":[],
             "preview":{"world":"무림이 기울었다.","protagonist":"몰락한 가문의 검객.","opening":"비 내리는 객잔.",
               "characters":[{"name":"설월","role":"화산파 일대제자","note":"주인공과 악연"},
                             {"name":"무극","role":"떠돌이 도사","note":"옛 스승"}]}}
        """.trimIndent()

        private val WORLD = """
            <world>
            # 세계관
            ## 시대
            기울어 가는 무림.
            </world>
            <scenario>
            # 시나리오
            ## 초기 상황
            객잔에서 시작한다.
            </scenario>
            <keywords>
            ## 화산파
            키워드: 화산파, 화산
            구파일방의 하나.
            </keywords>
        """.trimIndent()

        private fun character(name: String) = """
            <character name="$name">
            # 캐릭터: $name
            ## 기본 정보
            - **이름**: $name
            ## 말투
            존댓말.
            </character>
        """.trimIndent()

        private val PROTAGONIST = """
            <protagonist>
            # 주인공 (사용자)
            ## 기본 정보
            - **이름**:
            ## 배경
            몰락한 가문의 검객.
            </protagonist>
            <prologue>
            [인물: 설월]

            *비가 그친 저녁.* {{user}}의 앞에 낯선 이가 앉았다.
            </prologue>
        """.trimIndent()
    }
}
