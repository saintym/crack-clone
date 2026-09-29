package com.crack.scenario.imports

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.service.AiGateway
import com.crack.global.exception.BadRequestException
import com.crack.scenario.repository.ScenarioRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDate

/**
 * URL에서 시나리오 한 벌을 만든다 (DESIGN.md §11).
 *
 * 1. [analyze]: 페이지를 내려받아 추출하고 LLM 1회로 제목·인물 목록·질문을 뽑아 [ImportJobStore]에 캐시한다.
 * 2. [confirm]: 사용자 답변을 받아 문서를 만든다. 진행 상황은 SSE `step`, 끝은 `done`, 실패는 `error`다.
 *
 * 문서를 만드는 일은 [ScenarioBuildPipeline]이 한다(질문으로 만들기 T48과 공용).
 * 이 클래스가 하는 것은 **맥락을 페이지에서 얻는 것**뿐이다.
 */
@Service
class ScenarioImportService(
    private val fetcher: PageFetcher,
    private val extractor: HtmlExtractor,
    private val gateway: AiGateway,
    private val jobs: ImportJobStore,
    private val workspace: ImportWorkspace,
    private val executors: ImportExecutors,
    private val pipeline: ScenarioBuildPipeline,
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
        val batches = pipeline.batchCount(analysis.characters.size)
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
            estimatedSeconds = pipeline.estimatedSeconds(batches),
        )
    }

    /** LLM이 이미지 주소를 빠뜨렸으면 페이지의 이미지 목록에서 이름이 든 주소를 찾아 채운다. */
    private fun fillImages(characters: List<ImportCharacter>, imageUrls: List<String>): List<ImportCharacter> =
        characters.map { character ->
            if (character.imageUrl.isNotBlank()) return@map character
            val guess = imageUrls.firstOrNull { it.contains(character.name) }
            if (guess == null) character else character.copy(imageUrl = guess)
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
     * 페이지를 맥락으로 한 생성. 실제 작업은 [ScenarioBuildPipeline]이 한다.
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
        val answerText = ImportPrompts.formatAnswers(job.analysis.questions, answers)
        val source = ImportDocs.sourceLine(job.page.url, LocalDate.now())
        pipeline.run(
            ScenarioBuildSpec(
                jobId = job.id,
                name = name,
                title = title,
                characterNames = job.analysis.characters.map { it.name },
                prompts = ImportPrompts.forPage(job.page, answerText),
                source = source,
                protagonistName = protagonistName(job, answers),
                provider = provider,
                priorLlmCalls = 1, // 분석 1회를 포함해 센다
                images = { written -> buildImages(job, written, source) },
                onPublished = { jobs.remove(job.id) },
            ),
            sink,
        )
    }

    /** 주인공 이름 질문의 답. 이름 줄이 비었을 때 채워 넣는다(프롤로그의 `{{user}}` 치환에 쓰인다). */
    private fun protagonistName(job: ImportJob, answers: Map<String, String>): String? =
        job.analysis.questions.firstOrNull { "이름" in it.text }
            ?.let { answers[it.id]?.trim() }
            ?.takeIf { it.isNotEmpty() && it.length <= 40 }

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

    companion object {
        /** 인물 25명이면 3~6분이 걸린다. 넉넉하게 잡는다. */
        const val SSE_TIMEOUT_MS = 30 * 60 * 1000L
        const val MAX_SCENE_IMAGES = 60
    }
}
