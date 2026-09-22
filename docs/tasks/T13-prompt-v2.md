# T13 프롬프트 조립 v2

- **상태**: REVIEW
- **웨이브**: 4
- **의존**: T05, T06, T07, T08
- **브랜치**: `task/T13-prompt-v2`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §6 / 결정 D9, D10

## 목표
매 턴 프롬프트를 **필요한 것만** 담도록 다시 만든다. LLM 사전 호출은 하지 않는다. 뒤따르는 기능이 빈 하나만 추가하면 되도록 기여자(contributor) 구조를 도입한다.

## 범위
- `prompt/**` (PromptAssembler 재작성, 신규 `prompt/contributor/**`)
- `chat/flow/ConversationBuilder`(T07)의 원문 범위 로직
- 신규 `prompt/api/PromptPreviewController`
- 관련 테스트

## 구현 내용
1. `PromptContributor`, `PromptSlot`, `PromptContext`를 DESIGN.md §6 그대로 만든다. 조립기는 슬롯 순서대로 모은다.
2. **기본 기여자:** BASE(기본 규칙 + 출력 형식), WORLD, SCENARIO(scenario.md + 연대기), PROTAGONIST, CHARACTERS(§6.2 활성 인물), USER_NOTE
   - **BASE 규칙 보강:** 유저 입력의 `**…**`는 상황 묘사이고 `"…"`는 대사라는 설명을 추가한다
   - **연대기 넣는 방식:** `## 장 요약` + 최근 회차 원문(연대기 예산 안에서)
3. **활성 인물 (D9):** `StoryState.companions` ∪ `KeywordMatcher`(인물 파일명과 별칭, 최근 N=6 메시지와 이번 입력). N은 `crack.prompt.keyword-scan-messages`로 설정한다.
4. **대화 원문 범위:** DESIGN.md §6.1(`recorded_through_turn - overlap`, 상한 `max-raw-turns`). T14 이전이면 `recorded_through_turn`을 0으로 본다. T14가 먼저 머지됐으면 그 필드를 쓴다.
5. **BOTTOM 슬롯:** 마지막 유저 메시지 앞에 `[지시]` 블록으로 붙인다. 이번 턴 지시(`turnInstruction`: 이어쓰기, 재생성 지시, `/` 명령)를 여기에 넣는 기여자를 만든다. 지속 지시 기여자는 T16이 추가한다.
6. **측정**
   - 섹션별 글자 수를 INFO 로그로 남긴다
   - `GET /api/stories/{id}/prompt-preview`로 섹션별 크기와 전문을 돌려준다
   - 샘플 픽스처로 v1(모든 인물 주입)과 v2의 크기를 비교한 값을 작업 로그와 `Plan-roadmap.md` §2.4에 기록한다

## 완료 조건
- [x] `./gradlew test` 통과
- [x] 테스트 항목
  - 슬롯 순서
  - 동행 인물과 별칭 매칭으로 인물 선택(언급 없는 인물 제외)
  - 원문 범위 경계
  - BOTTOM 위치
  - preview 응답
