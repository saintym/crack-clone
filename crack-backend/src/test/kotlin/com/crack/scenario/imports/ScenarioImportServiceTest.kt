package com.crack.scenario.imports

import com.crack.ai.dto.AiRequest
import com.crack.ai.service.AiGateway
import com.crack.global.config.DataPathConfig
import com.crack.global.config.DataPaths
import com.crack.global.exception.BadRequestException
import com.crack.image.ImageCatalogParser
import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.ProtagonistDoc
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.prologue.Prologue
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 생성 파이프라인 (DESIGN.md §11.3): 문서 한 벌, 원자성, 이름 충돌.
 *
 * 실제 AI를 부르지 않는다. [AiGateway]를 스텁해 단계별 계약 형식으로 답하게 한다.
 */
class ScenarioImportServiceTest {

    private lateinit var root: Path
    private lateinit var gateway: AiGateway
    private lateinit var repository: ScenarioRepository
    private lateinit var jobs: ImportJobStore
    private lateinit var service: ScenarioImportService
    private lateinit var executors: ImportExecutors
    private lateinit var properties: ImportProperties
    private val calls = AtomicInteger()

    /** 이벤트를 모으는 sink. SSE 없이 파이프라인을 끝까지 돌린다. */
    private class RecordingSink : ImportEventSink {
        val steps = mutableListOf<ImportStepEvent>()
        var done: ImportDoneEvent? = null
        var error: String? = null
        override fun step(event: ImportStepEvent) { synchronized(steps) { steps += event } }
        override fun done(event: ImportDoneEvent) { done = event }
        override fun error(message: String) { error = message }
    }

    private val html = """
        <html><head><title>무림 설정집</title></head><body>
        <h1>강호</h1><p>정파와 마교가 대치한다.</p>
        <script>
        const chars = [
          {name:"휘령", alias:"낙화검선", img:"https://jangsue.uk/MG/img/26.png"},
          {name:"혜연", alias:"신권나찰", img:"https://jangsue.uk/MG/img/24.png"},
          {name:"소진", alias:"현월냉검", img:"https://jangsue.uk/MG/img/10.png"},
        ];
        </script>
        <img src="https://jangsue.uk/MG/img/bg.webp">
        </body></html>
    """.trimIndent()

