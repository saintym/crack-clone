package com.crack.scenario.imports.guided

import com.crack.scenario.imports.ImportQuestion
import java.time.Instant

/**
 * 미리보기의 인물 한 명 (DESIGN.md §11.6).
 * confirm 요청에도 같은 형식을 쓴다. 사용자가 지우거나 더한 결과가 그대로 올라온다.
 */
data class CreateCharacter(
    val name: String = "",
    val role: String = "",
    val note: String = "",
)

/** 생성 직전에 보여 주는 요약. 문서가 아니라 **사람이 읽고 고칠 것**이다. */
data class CreatePreview(
    val title: String = "",
    val world: String = "",
    val protagonist: String = "",
    val opening: String = "",
    val characters: List<CreateCharacter> = emptyList(),
)

/** 질문 라운드 하나. 물은 것과 받은 답을 함께 들고 있다(다음 라운드 프롬프트의 맥락이 된다). */
data class CreateRound(
    val number: Int,
    val questions: List<ImportQuestion>,
    val answers: Map<String, String> = emptyMap(),
)

/**
 * 진행 중인 생성 작업. **불변**이다. 라운드가 늘 때마다 통째로 갈아 끼운다
 * (동시에 두 요청이 들어와도 반쯤 바뀐 상태가 보이지 않는다).
 */
data class CreateJob(
    val id: String,
    val seed: String,
    val title: String = "",
    val suggestedName: String = "",
    val rounds: List<CreateRound> = emptyList(),
    val preview: CreatePreview? = null,
    val llmCalls: Int = 0,
    val createdAt: Instant = Instant.now(),
) {
    val round: Int get() = rounds.size
    val done: Boolean get() = preview != null
}

// --- 요청 ---

data class CreateStartRequest(val seed: String = "")

data class CreateAnswerRequest(val answers: Map<String, String> = emptyMap())

data class CreateConfirmRequest(
    val name: String = "",
    val title: String? = null,
    /** null이면 미리보기 목록 그대로. 주면 **그 목록만** 만든다 */
    val characters: List<CreateCharacter>? = null,
)

// --- 응답 ---

/**
 * start와 answer가 함께 쓰는 응답.
 *
 * - 아직 물을 것이 남았으면 `done=false` + [questions]
 * - 준비됐으면 `done=true` + [preview] + 예상치. 이때 [questions]는 비어 있다
 */
data class CreateRoundResponse(
    val jobId: String,
    val round: Int,
    val done: Boolean,
    val suggestedName: String,
    val title: String,
    val questions: List<ImportQuestion> = emptyList(),
    val preview: CreatePreview? = null,
    val estimatedLlmCalls: Int = 0,
    val estimatedSeconds: Int = 0,
)