- [x] 상태 `REVIEW` + 작업 로그 (PR·머지는 오케스트레이터)

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T13-prompt-v2`(worktree).

**한 일**
- `prompt/contributor/`: `PromptContributor`(+ 기본 `name`), `PromptSlot`, `PromptContext`(DESIGN §6 그대로). 기본 기여자 8개: `BaseContributor`(기본 규칙 + 유저 입력 규칙 + 출력 형식), `WorldContributor`, `ScenarioContributor`, `ChronicleContributor`(장 요약 + 예산 안의 최근 회차), `ProtagonistContributor`, `CharactersContributor`, `UserNoteContributor`(옛 `memory/must_remember.md` 폴백), `TurnInstructionContributor`(BOTTOM 100). `ActiveCharacterSelector`(동행 ∪ 파일명·별칭 매칭, D9).
- `prompt/context/RecordedTurnSource`: `fun interface RecordedTurnSource { fun recordedThroughTurn(storyId: Long): Int }` + `RecordedTurnSource.NONE`(항상 0).
- `prompt/config/PromptProperties`: `crack.prompt.keyword-scan-messages`(6), `overlap-turns`(2), `max-raw-turns`(30).
- `prompt/service/PromptAssembler`(v2 새로 작성): `assemble(storyId, beforeSeq?, turnInstruction?, pendingInput?)` → `AssembledPrompt`. `buildSections(ctx)`는 DB 없이 쓸 수 있다. 조립마다 섹션별 글자 수 INFO 로그.
- `prompt/service/LegacyPromptAssembler`: 옛 v1(모든 인물, 파일 기반 대화)을 이름만 바꿔 옮겼다(`@Deprecated`). 옛 `/chat` 흐름(`ChatService`) 전용, T12에서 삭제.
- `chat/flow/ConversationBuilder`: 원문 범위(§6.1)와 `RawWindow`, 가상 입력(`pendingInput`). `[지시]` 블록 규칙은 T07 그대로.
- `chat/flow/ChatFlowService`: 전송·재생성·이어쓰기가 `PromptAssembler.assemble`을 쓴다. 이번 턴 지시가 BOTTOM 기여자를 거쳐 `[지시]`로 들어간다. 안 쓰게 된 `ScenarioRepository`, `DataPaths`, `ConversationBuilder` 주입을 뺐다.
- `prompt/api/PromptPreviewController`: `GET /api/stories/{id}/prompt-preview?input=`.
- `docs/DESIGN.md` §6: 아래 "새로 정한 것" 반영. `Plan-roadmap.md` §2.4: 측정값.
- 테스트 36개 추가: `PromptContributorsTest`(15), `ConversationBuilderTest`(+6), `PromptAssemblerSlotOrderTest`(2), `PromptAssemblerTest`(9, `@SpringBootTest`), `PromptPreviewApiTest`(3), `PromptSizeComparisonTest`(1). 옛 `PromptAssemblerTest`는 `LegacyPromptAssemblerTest`로 이름만 바꿨다.

**범위 밖 수정 (최소한)**
- `chat/flow/ChatFlowService.kt`: v2 조립기로 바꾸려면 호출부를 고쳐야 했다(프롬프트 람다 한 곳과 생성자).
- `chat/service/ChatService.kt`: 타입을 `LegacyPromptAssembler`로 바꾼 한 줄. 옛 흐름 동작은 그대로다.
- `story/StoryIsolationTest.kt`: v2는 언급된 인물만 넣으므로, 두 인물을 언급한 가상 입력으로 조립하도록 도우미 하나를 바꿨다(검증 내용은 그대로).

**설계 판단 (DESIGN.md §6에 반영)**
- **v1 분리:** 옛 `/chat` 흐름이 T12까지 살아 있어서 v1 메서드(`assembleSystemPrompt`, `loadConversationContext`)를 지울 수 없다. `PromptAssembler`를 기여자 기반으로 새로 쓰고 v1은 `LegacyPromptAssembler`로 옮겼다. v1 대비 측정에도 쓴다.
- **`RecordedTurnSource` 기본값:** `@ConditionalOnMissingBean`은 사용자 설정 클래스에서 등록 순서에 따라 틀릴 수 있어서, `ConversationBuilder`가 `ObjectProvider.getIfAvailable() ?: NONE`으로 읽는다. T14 구현 빈을 하나 등록하면 바로 쓰인다(두 개 이상이면 예외).
- **원문 범위:** `afterTurn = max(recorded − overlap, 최대 턴 − max-raw-turns)`, 기록한 적이 있으면 0 이상(프롤로그 제외). 최대 턴은 넣을 메시지(재생성 대상 앞까지) 기준이다. `recorded = 1`처럼 overlap보다 작아도 프롤로그는 뺀다(§6.1 "첫 기록 이후에는 빠진다").
- **`recentText`:** 이번 입력을 뺀 최근 6개 메시지 + 이번 입력. 원문 범위와 무관하게 전체 대화에서 고른다(원문에서 빠진 옛 언급은 스캔하지 않는다).
- **BOTTOM 합치기:** BOTTOM 기여를 빈 줄로 이어 `[지시]` 블록 하나로 만든다. T07 형식(`[지시]\n…\n\n유저 메시지`)과 테스트는 그대로다.
- **동행 이름 해석:** 파일명 → 별칭 정확 일치 → 버림. T14 관리자가 별칭으로 적어도 인물을 찾게 했다.
- **연대기:** 최신 회차부터 예산(`crack.memory.budget.chronicle`, 12000) 안에서 담고 오래된 순으로 쓴다. 가장 최근 회차 하나는 예산을 넘어도 넣는다(압축은 T14 몫).
- **기여자 실패:** 예외는 그 섹션만 빼고 ERROR 로그. 문서 하나가 깨져도 응답은 나간다.
- **섹션 이름:** `PromptContributor.name` 기본값을 추가했다(클래스 이름에서 `Contributor`를 떼고 snake_case). 인터페이스에 기본 구현이 있어 T16/T17/T20은 신경 쓰지 않아도 된다.
- **BASE:** v1 규칙과 출력 형식(감정 태그 예시 포함)을 유지하고, 유저 입력 규칙(`**…**` 상황 묘사, `"…"` 대사, `[지시]`는 이야기 밖 지시)과 "연대기·기억은 사실로 존중" 한 줄을 더했다.
- **preview:** `input` 쿼리로 저장하지 않는 가상 입력을 받는다. 다음 전송 때 어떤 인물이 들어갈지 확인하는 데 쓴다.
- `GET /messages`의 `recordedThroughTurn`은 T14가 같은 DTO를 고치는 중이라 건드리지 않았다(여전히 0).

**확인 방법**
- `cd crack-backend && ./gradlew test`: 405개 전부 통과(신규 36).
- 로컬 `application.yml`을 스크래치로 치운 상태에서도 `./gradlew clean test` 405개 전부 통과 후 원복.
- 크기 측정: `PromptSizeComparisonTest`(픽스처), 실제 데이터는 커밋하지 않은 임시 테스트로 **읽기만** 해서 쟀다(아래).
- 실제 CLI/API 프로바이더와 브라우저로는 확인하지 않았다.

**측정 (시스템 프롬프트, 대화 원문 제외)**
- 마도생존기(인물 24명): v1 192,013 B(85,837자) → v2 56,220 B(24,895자, 언급 인물 없음, −71%) / 79,303 B(35,502자, 3명 매칭, −59%).
- sample-scenario: v1 1,560자 → v2 1,546자(0명) / 1,703자(1명) / 1,856자(2명). 인물 문서가 작아서 base 증가분(약 300자)과 비슷하다.
- 실제 데이터는 아직 T09 이전 전 구조라 원본 폴더 문서 + 각 스토리 `chat_latest.md`의 최근 메시지로 쟀다. 자세한 표는 `Plan-roadmap.md` §2.4.

**겪은 문제**
- 픽스처 크기 비교에서 "1명 주입 v2 < v1"을 단언했다가 실패했다. 픽스처 인물이 150자 정도라 base 증가분이 더 컸다. 단언을 인물 섹션 크기 비교로 바꾸고, 전체 크기는 기록만 한다.
- 인물 섹션 순서 단언이 틀렸다(키워드 매칭 인물은 파일명 순이라 `무극` < `설월`). 테스트를 고쳤다.

**다음 작업자 주의점**
- **기여자 붙이는 법 (T16/T17/T20):** `PromptContributor`를 구현한 `@Component` 하나만 추가한다. 조립기는 고치지 않는다.
  - `slot`: T17 `KEYWORDS`, T20 `IMAGES`, T16 지속 지시 `BOTTOM`. `order`: 같은 슬롯 안 순서. T16 지속 지시는 **order 0**(이번 턴 지시 `turn_instruction`이 100이라 그 앞). 섹션 제목(`=== … ===`)은 기여자가 직접 붙인다.
  - `contribute(ctx)`는 null/공백이면 생략된다. 스토리 폴더(`ctx.storyDir`)만 읽는다. LLM 호출 금지.
  - 키워드 매칭은 `KeywordMatcher.match(entries, ctx.recentText, limit)`. `recentText`는 조립기가 만든다.
  - 사용자 정의 명령(T16)은 `PromptAssembler.assemble(..., turnInstruction = 명령 프롬프트)`로 넘기면 BOTTOM에 들어간다. `ChatFlowService.send`는 아직 `turnInstruction`을 넘기지 않는다.
  - 결과 확인은 `GET /api/stories/{id}/prompt-preview`(섹션 `name`/`slot`/`chars`/`content`). T17의 "발동한 키워드 목록"은 응답에 필드가 없으니 `PromptPreviewResponse`와 `AssembledPrompt`에 추가해야 한다.
- **T14 연결(오케스트레이터):** `RecordedTurnSource` 구현 빈 하나(예: `Story.recordedThroughTurn`을 읽는 `@Component`)를 등록하면 원문 범위에 바로 반영된다. `ChatFlowService.state`의 `recordedThroughTurn = 0`은 T14가 고친다.
- **T12:** `LegacyPromptAssembler`(+ `LegacyPromptAssemblerTest`, `PromptSizeComparisonTest`의 v1 비교)를 옛 흐름과 함께 지운다. 크기 비교 테스트는 v1 부분만 빼면 된다.
- 조립 INFO 로그는 매 턴 한 줄이다. 인물 선택을 위해 `characters/` 문서를 매 턴 읽는다(마도생존기 24명 약 200KB, 로컬 디스크라 문제없는 수준). 활성 인물 보고용으로 한 번 더 읽는다.

