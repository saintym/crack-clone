package com.crack.scenario.imports

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.service.AiGateway
import com.crack.global.exception.BadRequestException
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * URL에서 시나리오 한 벌을 만든다 (DESIGN.md §11).
 *
 * 1. [analyze]: 페이지를 내려받아 추출하고 LLM 1회로 제목·인물 목록·질문을 뽑아 [ImportJobStore]에 캐시한다.
 * 2. [confirm]: 사용자 답변을 받아 문서를 만든다. 진행 상황은 SSE `step`, 끝은 `done`, 실패는 `error`다.
 *
 * 모든 AI 호출은 [AiGateway]를 거친다. 인물 배치는 `crack.import.concurrency`로 동시 실행한다.
 * 생성은 **원자적**이다([ImportWorkspace]).
 */
@Service
class ScenarioImportService(
    private val fetcher: PageFetcher,
    private val extractor: HtmlExtractor,
    private val gateway: AiGateway,
    private val jobs: ImportJobStore,
    private val workspace: ImportWorkspace,
    private val executors: ImportExecutors,
    private val properties: ImportProperties,
    private val scenarioRepository: ScenarioRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // --- 1단계: 분석 ---

    fun analyze(url: String, provider: String? = null): ImportAnalyzeResponse {
        val page = extractor.extract(fetcher.fetch(url))
        log.info(
            "가져오기 분석: url={}, 본문={}자, 데이터블록={}개({}자), 이미지={}개",
            page.url, page.bodyText.length, page.dataBlocks.size, page.truncated.dataChars, page.imageUrls.size,
        )
        val raw = gateway.chat(
            AiRequest(
                systemPrompt = ImportPrompts.system(page),
                messages = listOf(ChatMessage(MessageRole.USER, ImportPrompts.analyze())),
                purpose = AiPurpose.CHAT,
            ),
            provider,
        )
        val parsed = ImportOutputParser.parseAnalysis(objectMapper, raw, page.title)
        val analysis = parsed.copy(
            suggestedName = ImportDocs.safeName(parsed.suggestedName) ?: "imported",
            characters = fillImages(parsed.characters, page.imageUrls),
        )
        val job = jobs.put(page, analysis)
        val batches = batchCount(analysis.characters.size)
        return ImportAnalyzeResponse(
            jobId = job.id,
            url = page.url,
            suggestedName = analysis.suggestedName,
            title = analysis.title,
            characters = analysis.characters,
            characterCount = analysis.characters.size,
            imageCount = page.imageUrls.size,
            questions = analysis.questions,
            truncated = page.truncated,
            estimatedLlmCalls = 1 + 1 + batches + 1,
            estimatedSeconds = estimatedSeconds(batches),
        )
    }

    /** LLM이 이미지 주소를 빠뜨렸으면 페이지의 이미지 목록에서 이름이 든 주소를 찾아 채운다. */
    private fun fillImages(characters: List<ImportCharacter>, imageUrls: List<String>): List<ImportCharacter> =
        characters.map { character ->
            if (character.imageUrl.isNotBlank()) return@map character
            val guess = imageUrls.firstOrNull { it.contains(character.name) }
            if (guess == null) character else character.copy(imageUrl = guess)
        }

    private fun batchCount(characters: Int): Int {
        val size = properties.charactersPerBatch.coerceAtLeast(1)
        return ceil(characters.toDouble() / size).toInt()
    }

    /** 배치가 `concurrency`씩 병렬로 도는 것을 감안한 대략 시간. 한 호출을 30초로 본다. */
    private fun estimatedSeconds(batches: Int): Int {
        val waves = ceil(batches.toDouble() / properties.concurrency.coerceAtLeast(1)).toInt()
        return (1 + waves + 1) * 30
    }

    // --- 2단계: 생성 ---

    fun confirm(jobId: String, request: ImportConfirmRequest, provider: String? = null): SseEmitter {
        val job = jobs.require(jobId)
        val name = ImportDocs.safeName(request.name)
            ?: throw BadRequestException("시나리오 이름이 올바르지 않습니다: '${request.name}'")
        if (scenarioRepository.existsByName(name)) {
            throw BadRequestException("시나리오 '$name'이(가) 이미 존재합니다. 다른 이름을 쓰세요(덮어쓰지 않습니다)")
        }
        workspace.requireAvailable(name)

        val title = request.title?.trim()?.ifEmpty { null } ?: job.analysis.title.ifBlank { name }
        val emitter = SseEmitter(SSE_TIMEOUT_MS)
        val sender = SseEventSink(emitter)
        executors.runner.submit {
            try {
                generate(job, name, title, request.answers, provider, sender)
            } finally {
                sender.finish()
            }
        }
        return emitter
    }

    /**
     * 생성 본체. 실패해도 예외를 던지지 않고 [sink]에 `error`를 보낸다.
     * 테스트가 SSE 없이 바로 부를 수 있게 [ImportEventSink]만 받는다.
     */
    internal fun generate(
        job: ImportJob,
        name: String,
        title: String,
        answers: Map<String, String>,
        provider: String?,
        sink: ImportEventSink,
    ) {
        val startedAt = Instant.now()
        val calls = AtomicInteger(1) // 분석 1회를 포함해 센다
        var tempDir: Path? = null // 정리용. 옮긴 뒤에는 null
        var published: Path? = null
        var step = "시작"
        try {
            val system = ImportPrompts.system(job.page)
            val answerText = ImportPrompts.formatAnswers(job.analysis.questions, answers)
            val source = ImportDocs.sourceLine(job.page.url, LocalDate.now())
            val dir = workspace.createTempDir(job.id)
            tempDir = dir

            // 1. 세계관 · 시나리오 · 키워드북
            step = "세계관"
            sink.step(ImportStepEvent("world", "세계관·시나리오·키워드북", 1, TOTAL_STEPS, 5))
            val worldOut = ask(system, ImportPrompts.worldStep(answerText), provider, calls)
            workspace.write(dir, "world.md", ImportDocs.plain(ImportOutputParser.requireTag(worldOut, "world", step), source, "세계관"))
            workspace.write(dir, "scenario.md", ImportDocs.plain(ImportOutputParser.requireTag(worldOut, "scenario", step), source, "시나리오"))
            ImportOutputParser.tag(worldOut, "keywords")?.let {
                workspace.write(dir, "keywords.md", ImportDocs.keywords(it, source))
            }

            // 2. 인물 전원
            step = "인물"
            val fileNames = assignFileNames(job.analysis.characters)
            val written = writeCharacters(dir, job, fileNames, system, answerText, source, provider, calls, sink)

            // 3. 주인공 · 첫 메시지
            step = "주인공"
            sink.step(ImportStepEvent("protagonist", "주인공·첫 메시지", 3, TOTAL_STEPS, 80))
            val protagonistOut = ask(
                system,
                ImportPrompts.protagonistStep(answerText, written.keys.toList()),
                provider,
                calls,
            )
            val protagonistBody = ImportOutputParser.requireTag(protagonistOut, "protagonist", step)
            workspace.write(
                dir,
                "characters/protagonist.md",
                ImportDocs.protagonist(protagonistName(job, answers), protagonistBody, source),
            )
            ImportOutputParser.tag(protagonistOut, "prologue")?.let {
                // 첫 줄 인물 태그를 인물 파일명으로 맞춘다(T29). written: 화면 이름 → 파일명
                workspace.write(dir, "prologue.md", ImportDocs.prologue(it, source, written))
            }

            // 4. 이미지 카탈로그 (LLM 호출 없음)
            step = "이미지"
            sink.step(ImportStepEvent("images", "이미지 카탈로그", 4, TOTAL_STEPS, 92))
            workspace.write(dir, "images.md", buildImages(job, written, source))

            // 5. 폴더를 옮기고 DB에 등록한다 (원자적)
            step = "등록"
            val files = workspace.listFiles(dir)
            published = workspace.publish(dir, name)
            tempDir = null
            val scenario = scenarioRepository.save(Scenario(name = name, title = title))

            jobs.remove(job.id)
            val elapsed = Duration.between(startedAt, Instant.now()).seconds
            log.info(
                "가져오기 완료: name={}, 인물={}명, 파일={}개, LLM={}회, {}초",
                name, written.size, files.size, calls.get(), elapsed,
            )
            sink.done(
                ImportDoneEvent(
                    scenarioId = scenario.id,
                    name = name,
                    title = title,
                    files = files,
                    characterCount = written.size,
                    llmCalls = calls.get(),
                    elapsedSeconds = elapsed,
                ),
            )
        } catch (e: Exception) {
            workspace.deleteQuietly(tempDir)
            workspace.deleteQuietly(published) // DB 등록이 실패했으면 옮긴 폴더도 되돌린다
            log.error("가져오기 실패: name={}, 단계={}", name, step, e)
            sink.error("$step 단계에서 실패했습니다: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 주인공 이름 질문의 답. `- **이름**:`이 비어 있을 때 채워 넣는다(프롤로그의 `{{user}}` 치환에 쓰인다). */
    private fun protagonistName(job: ImportJob, answers: Map<String, String>): String? =
        job.analysis.questions.firstOrNull { "이름" in it.text }
            ?.let { answers[it.id]?.trim() }
            ?.takeIf { it.isNotEmpty() && it.length <= 40 }

    private fun ask(system: String, userMessage: String, provider: String?, calls: AtomicInteger): String {
        calls.incrementAndGet()
        return gateway.chat(
            AiRequest(
                systemPrompt = system,
                messages = listOf(ChatMessage(MessageRole.USER, userMessage)),
                purpose = AiPurpose.CHAT,
            ),
            provider,
        )
    }

    /** 표시 이름 → 파일명(= 이미지 태그 접두사). 쓸 수 없는 이름은 빼고, 중복은 숫자를 붙인다. */
    private fun assignFileNames(characters: List<ImportCharacter>): Map<String, String> {
        val used = mutableSetOf("protagonist")
        val result = LinkedHashMap<String, String>()
        for (character in characters) {
            if (result.containsKey(character.name)) continue
            val safe = ImportDocs.safeName(character.name)
            if (safe == null) {
                log.warn("인물 이름을 파일명으로 쓸 수 없어 건너뜀: '{}'", character.name)
                continue
            }
            result[character.name] = ImportDocs.uniqueName(used, safe)
        }
        return result
    }

    /**
     * 인물 문서를 배치로 만든다. 배치 하나가 LLM 1회다.
     * 빠진 인물이 있으면 그 배치를 **한 번** 더 부른다(형식이 흔들려 한두 명이 빠지는 일이 잦다).
     * 그래도 없으면 그 인물만 건너뛴다(전체를 실패시키지 않는다).
     *
     * @return 실제로 문서를 쓴 인물의 표시 이름 → 파일명
     */
    private fun writeCharacters(
        tempDir: Path,
        job: ImportJob,
        fileNames: Map<String, String>,
        system: String,
        answerText: String,
        source: String,
        provider: String?,
        calls: AtomicInteger,
        sink: ImportEventSink,
    ): Map<String, String> {
        val names = fileNames.keys.toList()
        if (names.isEmpty()) {
            sink.step(ImportStepEvent("characters", "인물 문서", 2, TOTAL_STEPS, 25, "인물을 찾지 못했습니다"))
            return emptyMap()
        }
        val batches = names.chunked(properties.charactersPerBatch.coerceAtLeast(1))
        sink.step(ImportStepEvent("characters", "인물 문서", 2, TOTAL_STEPS, 25, "0/${names.size}명"))

        val done = AtomicInteger(0)
        val written = java.util.concurrent.ConcurrentHashMap<String, String>()
        val futures = mutableListOf<Future<*>>()
        for (batch in batches) {
            futures += executors.workers.submit {
                val bodies = askCharacters(system, batch, answerText, provider, calls)
                for (displayName in batch) {
                    val body = bodies[displayName] ?: continue
                    val fileName = fileNames.getValue(displayName)
                    workspace.write(
                        tempDir,
                        "characters/$fileName.md",
                        ImportDocs.character(fileName, displayName, body, source),
                    )
                    written[displayName] = fileName
                }
                val finished = done.addAndGet(batch.size)
                sink.step(
                    ImportStepEvent(
                        "characters", "인물 문서", 2, TOTAL_STEPS,
                        25 + (50 * finished / names.size),
                        "$finished/${names.size}명",
                    ),
                )
            }
        }
        val errors = mutableListOf<Throwable>()
        futures.forEach { future ->
            try {
                future.get()
            } catch (e: Exception) {
                errors += e.cause ?: e
            }
        }
        // 모든 배치가 실패했으면 가져오기를 실패시킨다. 일부만 실패했으면 나머지로 계속한다.
        if (written.isEmpty() && errors.isNotEmpty()) throw errors.first().let { it as? Exception ?: RuntimeException(it) }
        if (errors.isNotEmpty()) log.warn("인물 배치 {}개 실패(나머지로 계속한다): {}", errors.size, errors.first().message)

        val missing = names.filterNot { written.containsKey(it) }
        if (missing.isNotEmpty()) log.warn("문서를 만들지 못한 인물 {}명: {}", missing.size, missing)
        // 요청 순서를 유지해서 돌려준다
        return names.mapNotNull { name -> written[name]?.let { name to it } }.toMap(LinkedHashMap())
    }

    /** 배치 하나. 빠진 인물이 있으면 한 번 더 부른다. */
    private fun askCharacters(
        system: String,
        batch: List<String>,
        answerText: String,
        provider: String?,
        calls: AtomicInteger,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var remaining = batch
        repeat(2) {
            if (remaining.isEmpty()) return result
            val output = ask(system, ImportPrompts.charactersStep(remaining, answerText), provider, calls)
            val parsed = ImportOutputParser.characters(output)
            for ((name, body) in parsed) {
                val matched = remaining.firstOrNull { it == name }
                    ?: remaining.firstOrNull { it.replace(" ", "") == name.replace(" ", "") }
                    ?: continue
                result[matched] = body
            }
            remaining = remaining.filterNot { result.containsKey(it) }
        }
        return result
    }

    /** 인물 이미지는 `{파일명}_기본`으로, 나머지는 주석 안에 장면 태그 후보로. */
    private fun buildImages(job: ImportJob, written: Map<String, String>, source: String): String {
        val characterImages = job.analysis.characters.mapNotNull { character ->
            val fileName = written[character.name] ?: return@mapNotNull null
            val url = character.imageUrl.trim()
            if (url.isEmpty()) null else fileName to url
        }
        val taken = characterImages.map { it.second }.toSet()
        val sceneUrls = job.page.imageUrls.filterNot { it in taken }.take(MAX_SCENE_IMAGES)
        return ImportDocs.images(characterImages, sceneUrls, source)
    }

    /** SSE 전송. 클라이언트가 끊겨도 생성은 끝까지 한다(중간에 멈추면 흔적이 남는다). */
    internal class SseEventSink(private val emitter: SseEmitter) : ImportEventSink {
        private val log = LoggerFactory.getLogger(SseEventSink::class.java)
        private val clientGone = AtomicBoolean(false)

        override fun step(event: ImportStepEvent) = send(ImportSseEvents.STEP, event, MediaType.APPLICATION_JSON)
        override fun done(event: ImportDoneEvent) = send(ImportSseEvents.DONE, event, MediaType.APPLICATION_JSON)
        override fun error(message: String) = send(ImportSseEvents.ERROR, message, null)

        fun finish() {
            try {
                emitter.complete()
            } catch (e: Exception) {
                log.debug("SSE complete 실패: {}", e.message)
            }
        }

        private fun send(name: String, data: Any, mediaType: MediaType?) {
            if (clientGone.get()) return
            try {
                emitter.send(SseEmitter.event().name(name).data(data, mediaType))
            } catch (e: Exception) {
                clientGone.set(true)
                log.debug("SSE 전송 실패(연결 끊김 추정): {}", e.message)
            }
        }
    }

    companion object {
        /** 인물 25명이면 3~6분이 걸린다. 넉넉하게 잡는다. */
        const val SSE_TIMEOUT_MS = 30 * 60 * 1000L
        const val TOTAL_STEPS = 4
        const val MAX_SCENE_IMAGES = 60
    }
}