    private val characters = listOf(
        ImportCharacter("휘령", "낙화검선", "화산파", "https://jangsue.uk/MG/img/26.png"),
        ImportCharacter("혜연", "신권나찰", "소림사", "https://jangsue.uk/MG/img/24.png"),
        ImportCharacter("소진", "현월냉검", "무당파", "https://jangsue.uk/MG/img/10.png"),
    )
    private val questions = listOf(
        ImportQuestion("q1", "주인공의 이름은?", "예: 무명"),
        ImportQuestion("q2", "시작 시점은?", ""),
    )

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("crack-import-test")
        properties = ImportProperties(concurrency = 2, charactersPerBatch = 2, allowPrivateHosts = true)
        val dataPaths = DataPaths(DataPathConfig(dataPath = root.toString()))
        gateway = mock()
        repository = mock()
        jobs = ImportJobStore(properties)
        executors = ImportExecutors(properties)
        service = ScenarioImportService(
            fetcher = mock(),
            extractor = HtmlExtractor(properties),
            gateway = gateway,
            jobs = jobs,
            workspace = ImportWorkspace(dataPaths),
            executors = executors,
            properties = properties,
            scenarioRepository = repository,
            objectMapper = ObjectMapper(),
        )
        calls.set(0)
    }

    @AfterEach
    fun tearDown() {
        executors.shutdown()
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun newJob(): ImportJob {
        val page = HtmlExtractor(properties).extract("https://jangsue.uk/MG/web.html", html)
        return jobs.put(page, ImportAnalysis("무림 설정집", "무림", characters, questions))
    }

    /** 단계별 계약 형식으로 답한다. [missingCharacter]가 있으면 그 인물만 빼고 답한다. */
    private fun stubGateway(missingCharacter: String? = null, failOn: String? = null) {
        whenever(gateway.chat(any(), anyOrNull())).thenAnswer { invocation ->
            val request = invocation.getArgument<AiRequest>(0)
            val message = request.messages.last().content
            calls.incrementAndGet()
            if (failOn != null && message.startsWith(failOn)) throw IllegalStateException("AI 호출 실패(테스트)")
            when {
                message.startsWith("1단계") -> worldOutput
                message.startsWith("2단계") -> {
                    val names = Regex("""대상 \(\d+명\): (.*)""").find(message)!!.groupValues[1]
                        .split(", ").map { it.trim() }
                        .filter { it != missingCharacter }
                    names.joinToString("\n") { characterOutput(it) }
                }
                message.startsWith("3단계") -> protagonistOutput
                else -> "{}"
            }
        }
    }

    private val worldOutput = """
        <world>
        # 세계관
        ## 시대
        무협 시대.
        </world>
        <scenario>
        # 시나리오
        ## 초기 상황
        객잔에서 시작한다.
        </scenario>
        <keywords>
        ## 천마신교
        키워드: 천마신교, 마교
        서쪽의 마도 문파.
        </keywords>
    """.trimIndent()

    private fun characterOutput(name: String) = """
        <character name="$name">
        # 캐릭터: $name
        ## 기본 정보
        - **이름**: $name
        - **별칭**: ${name}이
        ## 말투
        존댓말.
        </character>
    """.trimIndent()

    private val protagonistOutput = """
        <protagonist>
        # 주인공 (사용자)
        ## 기본 정보
        - **이름**:
        ## 배경
        무명의 낭인.
        </protagonist>
        <prologue>
        *비가 그친 저녁.* {{user}}의 앞에 낯선 이가 앉았다.
        </prologue>
    """.trimIndent()

    @Test
    fun `문서 한 벌을 만들고 DB에 등록한다`() {
        stubGateway()
        whenever(repository.save(any<Scenario>())).thenAnswer {
            val saved = it.getArgument<Scenario>(0)
            Scenario(id = 7, name = saved.name, title = saved.title)
        }
        val sink = RecordingSink()
        val job = newJob()

        service.generate(job, "무림", "무림 설정집", mapOf("q1" to "무명", "q2" to "봄"), null, sink)

        assertThat(sink.error).isNull()
        val done = sink.done!!
        assertThat(done.scenarioId).isEqualTo(7)
        assertThat(done.characterCount).isEqualTo(3)
        assertThat(done.files).contains(
            "world.md", "scenario.md", "keywords.md", "prologue.md", "images.md",
            "characters/protagonist.md", "characters/휘령.md", "characters/혜연.md", "characters/소진.md",
        )
        // LLM 호출: 세계관 1 + 인물 배치 2 + 주인공 1
        assertThat(calls.get()).isEqualTo(4)
        assertThat(done.llmCalls).isEqualTo(5) // 분석 1회 포함

        val dir = root.resolve("무림")
        assertThat(Files.isDirectory(dir)).isTrue()
        assertThat(Files.exists(root.resolve(ImportWorkspace.TEMP_DIR).resolve(job.id))).isFalse()

        // 우리 파서로 읽힌다
        val hwiryeong = CharacterDoc.read(dir.resolve("characters/휘령.md"))
        assertThat(hwiryeong.parseAliases()).contains("휘령이")
        assertThat(hwiryeong.memoryLength()).isZero()
        val protagonist = ProtagonistDoc.read(dir.resolve("characters/protagonist.md"))
        assertThat(protagonist.displayName()).isEqualTo("무명")
        assertThat(protagonist.changesLength()).isZero()

        // 인물 이미지가 {이름}_기본으로 등록된다 (T27 규칙)
        val images = ImageCatalogParser.parse(Files.readString(dir.resolve("images.md")))
        assertThat(images.map { it.tag }).containsExactly("휘령_기본", "혜연_기본", "소진_기본")
        assertThat(images[0].url).isEqualTo("https://jangsue.uk/MG/img/26.png")

        // 프롤로그는 {{user}}가 주인공 이름으로 바뀐다
        assertThat(Prologue.render(Files.readString(dir.resolve("prologue.md")), "무명")).contains("무명의 앞에")

        // 단계 이벤트가 순서대로 온다
        assertThat(sink.steps.map { it.step }.distinct()).containsExactly("world", "characters", "protagonist", "images")
        assertThat(jobs.size()).isZero() // 끝나면 캐시를 비운다
    }

    @Test
    fun `중간에 실패하면 폴더도 DB도 흔적이 없다`() {
        stubGateway(failOn = "3단계")
        val sink = RecordingSink()
        val job = newJob()

        service.generate(job, "무림", "무림 설정집", emptyMap(), null, sink)

        assertThat(sink.done).isNull()
        assertThat(sink.error).contains("주인공 단계에서 실패")
        assertThat(Files.exists(root.resolve("무림"))).isFalse()
        verify(repository, never()).save(any<Scenario>())
        // 임시 폴더가 남지 않는다
        val tempRoot = root.resolve(ImportWorkspace.TEMP_DIR)
        val leftovers = if (Files.isDirectory(tempRoot)) Files.list(tempRoot).use { it.toList() } else emptyList()
        assertThat(leftovers).isEmpty()
    }

    @Test
    fun `첫 단계가 실패하면 시나리오 폴더를 만들지 않는다`() {
        stubGateway(failOn = "1단계")
        val sink = RecordingSink()

        service.generate(newJob(), "무림", "무림 설정집", emptyMap(), null, sink)

        assertThat(sink.error).contains("세계관 단계에서 실패")
        assertThat(Files.exists(root.resolve("무림"))).isFalse()
    }

    @Test
    fun `DB 등록이 실패하면 옮긴 폴더도 되돌린다`() {
        stubGateway()
        whenever(repository.save(any<Scenario>())).thenThrow(IllegalStateException("DB 실패(테스트)"))
        val sink = RecordingSink()

        service.generate(newJob(), "무림", "무림 설정집", emptyMap(), null, sink)

        assertThat(sink.error).contains("등록 단계에서 실패")
        assertThat(Files.exists(root.resolve("무림"))).isFalse()
    }

    @Test
    fun `배치에서 빠진 인물은 한 번 더 부르고 그래도 없으면 건너뛴다`() {
        stubGateway(missingCharacter = "혜연")
        whenever(repository.save(any<Scenario>())).thenAnswer {
            val saved = it.getArgument<Scenario>(0)
            Scenario(id = 1, name = saved.name, title = saved.title)
        }
        val sink = RecordingSink()

        service.generate(newJob(), "무림", "무림 설정집", emptyMap(), null, sink)

        assertThat(sink.error).isNull()
        assertThat(sink.done!!.characterCount).isEqualTo(2)
        assertThat(Files.exists(root.resolve("무림/characters/혜연.md"))).isFalse()
        assertThat(Files.exists(root.resolve("무림/characters/휘령.md"))).isTrue()
        // 혜연이 든 배치만 재시도한다 (세계관 1 + 배치 2 + 재시도 1 + 주인공 1)
        assertThat(calls.get()).isEqualTo(5)
    }

    @Test
    fun `같은 이름의 시나리오가 있으면 400이다`() {
        val job = newJob()
        whenever(repository.existsByName("무림")).thenReturn(true)

        assertThatThrownBy { service.confirm(job.id, ImportConfirmRequest(name = "무림")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("이미 존재")
    }

    @Test
    fun `이미 폴더가 있으면 400이다`() {
        val job = newJob()
        Files.createDirectories(root.resolve("무림"))

        assertThatThrownBy { service.confirm(job.id, ImportConfirmRequest(name = "무림")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("이미")
    }

    @Test
    fun `경로를 벗어나는 이름을 거부한다`() {
        val job = newJob()

        assertThatThrownBy { service.confirm(job.id, ImportConfirmRequest(name = "   ")) }
            .isInstanceOf(BadRequestException::class.java)
        // `..`는 safeName이 빈 값으로 만들어 거부된다
        assertThatThrownBy { service.confirm(job.id, ImportConfirmRequest(name = "..")) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `없는 jobId는 404다`() {
        assertThatThrownBy { service.confirm("없는-job", ImportConfirmRequest(name = "무림")) }
            .isInstanceOf(com.crack.global.exception.NotFoundException::class.java)
    }
}
