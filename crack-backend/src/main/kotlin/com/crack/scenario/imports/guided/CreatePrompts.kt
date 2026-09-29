package com.crack.scenario.imports.guided

import com.crack.scenario.imports.ScenarioBuildPrompts

/**
 * 질문으로 시나리오 만들기 프롬프트 (DESIGN.md §11.6).
 *
 * URL 가져오기(`ImportPrompts`)와 구조가 같다. 다른 것은 **맥락뿐**이다 —
 * 추출한 페이지 대신 **씨앗과 지금까지의 질의응답**이 시스템 프롬프트에 들어간다.
 *
 * 여러 줄 값을 `""" … ${'$'}x … """.trimIndent()` 안에 끼우면 공통 들여쓰기가 0이 되어
 * 템플릿 전체가 들여쓰인 채 나간다. 그래서 맥락 구획은 **trimIndent 뒤에 이어 붙인다**
 * (`ImportPrompts`에서 겪은 것과 같다).
 */
object CreatePrompts {

    /** 라운드별 주제. 상한을 늘려도 마지막 라운드는 [LAST_TOPIC]을 쓴다. */
    private val TOPICS = listOf(
        "큰 틀 — 시대와 장소, 분위기, 주인공이 어디에 서 있는지, 이야기의 중심 갈등",
        "인물 — 주인공 곁과 맞은편에 누가 있는지, 각자 주인공과 어떤 관계인지",
        "시작 — 첫 장면이 어디서 어떻게 열리는지, 무엇이 주인공을 움직이게 하는지",
    )
    private const val LAST_TOPIC = "아직 비어 있는 것 중 이야기를 시작하는 데 꼭 필요한 것"

    // --- 시스템 프롬프트 (질문 라운드와 생성이 함께 쓴다) ---

    fun system(job: CreateJob, characters: List<CreateCharacter> = emptyList()): String = buildString {
        append(
            """
            너는 사용자와 함께 AI 롤플레이 시나리오를 처음부터 만드는 기획자다.

            원칙
            - 사용자의 씨앗과 답변이 **설정의 근거**다. 어긋나게 바꾸지 않는다.
            - 사용자가 정하지 않은 것 중 **장르 상식으로 채울 수 있는 것은 네가 채운다.** 사용자에게 떠넘기지 않는다.
            - 답하지 않았거나 "모르겠다"고 한 것은 네가 정한다. 되묻지 않는다.
            - 한국어로 쓴다. 문서는 마크다운이다.
            - **요청한 태그(또는 JSON)만 출력한다.** 태그 밖에 인사, 설명, 코드 펜스를 붙이지 않는다.

            === 지금까지 정해진 것 시작 ===
            """.trimIndent(),
        )
        append("\n\n## 사용자가 준 씨앗\n").append(job.seed.trim()).append('\n')
        job.rounds.forEach { round ->
            append("\n## ").append(round.number).append("라운드 질의응답\n")
            append(formatAnswers(round)).append('\n')
        }
        job.preview?.let { preview ->
            append("\n## 함께 정리한 뼈대\n")
            if (preview.world.isNotBlank()) append("- 세계: ").append(oneLine(preview.world)).append('\n')
            if (preview.protagonist.isNotBlank()) append("- 주인공: ").append(oneLine(preview.protagonist)).append('\n')
            if (preview.opening.isNotBlank()) append("- 첫 장면: ").append(oneLine(preview.opening)).append('\n')
        }
        if (characters.isNotEmpty()) {
            append("\n## 만들기로 확정한 인물 (").append(characters.size).append("명)\n")
            characters.forEach { character ->
                append("- ").append(character.name.trim())
                val detail = listOf(character.role, character.note).filter { it.isNotBlank() }
                if (detail.isNotEmpty()) append(": ").append(oneLine(detail.joinToString(" / ")))
                append('\n')
            }
        }
        append("\n=== 지금까지 정해진 것 끝 ===\n")
    }

    /** 라운드의 질문과 답. **빈 답은 "네가 정하라"로 바꿔 넣는다** — 되물어 막지 않는다. */
    fun formatAnswers(round: CreateRound): String =
        round.questions.joinToString("\n") { question ->
            val answer = round.answers[question.id]?.trim()
            val value = if (answer.isNullOrEmpty()) "(답하지 않았다. 네가 정한다)" else answer
            "- ${oneLine(question.text)}\n  → $value"
        }.ifBlank { "(질문이 없었다)" }

    // --- 질문 라운드 ---

