package com.crack.scenario.imports.guided

import com.crack.ai.dto.AiRequest
import com.crack.ai.service.AiGateway
import com.crack.global.config.DataPathConfig
import com.crack.global.config.DataPaths
import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.image.ImageCatalogParser
import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.MarkdownSections
import com.crack.memory.docs.ProtagonistDoc
import com.crack.prompt.contributor.PrivateSections
import com.crack.prompt.keyword.KeywordBookParser
import com.crack.scenario.entity.Scenario
import com.crack.scenario.imports.ImportDoneEvent
import com.crack.scenario.imports.ImportEventSink
import com.crack.scenario.imports.ImportExecutors
import com.crack.scenario.imports.ImportProperties
import com.crack.scenario.imports.ImportStepEvent
import com.crack.scenario.imports.ImportWorkspace
import com.crack.scenario.imports.ScenarioBuildPipeline
import com.crack.scenario.repository.ScenarioRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 질문으로 시나리오 만들기 (DESIGN.md §11.6, T48).
 *
 * 실제 AI를 부르지 않는다. [AiGateway]를 스텁해 라운드 JSON과 단계별 태그로 답하게 한다.
 */
class ScenarioCreateServiceTest {

    private lateinit var root: Path
    private lateinit var gateway: AiGateway
    private lateinit var repository: ScenarioRepository
    private lateinit var jobs: CreateJobStore
    private lateinit var executors: ImportExecutors
    private lateinit var service: ScenarioCreateService
    private val prompts = CopyOnWriteArrayList<String>()

    /** 이벤트를 모으는 sink. SSE 없이 파이프라인을 끝까지 돌린다. */
    private class RecordingSink : ImportEventSink {
        val steps = mutableListOf<ImportStepEvent>()
        var done: ImportDoneEvent? = null
        var error: String? = null
        override fun step(event: ImportStepEvent) { synchronized(steps) { steps += event } }
        override fun done(event: ImportDoneEvent) { done = event }
        override fun error(message: String) { error = message }
    }

