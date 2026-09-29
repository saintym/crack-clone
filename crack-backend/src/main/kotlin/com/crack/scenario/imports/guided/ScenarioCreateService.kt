package com.crack.scenario.imports.guided

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.service.AiGateway
import com.crack.global.exception.BadRequestException
import com.crack.scenario.imports.ImportDocs
import com.crack.scenario.imports.ImportEventSink
import com.crack.scenario.imports.ImportExecutors
import com.crack.scenario.imports.ImportFormatException
import com.crack.scenario.imports.ImportWorkspace
import com.crack.scenario.imports.ScenarioBuildPipeline
import com.crack.scenario.imports.ScenarioBuildSpec
import com.crack.scenario.imports.SseEventSink
import com.crack.scenario.repository.ScenarioRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDate

/**
 * 질문에 답하면 시나리오 한 벌을 만든다 (DESIGN.md §11.6, D47).
 *
 * URL 가져오기(§11.2)와 **다른 것은 맥락을 어디서 얻느냐뿐이다.** 추출한 페이지 대신
 * 씨앗과 여러 라운드의 질의응답이 맥락이 되고, 문서를 만드는 일은 [ScenarioBuildPipeline]이 똑같이 한다.
 *
 * 1. [start]: 씨앗 한 줄 → 1라운드 질문. LLM 1회
 * 2. [answer]: 답 → 다음 질문 **또는** 미리보기. 라운드마다 LLM 1회, 상한은 `crack.create.max-rounds`
 * 3. [confirm]: 이름과 인물 목록을 확정 → SSE로 생성
 */