    /**
     * 라운드 하나의 질문을 받는다. LLM 1회.
     *
     * @param last 상한에 닿은 라운드. 더 묻지 말고 미리보기를 채우게 한다
     */
    fun roundStep(round: Int, maxQuestions: Int, last: Boolean): String {
        val topic = TOPICS.getOrNull(round - 1) ?: LAST_TOPIC
        val head = if (last) {
            """
            마지막 단계다. **더 묻지 말고** 준비를 끝내라. 아래 JSON 하나만 출력한다.
            `done`은 반드시 `true`, `questions`는 빈 배열이다. 정해지지 않은 것은 네가 정한다.
            """.trimIndent()
        } else {
            """
            ${round}라운드 질문을 만들어라. 아래 JSON **하나만** 출력한다.

            이번 라운드에서 다룰 것: $topic
            """.trimIndent()
        }
        return head + "\n\n" + JSON_SHAPE + "\n\n" + (if (last) FINISH_RULES else questionRules(maxQuestions))
    }

    private val JSON_SHAPE =
        """
        {
          "title": "시나리오 제목",
          "suggestedName": "폴더 이름으로 쓸 짧은 이름. 한글 또는 영문, 공백과 / \ : * ? 없이",
          "done": false,
          "questions": [
            {"id": "q1", "text": "사용자에게 물을 질문", "placeholder": "답 예시"}
          ],
          "preview": {
            "world": "세계관 한 문단",
            "protagonist": "주인공 한 문단",
            "opening": "첫 장면 한 문단",
            "characters": [
              {"name": "인물 이름", "role": "한 줄 역할", "note": "주인공과의 관계 한 줄"}
            ]
          }
        }
        """.trimIndent()

    private fun questionRules(maxQuestions: Int): String =
        """
        질문 규칙
        - **최대 ${maxQuestions}개.** 적어도 된다. 아직 정해지지 않은 것 중 **가장 중요한 것**부터 묻는다
        - **씨앗과 지금까지의 답에서 이미 알 수 있는 것은 묻지 않는다.** 같은 것을 다시 묻지 않는다
        - **한 질문에 하나만 묻는다.** "이름과 나이와 소속은?"처럼 묶지 않는다
        - `placeholder`에 **답 예시**를 넣는다. 사용자가 무엇을 적어야 하는지 바로 알게
        - **설정 상식은 네가 채운다.** 문파 이름, 지명, 용어, 역사처럼 장르를 알면 정할 수 있는 것을
          사용자에게 정하라고 떠넘기지 않는다. 사용자에게는 **취향과 방향**을 묻는다 —
          어떤 인물을 곁에 두고 싶은지, 어떤 갈등을 겪고 싶은지, 어디서 시작하고 싶은지
        - `id`는 `q1`부터 차례로 붙인다

        더 물을 것이 없으면 `done`을 `true`로 하고 `questions`를 빈 배열로 둔 뒤 `preview`를 채운다.
        아직 물을 것이 남았으면 `done`을 `false`로 하고 `preview`는 `null`로 둔다.
        """.trimIndent()

    private val FINISH_RULES =
        """
        미리보기 규칙
        - `world`·`protagonist`·`opening`은 각각 **한 문단**이다. 사용자가 읽고 고칠 요약이지 문서가 아니다
        - `characters`는 주인공을 **뺀** 3~6명이다. 씨앗과 답변에 나온 인물을 먼저 넣고, 모자라면 네가 채운다
        - `note`에는 **주인공과의 관계**를 한 줄로 쓴다
        """.trimIndent()

    // --- 생성 단계 (공용 파이프라인이 쓴다) ---

    /**
     * 확정된 맥락으로 단계 프롬프트를 묶는다.
     * 파이프라인은 입력원을 모르고 [ScenarioBuildPrompts]만 본다.
     */
    fun forJob(job: CreateJob, characters: List<CreateCharacter>): ScenarioBuildPrompts =
        object : ScenarioBuildPrompts {
            private val systemPrompt = CreatePrompts.system(job, characters)
            override fun system(): String = systemPrompt
            override fun worldStep(): String = CreatePrompts.worldStep()
            override fun charactersStep(names: List<String>): String = CreatePrompts.charactersStep(names)
            override fun protagonistStep(characterNames: List<String>): String =
                CreatePrompts.protagonistStep(characterNames)
        }

    /** 1단계: 세계관 · 시나리오 · 키워드북. */
    fun worldStep(): String =
        """
        1단계. 세계관·시나리오·키워드북 세 문서를 만들어라.

        <world>
        # 세계관
        ## 시대
        ## 장소
        ## 세계 규칙
        ## 분위기
        ## 기타 설정
        </world>
        <scenario>
        # 시나리오
        ## 초기 상황
        ## 장면
        ## 관계 설정
        </scenario>
        <keywords>
        ## (항목 제목)
        키워드: 쉼표로 구분한 키 2~5개
        (프롬프트에 넣을 내용 2~5줄)
        </keywords>

        - `world`: 정해진 것을 정리하고 **빈 곳은 장르 상식으로 채운다.** 섹션마다 3줄 이상.
        - `scenario`: 위에 정리한 첫 장면에서 시작한다. `관계 설정`에는 주인공과 주요 인물의 초기 관계를 쓴다.
        - `keywords`: 세력·지명·용어·직위 등 8~20개 항목. **항목마다 `키워드:` 줄이 반드시 있어야 한다.** 내용이 없는 항목은 만들지 않는다.
        - **AI가 지킬 행동 규칙(주인공을 대신 서술하지 않는다 같은 것)은 적지 않는다.** 시스템이 따로 넣는다.
        """.trimIndent()

