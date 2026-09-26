package com.crack.scenario.imports

/**
 * 가져오기 프롬프트 (DESIGN.md §11.3).
 *
 * **추출 컨텍스트는 시스템 프롬프트에 두고 단계별 지시만 유저 메시지로 보낸다.**
 * 인물 배치가 여러 번 돌아도 프롬프트 접두사가 같아 캐시가 살아 있다.
 */
object ImportPrompts {

    fun system(page: ExtractedPage): String = buildString {
        append(
            """
            너는 웹페이지의 설정 자료를 AI 롤플레이 시나리오 문서로 옮기는 편집자다.

            원칙
            - 페이지에 있는 사실을 그대로 옮긴다. 페이지에 없는 설정을 지어내지 않는다. 자료가 없으면 비워 둔다.
            - 사용자 답변으로 받은 정보는 그대로 반영한다.
            - 한국어로 쓴다. 문서는 마크다운이다.
            - **요청한 태그(또는 JSON)만 출력한다.** 태그 밖에 인사, 설명, 코드 펜스를 붙이지 않는다.

            === 페이지 자료 시작 ===
            """.trimIndent(),
        )
        append('\n')
        append(page.context())
        append("\n=== 페이지 자료 끝 ===\n")
    }

    /** 1단계 분석. JSON 하나를 받는다. */
    fun analyze(): String =
        """
        페이지를 읽고 아래 JSON **하나만** 출력해라.

        {
          "title": "시나리오 제목 (페이지의 작품 이름)",
          "suggestedName": "폴더 이름으로 쓸 짧은 이름. 한글 또는 영문, 공백과 / \ : * ? 없이",
          "characters": [
            {"name": "인물 이름", "alias": "별칭 (없으면 빈 문자열)", "org": "소속 (없으면 빈 문자열)", "imageUrl": "인물 이미지 주소 (없으면 빈 문자열)"}
          ],
          "questions": [
            {"id": "q1", "text": "사용자에게 물을 질문", "placeholder": "답 예시"}
          ]
        }

        - `characters`: 페이지에 등장하는 인물을 **전원** 넣는다. 스크립트 데이터에 인물 배열이 있으면 그 순서와 개수를 그대로 따른다.
          `imageUrl`은 인물 데이터의 이미지 필드를 절대 주소(https://…)로 적는다. `data:`로 시작하는 값은 빈 문자열로 둔다.
        - `questions`: 3~5개. **페이지에 없는 정보만** 묻는다. 설정집이라 주인공이 없으면 주인공 이름·성별·나이·소속 세력·시작 시점·목표를 묻는다.
          페이지에 이미 있는 것은 묻지 않는다. `id`는 `q1`부터 차례로 붙인다.
        """.trimIndent()

    /** 2단계: 세계관 · 시나리오 · 키워드북. */
    fun worldStep(answers: String): String =
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

        - `world`: 페이지의 시대·지역·세력 구조·규칙·분위기를 정리한다. 섹션마다 3줄 이상.
        - `scenario`: 사용자 답변의 시작 시점과 목표를 반영해 첫 장면을 정한다. `관계 설정`에는 주인공과 주요 인물의 초기 관계를 쓴다.
        - `keywords`: 세력·지명·용어·직위 등 8~20개 항목. **항목마다 `키워드:` 줄이 반드시 있어야 한다.** 내용이 없는 항목은 만들지 않는다.
        """.trimIndent() + answerSection(answers)

    /** 3단계: 인물 배치 하나. */
    fun charactersStep(names: List<String>, answers: String): String =
        """
        2단계. 아래 인물의 문서를 만들어라. **요청한 인물만, 한 명도 빠뜨리지 말고** 각각 하나씩.

        대상 (${names.size}명): ${oneLine(names)}

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
        ## 말투
        ## 대사 예시
        - "(예시 3개)"
        ## 감정 표현 규칙
        ## 첫 인사
        </character>

        - 페이지의 인물 데이터(소속·계급·나이·성별·MBTI·성향 키워드·무공/능력·소개문)를 빠짐없이 녹인다. 이미지 주소는 문서에 쓰지 않는다.
        - `외모`는 페이지에 묘사가 없으면 소속·무기·분위기에서 추정할 수 있는 만큼만 절제해 쓴다.
        - `## 기억` 섹션은 쓰지 않는다. 시스템이 붙인다.
        - 주인공을 만들지 않는다.
        """.trimIndent() + answerSection(answers, "사용자 답변 (참고)")

    /** 4단계: 주인공 + 첫 메시지. */
    fun protagonistStep(answers: String, characterNames: List<String>): String =
        """
        3단계. 주인공 문서와 첫 메시지를 만들어라. **사용자 답변을 그대로 반영한다.**

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
        </protagonist>
        <prologue>
        [인물: (첫 장면의 중심 인물 이름)]

        (첫 메시지 300~600자. 지문은 *기울임*, 대사는 "…". 주인공을 부를 때는 {{user}}라고 쓴다.
         사용자 답변의 시작 시점에서 시작하고, 마지막은 주인공이 반응할 여지를 남긴다)
        </prologue>

        - `현재 상태`는 시작 시점의 상태만 쓴다. `## 변화 기록` 섹션은 쓰지 않는다. 시스템이 붙인다.
        - 첫 메시지에 등장시킬 인물은 다음 중에서 고른다: ${oneLine(characterNames.take(30))}
        - **`prologue`의 첫 줄은 `[인물: 이름]` 태그다.** 첫 장면에 등장하는 중심 인물 한 명을 위 목록에서 골라
          목록에 적힌 이름 그대로 쓰고, 빈 줄을 하나 둔 뒤 본문을 시작한다. 이 줄은 독자에게 보이지 않고, 인물 이미지를 고르는 데 쓰인다.
          첫 장면에 인물이 없으면(주인공 혼자면) 태그 줄을 쓰지 않는다.
        """.trimIndent() + answerSection(answers)

    /**
     * 사용자 답변 구획. **trimIndent 뒤에 이어 붙인다.**
     * 여러 줄 값을 `"""…\$answers…""".trimIndent()` 안에 끼우면 공통 들여쓰기가 0이 되어
     * 템플릿 전체의 들여쓰기가 그대로 남는다(BUG: 프롬프트가 8칸 들여쓰인 채 나갔다).
     */
    /** 이름 목록을 한 줄로. 줄바꿈이 섞이면 trimIndent가 깨지므로 지운다. */
    private fun oneLine(names: List<String>): String =
        names.joinToString(", ") { it.replace(Regex("""\s+"""), " ").trim() }

    private fun answerSection(answers: String, title: String = "사용자 답변"): String =
        "\n\n" + title + "\n" + answers.trim() + "\n"

    /** 사용자 답변을 프롬프트에 넣을 텍스트로. 답이 없으면 안내 문구. */
    fun formatAnswers(questions: List<ImportQuestion>, answers: Map<String, String>): String {
        val lines = questions.mapNotNull { q ->
            val answer = answers[q.id]?.trim()
            if (answer.isNullOrEmpty()) null else "- ${q.text}\n  → $answer"
        }
        val extra = answers.filterKeys { key -> questions.none { it.id == key } }
            .filterValues { it.isNotBlank() }
            .map { (key, value) -> "- $key\n  → ${value.trim()}" }
        val all = lines + extra
        return if (all.isEmpty()) "(사용자가 답하지 않았다. 페이지 자료만으로 자연스럽게 정한다)" else all.joinToString("\n")
    }
}
