package com.crack.scenario.imports

/** 페이지에서 찾은 인물 하나 (DESIGN.md §11.2). */
data class ImportCharacter(
    val name: String,
    val alias: String = "",
    val org: String = "",
    val imageUrl: String = "",
)

/** 사용자에게 물을 질문. 페이지에 없는 정보만 묻는다. */
data class ImportQuestion(
    val id: String,
    val text: String,
    val placeholder: String = "",
)

/** LLM 분석 결과(캐시용). */
data class ImportAnalysis(
    val title: String,
    val suggestedName: String,
    val characters: List<ImportCharacter>,
    val questions: List<ImportQuestion>,
)

// --- 요청 ---

data class ImportAnalyzeRequest(val url: String = "")

data class ImportConfirmRequest(
    val name: String = "",
    val title: String? = null,
    val answers: Map<String, String> = emptyMap(),
)

// --- 응답 ---

data class ImportAnalyzeResponse(
    val jobId: String,
    val url: String,
    val suggestedName: String,
    val title: String,
    val characters: List<ImportCharacter>,
    val characterCount: Int,
    val imageCount: Int,
    val questions: List<ImportQuestion>,
    val truncated: Truncation,
    val estimatedLlmCalls: Int,
    val estimatedSeconds: Int,
)

/** SSE `step` 이벤트. [percent]는 0~100. */
data class ImportStepEvent(
    val step: String,
    val label: String,
    val index: Int,
    val total: Int,
    val percent: Int,
    val detail: String = "",
)

/** SSE `done` 이벤트. */
data class ImportDoneEvent(
    val scenarioId: Long,
    val name: String,
    val title: String,
    val files: List<String>,
    val characterCount: Int,
    val llmCalls: Int,
    val elapsedSeconds: Long,
)

/** 가져오기 SSE 이벤트 이름 (DESIGN.md §11.2). */
object ImportSseEvents {
    const val STEP = "step"
    const val DONE = "done"
    const val ERROR = "error"
}

/**
 * 생성 진행 상황을 받는 곳. SSE 구현은 `ScenarioImportService.SseEventSink`다.
 * 테스트는 이벤트를 모으는 구현으로 바꿔 끼워 SSE 없이 파이프라인을 돌린다.
 */
interface ImportEventSink {
    fun step(event: ImportStepEvent)
    fun done(event: ImportDoneEvent)
    fun error(message: String)
}