    /** 2단계: 인물 배치 하나. */
    fun charactersStep(names: List<String>): String =
        """
        2단계. 아래 인물의 문서를 만들어라. **요청한 인물만, 한 명도 빠뜨리지 말고** 각각 하나씩.

        대상 (${names.size}명): ${oneLine(names.joinToString(", "))}

        인물마다 이 형식으로 출력한다.
        <character name="인물 이름">
        # 캐릭터: (이름)
        ## 기본 정보
        - **이름**: (이름)
        - **별칭**: (쉼표로 구분. 없으면 줄을 생략)
        - **나이**:
        - **성별**:
        ## 외모
        ## 성격
        ### 성격 태그
        - (태그 3~6개)
        ## 배경 스토리
        ## 알고 있는 것
        - (이 인물이 **시작부터** 아는 것. 없으면 비워 둔다)
        ## 말투
        ## 대사 예시
        - "(예시 3개)"
        ## 감정 표현 규칙
        ## 첫 인사
        </character>

        - 위에 확정된 역할과 관계를 그대로 살리고, 나머지는 세계관에 맞게 채운다.
        - `알고 있는 것`은 **이 인물이 아는 것만** 적는다. 주인공의 비밀을 아는 인물이 아니면 비워 둔다.
          모두가 아는 상식은 적지 않는다.
        - `## 첫 인사`는 **말투 샘플**이다. 날짜와 장소가 박힌 완결된 장면을 쓰지 않는다. 두세 문장이면 된다.
        - `## 기억` 섹션은 쓰지 않는다. 시스템이 붙인다.
        - 주인공을 만들지 않는다.
        """.trimIndent()

    /** 3단계: 주인공 + 첫 메시지. */
    fun protagonistStep(characterNames: List<String>): String =
        """
        3단계. 주인공 문서와 첫 메시지를 만들어라. **사용자가 정한 것을 그대로 반영한다.**

        <protagonist>
        # 주인공 (사용자)
        ## 기본 정보
        - **이름**:
        - **별칭**: (없으면 줄을 생략)
        - **나이**:
        - **성별**:
        ## 외모
        ## 성격
        ## 배경
        ## 현재 상태
        ## 보유 아이템 / 능력
        ## 비밀 (비공개)
        </protagonist>
        <prologue>
        [인물: (첫 장면의 중심 인물 이름)]

        (첫 메시지 300~600자. 지문은 *기울임*, 대사는 "…". 주인공을 부를 때는 {{user}}라고 쓴다.
         위에 정리한 첫 장면에서 시작하고, 마지막은 주인공이 반응할 여지를 남긴다)
        </prologue>

        - `현재 상태`는 시작 시점의 상태만 쓴다. `## 변화 기록` 섹션은 쓰지 않는다. 시스템이 붙인다.
        - **남이 알 수 없는 것(정체, 숨긴 과거, 진짜 목적)은 제목 끝에 `(비공개)`를 붙인 섹션에만 적는다.**
          그 표시가 있으면 등장인물들은 모르는 정보로 다뤄진다. 알 사람이 있으면 그 인물의 `## 알고 있는 것`에 적었어야 한다.
          숨길 것이 없으면 `## 비밀 (비공개)` 섹션을 비워 둔다.
        - **AI가 지킬 행동 규칙(주인공을 대신 서술하지 않는다 같은 것)은 적지 않는다.** 시스템이 따로 넣는다.
        - 첫 메시지에 등장시킬 인물은 다음 중에서 고른다: ${oneLine(characterNames.take(30).joinToString(", "))}
        - **`prologue`의 첫 줄은 `[인물: 이름]` 태그다.** 첫 장면에 등장하는 중심 인물 한 명을 위 목록에서 골라
          목록에 적힌 이름 그대로 쓰고, 빈 줄을 하나 둔 뒤 본문을 시작한다. 이 줄은 독자에게 보이지 않고, 인물 이미지를 고르는 데 쓰인다.
          첫 장면에 인물이 없으면(주인공 혼자면) 태그 줄을 쓰지 않는다.
        """.trimIndent()

    /** 줄바꿈이 섞이면 trimIndent가 깨지므로 한 줄로 만든다. */
    private fun oneLine(text: String): String = text.replace(Regex("""\s+"""), " ").trim()
}