    private fun setUpService(maxRounds: Int = 3) {
        val importProperties = ImportProperties(concurrency = 2, charactersPerBatch = 2)
        val createProperties = CreateProperties(maxRounds = maxRounds, maxQuestionsPerRound = 5)
        val dataPaths = DataPaths(DataPathConfig(dataPath = root.toString()))
        val workspace = ImportWorkspace(dataPaths)
        jobs = CreateJobStore(createProperties)
        executors = ImportExecutors(importProperties)
        service = ScenarioCreateService(
            gateway = gateway,
            jobs = jobs,
            workspace = workspace,
            executors = executors,
            pipeline = ScenarioBuildPipeline(gateway, workspace, executors, importProperties, repository),
            scenarioRepository = repository,
            objectMapper = ObjectMapper(),
            properties = createProperties,
        )
    }

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("crack-create-test")
        gateway = mock()
        repository = mock()
        prompts.clear()
        setUpService()
    }

    @AfterEach
    fun tearDown() {
        executors.shutdown()
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // --- 스텁 ---

    private fun stubGateway(failOn: String? = null, missingPrivate: Boolean = false) {
        whenever(gateway.chat(any(), anyOrNull())).thenAnswer { invocation ->
            val request = invocation.getArgument<AiRequest>(0)
            val message = request.messages.last().content
            prompts += request.systemPrompt + "\n@@@\n" + message
            if (failOn != null && message.startsWith(failOn)) throw IllegalStateException("AI 호출 실패(테스트)")
            when {
                message.startsWith("1단계") -> worldOutput
                message.startsWith("2단계") -> {
                    Regex("""대상 \(\d+명\): (.*)""").find(message)!!.groupValues[1]
                        .split(", ").map { it.trim() }
                        .joinToString("\n") { characterOutput(it) }
                }
                message.startsWith("3단계") -> protagonistOutput(missingPrivate)
                message.startsWith("마지막 단계다") -> finalRound
                message.startsWith("1라운드") -> round(1)
                else -> round(2)
            }
        }
    }

    private fun round(number: Int) = """
        {
          "title": "몰락한 검객",
          "suggestedName": "검객",
          "done": false,
          "questions": [
            {"id": "q1", "text": "주인공의 이름은?", "placeholder": "예: 우혁규"},
            {"id": "q$number", "text": "${number}라운드 질문", "placeholder": "예시"}
          ],
          "preview": null
        }
    """.trimIndent()

    private val finalRound = """
        {
          "title": "몰락한 검객",
          "suggestedName": "검객",
          "done": true,
          "questions": [],
          "preview": {
            "world": "무림이 기울었다.",
            "protagonist": "몰락한 가문의 검객.",
            "opening": "비 내리는 객잔에서 시작한다.",
            "characters": [
              {"name": "설월", "role": "화산파 일대제자", "note": "주인공과 악연"},
              {"name": "무극", "role": "떠돌이 도사", "note": "주인공의 옛 스승"}
            ]
          }
        }
    """.trimIndent()

    private val worldOutput = """
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

    private fun characterOutput(name: String) = """
        <character name="$name">
        # 캐릭터: $name
        ## 기본 정보
        - **이름**: $name
        ## 배경 스토리
        오래된 인연이 있다.
        ## 말투
        존댓말.
        </character>
    """.trimIndent()

    /** [missingPrivate]면 LLM이 `(비공개)` 섹션을 빠뜨린 경우다. 우리가 채워 넣어야 한다. */
    private fun protagonistOutput(missingPrivate: Boolean) = buildString {
        append("<protagonist>\n# 주인공 (사용자)\n## 기본 정보\n- **이름**:\n## 배경\n몰락한 가문의 검객.\n")
        if (!missingPrivate) append("## 진짜 목적 (비공개)\n가문을 무너뜨린 자를 찾는다.\n")
        append("</protagonist>\n")
        append("<prologue>\n[인물: 설월]\n\n*비가 그친 저녁.* {{user}}의 앞에 낯선 이가 앉았다.\n</prologue>")
    }

    private fun savingRepository() {
        whenever(repository.save(any<Scenario>())).thenAnswer {
            val saved = it.getArgument<Scenario>(0)
            Scenario(id = 9, name = saved.name, title = saved.title)
        }
    }

    /** 질문 3라운드를 다 돌아 미리보기까지 받은 작업. */
    private fun readyJob(): CreateJob {
        stubGateway()
        val started = service.start("무협. 주인공은 몰락한 가문의 검객.")
        service.answer(started.jobId, mapOf("q1" to "우혁규"))
        service.answer(started.jobId, emptyMap())
        val done = service.answer(started.jobId, emptyMap())
        assertThat(done.done).isTrue()
        return jobs.require(started.jobId)
    }

    // --- 질의응답 ---

    @Test
    fun `씨앗 한 줄로 시작하면 1라운드 질문이 온다`() {
        stubGateway()

        val response = service.start("무협. 주인공은 몰락한 가문의 검객. 분위기는 어둡고 건조하게.")

        assertThat(response.round).isEqualTo(1)
        assertThat(response.done).isFalse()
        assertThat(response.jobId).isNotBlank()
        assertThat(response.title).isEqualTo("몰락한 검객")
        assertThat(response.suggestedName).isEqualTo("검객")
        assertThat(response.questions.map { it.id }).containsExactly("q1")
        assertThat(response.questions[0].placeholder).isEqualTo("예: 우혁규")
        // 씨앗이 시스템 프롬프트의 맥락에 들어간다
        assertThat(prompts.single()).contains("무협. 주인공은 몰락한 가문의 검객.")
    }

    @Test
    fun `빈 씨앗은 400이다`() {
        assertThatThrownBy { service.start("   ") }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `빈 답을 줘도 막히지 않고 다음 라운드로 간다`() {
        stubGateway()
        val started = service.start("무협")

        val next = service.answer(started.jobId, mapOf("q1" to ""))

        assertThat(next.round).isEqualTo(2)
        assertThat(next.done).isFalse()
        assertThat(next.questions).isNotEmpty()
        // 빈 답은 "네가 정한다"로 바뀌어 프롬프트에 들어간다
        assertThat(prompts.last()).contains("(답하지 않았다. 네가 정한다)")
    }

    @Test
    fun `라운드 상한에 닿으면 done이 되고 미리보기가 온다`() {
        setUpService(maxRounds = 2)
        stubGateway()
        val started = service.start("무협")
        service.answer(started.jobId, mapOf("q1" to "우혁규"))

        val done = service.answer(started.jobId, emptyMap())

        assertThat(done.done).isTrue()
        assertThat(done.round).isEqualTo(2)
        assertThat(done.questions).isEmpty()
        assertThat(done.preview!!.characters.map { it.name }).containsExactly("설월", "무극")
        assertThat(done.preview!!.opening).contains("객잔")
        assertThat(done.estimatedLlmCalls).isGreaterThan(0)
        assertThat(done.estimatedSeconds).isGreaterThan(0)
        // 상한에 닿은 호출은 "더 묻지 말라"고 지시한다
        assertThat(prompts.last()).contains("마지막 단계다")
    }

    @Test
    fun `준비가 끝난 뒤에는 답을 더 받지 않는다`() {
        setUpService(maxRounds = 1)
        stubGateway()
        val started = service.start("무협")
        service.answer(started.jobId, emptyMap())

        assertThatThrownBy { service.answer(started.jobId, emptyMap()) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `없는 jobId는 404다`() {
        assertThatThrownBy { service.answer("없는-job", emptyMap()) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service.confirm("없는-job", CreateConfirmRequest(name = "검객")) }
            .isInstanceOf(NotFoundException::class.java)
    }

    // --- 생성 ---

    @Test
    fun `문서 한 벌을 만들고 T35 섹션이 들어간다`() {
        savingRepository()
        val job = readyJob()
        val sink = RecordingSink()

        service.generate(job, "검객", "몰락한 검객", job.preview!!.characters, null, sink)

        assertThat(sink.error).isNull()
        val done = sink.done!!
        assertThat(done.scenarioId).isEqualTo(9)
        assertThat(done.characterCount).isEqualTo(2)
        assertThat(done.files).contains(
            "world.md", "scenario.md", "keywords.md", "prologue.md", "images.md",
            "characters/protagonist.md", "characters/설월.md", "characters/무극.md",
        )
        assertThat(sink.steps.map { it.step }.distinct()).containsExactly("world", "characters", "protagonist", "images")

        val dir = root.resolve("검객")
        // 인물 문서: CharacterDoc으로 읽히고 `## 기억`은 비어 있으며 `## 알고 있는 것`이 있다 (T35)
        val seolwol = CharacterDoc.read(dir.resolve("characters/설월.md"))
        assertThat(seolwol.memoryLength()).isZero()
        assertThat(MarkdownSections.find(seolwol.text, "알고 있는 것")).isNotNull()

        // 주인공 문서: ProtagonistDoc으로 읽히고 `(비공개)` 섹션이 있다 (T35)
        val protagonist = ProtagonistDoc.read(dir.resolve("characters/protagonist.md"))
        assertThat(protagonist.changesLength()).isZero()
        assertThat(protagonist.displayName()).isEqualTo("우혁규")
        assertThat(PrivateSections.split(protagonist.text).hasPrivate).isTrue()

        // 키워드북과 이미지 카탈로그가 파서와 호환된다
        assertThat(KeywordBookParser.parse(Files.readString(dir.resolve("keywords.md"))).map { it.id })
            .containsExactly("화산파")
        val imagesText = Files.readString(dir.resolve("images.md"))
        assertThat(ImageCatalogParser.parse(imagesText)).isEmpty() // 등록할 주소가 없다
        assertThat(imagesText).contains("등록할 인물 태그: 설월_기본, 무극_기본")

        // 출처 줄은 URL이 아니라 "질문으로 생성"이다
        assertThat(Files.readString(dir.resolve("world.md"))).contains("출처: 질문으로 생성")
        assertThat(jobs.size()).isZero() // 끝나면 캐시를 비운다
    }

    @Test
    fun `LLM이 비공개 섹션을 빠뜨려도 채워 넣는다`() {
        savingRepository()
        val job = readyJob()
        stubGateway(missingPrivate = true)
        val sink = RecordingSink()

        service.generate(job, "검객", "몰락한 검객", job.preview!!.characters, null, sink)

        assertThat(sink.error).isNull()
        val protagonist = ProtagonistDoc.read(root.resolve("검객/characters/protagonist.md"))
        assertThat(PrivateSections.split(protagonist.text).hasPrivate).isTrue()
        assertThat(protagonist.text).contains("## 비밀 (비공개)")
    }

    @Test
    fun `미리보기에서 인물을 지우면 그 인물이 만들어지지 않는다`() {
        savingRepository()
        val job = readyJob()
        val sink = RecordingSink()

        service.generate(job, "검객", "몰락한 검객", listOf(CreateCharacter("설월", "화산파 일대제자", "악연")), null, sink)

        assertThat(sink.error).isNull()
        assertThat(sink.done!!.characterCount).isEqualTo(1)
        assertThat(Files.exists(root.resolve("검객/characters/설월.md"))).isTrue()
        assertThat(Files.exists(root.resolve("검객/characters/무극.md"))).isFalse()
    }

    @Test
    fun `중간에 실패하면 폴더도 DB도 흔적이 없다`() {
        val job = readyJob()
        stubGateway(failOn = "3단계")
        val sink = RecordingSink()

        service.generate(job, "검객", "몰락한 검객", job.preview!!.characters, null, sink)

        assertThat(sink.done).isNull()
        assertThat(sink.error).contains("주인공 단계에서 실패")
        assertThat(Files.exists(root.resolve("검객"))).isFalse()
        verify(repository, never()).save(any<Scenario>())
        val tempRoot = root.resolve(ImportWorkspace.TEMP_DIR)
        val leftovers = if (Files.isDirectory(tempRoot)) Files.list(tempRoot).use { it.toList() } else emptyList()
        assertThat(leftovers).isEmpty()
    }

    @Test
    fun `질문이 끝나지 않았으면 생성할 수 없다`() {
        stubGateway()
        val started = service.start("무협")

        assertThatThrownBy { service.confirm(started.jobId, CreateConfirmRequest(name = "검객")) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `이미 있는 이름은 400이다`() {
        val job = readyJob()
        whenever(repository.existsByName("검객")).thenReturn(true)

        assertThatThrownBy { service.confirm(job.id, CreateConfirmRequest(name = "검객")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("이미 존재")
    }

    @Test
    fun `경로를 벗어나는 이름을 거부한다`() {
        val job = readyJob()

        assertThatThrownBy { service.confirm(job.id, CreateConfirmRequest(name = "   ")) }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { service.confirm(job.id, CreateConfirmRequest(name = "..")) }
            .isInstanceOf(BadRequestException::class.java)
    }
}