@Service
class ScenarioCreateService(
    private val gateway: AiGateway,
    private val jobs: CreateJobStore,
    private val workspace: ImportWorkspace,
    private val executors: ImportExecutors,
    private val pipeline: ScenarioBuildPipeline,
    private val scenarioRepository: ScenarioRepository,
    private val objectMapper: ObjectMapper,
    private val properties: CreateProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // --- 1단계: 씨앗 ---

    fun start(seed: String, provider: String? = null): CreateRoundResponse {
        val trimmed = seed.trim()
        if (trimmed.isEmpty()) throw BadRequestException("무엇을 만들지 한 줄이라도 적어 주세요")
        val job = jobs.create(trimmed.take(MAX_SEED_CHARS))
        log.info("시나리오 만들기 시작: jobId={}, 씨앗={}자", job.id, trimmed.length)
        return askRound(job, provider)
    }

    // --- 2단계: 답 ---

    fun answer(jobId: String, answers: Map<String, String>, provider: String? = null): CreateRoundResponse {
        val job = jobs.require(jobId)
        if (job.done) throw BadRequestException("이미 준비가 끝난 작업입니다. 생성으로 넘어가세요")
        val current = job.rounds.lastOrNull() ?: throw BadRequestException("아직 질문이 없습니다")
        // 답을 먼저 기록한다. LLM이 형식을 깨서 502가 나도 사용자가 같은 답을 다시 보내면 이어진다
        val answered = job.copy(
            rounds = job.rounds.dropLast(1) + current.copy(answers = sanitize(answers)),
        )
        return askRound(jobs.save(answered), provider)
    }

    /**
     * 라운드 하나. LLM이 다음 질문을 주면 라운드를 늘리고, 준비가 됐다면 미리보기를 담는다.
     * **라운드 상한에 닿으면 더 묻지 말라고 지시한다**(빈 답 때문에 무한히 되묻는 것을 막는다).
     */
    private fun askRound(job: CreateJob, provider: String?): CreateRoundResponse {
        val next = job.round + 1
        // 상한을 **넘어선** 호출이 마무리다. 상한이 3이면 질문 라운드 3번을 다 쓰고 4번째 호출이 미리보기를 만든다
        val last = next > properties.maxRounds.coerceAtLeast(1)
        val raw = gateway.chat(
            AiRequest(
                systemPrompt = CreatePrompts.system(job),
                messages = listOf(
                    ChatMessage(
                        MessageRole.USER,
                        CreatePrompts.roundStep(next, properties.maxQuestionsPerRound.coerceAtLeast(1), last),
                    ),
                ),
                purpose = AiPurpose.CHAT,
            ),
            provider,
        )
        val result = CreateOutputParser.parse(objectMapper, raw, job.title.ifBlank { job.seed.take(30) })
        val calls = job.llmCalls + 1

        if (result.done) {
            val preview = result.preview
                ?: throw ImportFormatException("준비가 끝났다고 했는데 미리보기가 비어 있습니다")
            val finished = job.copy(
                title = result.title,
                suggestedName = ImportDocs.safeName(result.suggestedName) ?: "scenario",
                preview = preview,
                llmCalls = calls,
            )
            jobs.save(finished)
            val batches = pipeline.batchCount(preview.characters.size)
            log.info("시나리오 만들기 준비 완료: jobId={}, 라운드={}, 인물={}명", job.id, job.round, preview.characters.size)
            return response(finished, estimatedCalls = calls + 1 + batches + 1, seconds = pipeline.estimatedSeconds(batches))
        }

        val questions = result.questions.take(properties.maxQuestionsPerRound.coerceAtLeast(1))
        val asked = job.copy(
            title = result.title,
            suggestedName = ImportDocs.safeName(result.suggestedName) ?: "scenario",
            rounds = job.rounds + CreateRound(next, questions),
            llmCalls = calls,
        )
        return response(jobs.save(asked))
    }

    private fun response(job: CreateJob, estimatedCalls: Int = 0, seconds: Int = 0) = CreateRoundResponse(
        jobId = job.id,
        round = job.round,
        done = job.done,
        suggestedName = job.suggestedName,
        title = job.title,
        questions = if (job.done) emptyList() else job.rounds.lastOrNull()?.questions.orEmpty(),
        preview = job.preview,
        estimatedLlmCalls = estimatedCalls,
        estimatedSeconds = seconds,
    )

    /** 답이 길어도 프롬프트를 삼키지 않게 자른다. 빈 답은 그대로 둔다(프롬프트에서 "네가 정하라"가 된다). */
    private fun sanitize(answers: Map<String, String>): Map<String, String> =
        answers.mapValues { (_, value) -> value.trim().take(MAX_ANSWER_CHARS) }

    // --- 3단계: 생성 ---

    fun confirm(jobId: String, request: CreateConfirmRequest, provider: String? = null): SseEmitter {
        val job = jobs.require(jobId)
        val preview = job.preview ?: throw BadRequestException("아직 질문이 끝나지 않았습니다")
        val name = ImportDocs.safeName(request.name)
            ?: throw BadRequestException("시나리오 이름이 올바르지 않습니다: '${request.name}'")
        if (scenarioRepository.existsByName(name)) {
            throw BadRequestException("시나리오 '$name'이(가) 이미 존재합니다. 다른 이름을 쓰세요(덮어쓰지 않습니다)")
        }
        workspace.requireAvailable(name)

        val characters = (request.characters ?: preview.characters)
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.trim() }
        val title = request.title?.trim()?.ifEmpty { null } ?: job.title.ifBlank { name }
        val emitter = SseEmitter(SSE_TIMEOUT_MS)
        val sender = SseEventSink(emitter)
        executors.runner.submit {
            try {
                generate(job, name, title, characters, provider, sender)
            } finally {
                sender.finish()
            }
        }
        return emitter
    }

    /**
     * 씨앗과 질의응답을 맥락으로 한 생성. 실제 작업은 [ScenarioBuildPipeline]이 한다.
     * 테스트가 SSE 없이 바로 부를 수 있게 [ImportEventSink]만 받는다.
     */
    internal fun generate(
        job: CreateJob,
        name: String,
        title: String,
        characters: List<CreateCharacter>,
        provider: String?,
        sink: ImportEventSink,
    ) {
        val source = ImportDocs.originLine(SOURCE, LocalDate.now())
        pipeline.run(
            ScenarioBuildSpec(
                jobId = job.id,
                name = name,
                title = title,
                characterNames = characters.map { it.name.trim() },
                prompts = CreatePrompts.forJob(job, characters),
                source = source,
                protagonistName = protagonistName(job),
                provider = provider,
                priorLlmCalls = job.llmCalls,
                knownSection = true, // T35: 인물 문서에 `## 알고 있는 것`
                privateSection = true, // T35: 주인공 문서에 `(비공개)`
                // 등록할 주소가 없다. 사용자가 나중에 채우게 인물 태그 목록만 남긴다
                images = { written -> ImportDocs.imagePlaceholders(written.values.toList(), source) },
                onPublished = { jobs.remove(job.id) },
            ),
            sink,
        )
    }

    /** 주인공 이름을 물은 질문의 답. 이름 줄이 비었을 때 채워 넣는다(프롤로그의 `{{user}}` 치환에 쓰인다). */
    private fun protagonistName(job: CreateJob): String? {
        val pairs = job.rounds.flatMap { round ->
            round.questions.mapNotNull { q -> round.answers[q.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { q.text to it } }
        }
        val match = pairs.firstOrNull { (text, _) -> "이름" in text && ("주인공" in text || "당신" in text) }
            ?: pairs.firstOrNull { (text, _) -> "이름" in text }
        return match?.second?.takeIf { it.length <= 40 }
    }

    companion object {
        /** 인물 25명이면 3~6분이 걸린다. 넉넉하게 잡는다. */
        const val SSE_TIMEOUT_MS = 30 * 60 * 1000L
        const val MAX_SEED_CHARS = 4000
        const val MAX_ANSWER_CHARS = 1000
        const val SOURCE = "질문으로 생성"
    }
}
