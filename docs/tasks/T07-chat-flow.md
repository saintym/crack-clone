# T07 채팅 흐름 재작성

- **상태**: REVIEW
- **웨이브**: 2
- **의존**: T01, T03
- **브랜치**: `task/T07-chat-flow`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §5.2

## 목표
채팅을 메시지 저장소(T03)와 콜백 스트리밍(T01) 위에서 다시 만든다. 달라지는 동작은 다음과 같다.
- **서버가 응답을 저장**한다.
- 재생성하면 **이전 답변을 후보로 보관**한다 (D17).
- 유저·AI 메시지 **모두 수정**할 수 있고, 이후 진행에 수정 내용이 반영된다 (D2).
- 이어쓰기가 히스토리를 오염시키지 않는다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/chat/api/**`(새 컨트롤러), `chat/flow/**`(새 서비스)
- `chat/service/ChatService.kt`는 새 흐름으로 교체하거나 비워 둔다. 옛 `/chat/*` 컨트롤러는 **남겨 두되** 새 서비스로 위임하거나 그대로 둔다(T12가 삭제)
- 신규 테스트 `src/test/kotlin/com/crack/chat/**`
- **수정 금지:** `prompt/service/PromptAssembler.kt`의 `assembleSystemPrompt` 본문(T08 담당). 호출만 한다

## 구현 내용
1. **API:** DESIGN.md §5.2의 `/api/stories/{id}/messages*` 전부. SSE 이벤트는 `user` / `delta` / `done` / `error`.
2. **대화 컨텍스트 빌더:** `ConversationBuilder`가 `MessageService.list`로 AI 입력 메시지를 만든다.
   - 여기서는 전체 대화를 넣는다(원문 범위 제한은 T13).
   - `PromptAssembler.loadConversationContext`(파일 기반)는 더 이상 쓰지 않는다.
3. **저장 흐름**
   - 전송: 유저 메시지를 저장하고 → `user` 이벤트 → 스트리밍 → `onComplete`에서 ASSISTANT를 저장하고 `done`
   - 재생성: 성공했을 때만 `addVariant`
   - 이어쓰기: 지시문을 저장하지 않고 이번 요청에만 넣는다. 결과는 `kind = CONTINUATION` 새 턴
4. **안정성**
   - `StoryGenerationLock`: 스토리당 생성 1개. 생성 중이면 409
   - 클라이언트가 끊겨도 저장은 끝까지 진행한다
5. **확장 지점**
   - `AfterTurnHook` 인터페이스를 만들고, ASSISTANT를 저장한 뒤 호출한다. 구현은 T14가 한다.
   - 기존 10턴 자동 요약 호출(`memoryService.summarize`)은 **제거**한다. 옛 요약은 파일 기반이라 새 저장소와 맞지 않는다. 기억 기능은 T14 전까지 꺼져 있다는 사실을 작업 로그에 남긴다.
6. **감정 태그 숨김 (D19, DESIGN.md §5.3)**
   - `EmotionTagFilter`를 스트림 리스너 앞에 끼워 `delta`에 태그가 절대 나가지 않게 한다
   - 저장할 때 `emotion`을 분리한다
   - 태그가 없거나 형식이 다른 응답은 그대로 통과시킨다
7. **재생성 대상:** 가장 최근 ASSISTANT만 허용하고, 그 외는 400(D17)
8. **요청 필드:** `ChatRequest`에 `command: String?`(T16이 사용)과 `instruction: String?`(재생성 지시)를 받아 둔다. 동작은 `instruction`만 구현한다.

## 완료 조건
- [x] `./gradlew test` 통과. Fake 프로바이더로 통합 테스트
  - 전송 → 저장 → done
  - 재생성 후보 누적과 선택
  - 스트림 실패 시 원본 보존
  - 수정 후 다음 요청 컨텍스트에 수정 내용 반영
  - 이어쓰기 지시문 미저장
  - 동시 생성 409
  - 감정 태그가 delta와 저장 본문 어디에도 없고 `emotion` 칼럼에만 저장됨(태그가 여러 조각으로 나뉘어 들어오는 경우 포함)
  - 과거 메시지 재생성 400
- [x] 상태 `REVIEW` + 작업 로그 (PR은 오케스트레이터가 머지)

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T07-chat-flow`(worktree).

**한 일**
- `chat/flow/`
  - `EmotionTagFilter`(+ `TaggedResponseListener`): 첫 줄 `[감정: …]`을 스트림에서 떼어 내고 감정 값만 분리. 첫 줄바꿈까지(최대 200자) 버퍼링하되, 버퍼가 태그 앞부분 패턴(`[`, `[ 감`, `[감정 :…`)에서 벗어나는 즉시 흘린다. 태그 뒤 공백·빈 줄도 버린다. 저장은 `parse(fullText)` 결과를 쓴다.
  - `StoryGenerationLock`: 스토리당 1개. 티켓(`acquire` → `Ticket.release`)으로만 풀고, 중복 해제해도 다른 요청의 락을 풀지 않는다.
  - `ConversationBuilder`: `MessageService.list`로 AI 입력을 만든다(전체 대화). 연속된 같은 역할은 빈 줄로 합친다. 이번 턴 지시는 마지막 USER 앞에 `[지시]\n…\n\n` 블록으로 붙이고, 마지막이 ASSISTANT면 `[지시]`만 담은 USER 메시지를 덧붙인다(DESIGN §6 BOTTOM 위치).
  - `AfterTurnHook`/`AfterTurnEvent(storyId, messageId, turnNo, turnCount, mode)`/`GenerationMode(SEND, REGENERATE, CONTINUE)`.
  - `ChatFlowService`: 조회, 전송, 재생성, 이어쓰기, 후보 선택, 수정, 삭제. `GenerationStreamListener`: delta 전달, 완료 시 저장 → 락 해제 → 훅 → `done`, 실패 시 `error`.
- `chat/api/`: `StoryMessageController`(§5.2 전부), 요청 DTO, 컨트롤러 전용 예외 처리(`StoryMessageExceptionHandler`: 409/400/404를 JSON으로).
- 옛 `ChatService`: 10턴 자동 요약(`memoryService.summarize`) 호출과 `MemoryService` 의존성 제거, `@Deprecated` 표시. 옛 `/chat` 컨트롤러와 파일 기반 흐름은 그대로 동작한다(T12가 삭제).
- `docs/DESIGN.md` §5.2: 아래 "새로 정한 것"을 반영(재생성 `messageId`, DELETE 응답, 생성 중 수정 409, continue 400 조건).
- 테스트 52개 추가: `EmotionTagFilterTest`(19, 파라미터 포함), `ConversationBuilderTest`(6), `StoryGenerationLockTest`(2), `StoryMessageApiTest`(25, `@SpringBootTest` + MockMvc + Fake/스크립트 프로바이더).

**기억 기능은 T14 전까지 꺼져 있다.** 옛 10턴 자동 요약은 파일 기반이라 새 저장소와 맞지 않아 제거했다. 새 흐름은 `AfterTurnHook`만 부르고, 구현 빈이 아직 없다.

**설계 판단**
- **재생성 대상 지정:** §5.2의 regenerate body에는 대상 ID가 없어서 "과거 메시지 재생성 400"을 판정할 수 없다. 선택 필드 `messageId`를 추가했다. 주면 **대화의 마지막 메시지**여야 하고(마지막이 USER면 그 USER ID도 허용), 아니면 400. 다른 스토리 메시지면 404. 없으면 서버가 마지막 메시지 기준으로 판단한다.
- **프롤로그 재생성은 400**(오케스트레이터 요청 반영). 프롤로그만 있는 스토리에서 `messageId` 없이/있이 모두 400. 수정(PATCH)은 허용한다. 빈 스토리 재생성도 400.
- **이어쓰기 조건:** 대화가 비었거나 마지막이 USER(응답 없음)면 400. 후자는 턴에 응답이 빠진 채 새 턴이 열리는 것을 막기 위해서다. 프롤로그만 있으면 허용(턴 1 CONTINUATION).
- **이어쓰기 응답 재생성:** 이어쓰기 지시를 다시 넣고(+ 재생성 지시), 후보로 쌓는다.
- **첫 응답 재생성(마지막이 USER):** `appendAssistant(turnNo = 그 USER의 턴)`. 이 경우 재생성 지시는 AI 요청에는 들어가지만 후보 0번에는 기록되지 않는다(`appendAssistant`에 instruction 인자가 없음).
- **생성 중 수정 금지:** 생성 중에는 후보 선택·수정·삭제도 409. 생성 도중 대화가 잘리면 응답이 엉뚱한 턴에 저장될 수 있어서다. 수정도 같은 락을 짧게 잡는다.
- **락 해제 시점:** 저장 직후, `done` 전에 푼다. `done`을 받은 클라이언트가 바로 다음 요청을 보내도 409가 나지 않게 하기 위해서다.
- **AfterTurnHook 호출:** 저장 트랜잭션 커밋 뒤, 프로바이더 스레드에서 `done` 전에 호출. 예외는 로그만 남긴다. 오래 걸리는 일은 훅 안에서 배경으로 넘겨야 한다.
- **빈 응답**(태그만 있거나 공백)은 저장하지 않고 `error`.
- **SSE 매핑에 `produces`를 걸지 않았다.** 걸면 생성 전 400/404/409를 JSON으로 줄 때 `Accept: text/event-stream`과 충돌한다. 예외 처리기는 Content-Type을 `application/json`으로 미리 정해 협상을 건너뛴다. 전역 `GlobalExceptionHandler`는 건드리지 않고 컨트롤러 전용 `@RestControllerAdvice(assignableTypes=…)` + 최우선 순서로 처리했다.
- **요청 DTO:** 작업 파일은 `ChatRequest`에 `command`/`instruction`을 두라고 했지만, 옛 `chat.dto.ChatRequest`(`message` 필드)는 옛 컨트롤러가 계속 쓴다. 엔드포인트별 DTO(`SendMessageRequest{content, provider, command}`, `RegenerateRequest{provider, instruction, messageId}`, `ContinueRequest{provider}`, `SelectVariantRequest{index}`, `EditMessageRequest{content}`)를 `chat/api`에 새로 만들었다. `command`는 받기만 한다(T16).
- `GET /messages`의 `story.recordedThroughTurn`은 칼럼이 T14(V7)에서 생기므로 지금은 항상 0.
- `PromptAssembler.assembleSystemPrompt`는 호출만 한다(`scenarioDir`, `storyDir`). 본문과 시그니처는 건드리지 않았다.
- SSE 타임아웃 15분. 지나거나 클라이언트가 끊기면 전송만 멈추고 생성·저장은 끝까지 한다.

**확인 방법**
- `cd crack-backend && ./gradlew test`: 257개 전부 통과(이 브랜치 기준, 신규 52).
- 로컬 `application.yml`을 잠시 치운 상태로도 전체 통과 확인(리모트 조건).
- 최신 `origin/main`(T08, T10 머지 후) 위에 이 브랜치 diff를 얹은 스크래치 복사본(`git archive` + patch, application.yml 없음)에서 `./gradlew test` 344개 전부 통과. 겹치는 파일은 없다. rebase는 권한 정책에 막혀 하지 않았다.
- 실제 CLI/API 프로바이더와 브라우저로는 확인하지 않았다.

**겪은 문제**
- 로컬 `application.yml`에 `crack.auth.password`가 있어 MockMvc 요청이 401 → `@AutoConfigureMockMvc(addFilters = false)`.
- `application.yml`을 치우면 SSE 테스트가 10초 타임아웃과 `Could not open JPA EntityManager`로 줄줄이 실패. 원인: 테스트 프로필에는 `open-in-view: false`가 없어 OSIV가 켜지고, MockMvc는 async dispatch를 자동으로 하지 않아 SSE 요청마다 잡힌 커넥션이 반환되지 않았다(풀 소진). 테스트에서 `asyncDispatch(result)`로 요청 수명을 끝내 해결. 운영 예시 설정(`application.yml.example`)은 이미 `open-in-view: false`다. **이 값을 true로 두면 생성 중(수 분) 요청 하나가 커넥션을 계속 잡는다.**
- KDoc에 `` `/chat/*` ``를 쓰면 Kotlin 중첩 주석으로 해석되어 "Unclosed comment" 컴파일 오류.
- 태그 없는 응답의 첫 문단이 최대 200자까지 늦게 흘러 delta가 한 조각으로 몰림 → 태그 앞부분 패턴 검사로 즉시 흘리도록 수정.

**다음 작업자 주의점 (T11 프론트)**
- 경로: `/api/stories/{id}/messages` 아래. `GET` → `{story: {turnCount, recordedThroughTurn, generating}, messages: MessageView[]}`.
- SSE 요청은 POST라 `EventSource`를 못 쓴다. `fetch` + 스트림 파싱. 이벤트 순서:
  - 전송: `user`(저장된 유저 MessageView JSON) → `delta`* → `done`(저장된 ASSISTANT MessageView JSON) 또는 `error`(평문 메시지).
  - 재생성·이어쓰기: `user` 없이 `delta`* → `done` | `error`.
  - 재생성 `done`은 **기존 메시지와 같은 id**에 `variantIndex`/`variantCount`/`content`가 갱신된 값이다. 목록의 해당 메시지를 교체하면 된다. 이어쓰기 `done`은 `kind: "CONTINUATION"` 새 메시지.
- SSE 형식은 Spring 기본: `event:이름` / `data:값`(콜론 뒤 공백 없음). 여러 줄 delta는 줄마다 `data:`로 나뉘므로 `\n`으로 이어 붙여야 한다. `data:` 뒤 첫 공백을 지우는 파서를 쓰면 공백으로 시작하는 delta가 깨질 수 있다(스펙상 공백 하나만 지움, Spring은 공백을 넣지 않음). `\r\n`은 `\n`으로 바뀐다.
- 전송이 `error`로 끝나면 유저 메시지는 이미 저장되어 있다. 재시도는 `POST /messages/regenerate`(body 없이 또는 `{}`)로 같은 턴 첫 응답을 만든다.
- 재생성 버튼은 `messageId`를 함께 보내길 권장(서버가 최신 메시지인지 확인). 후보 선택은 `PUT /messages/{id}/variant {index}`로 최신 ASSISTANT만.
- 생성 중(`story.generating == true`)에는 전송/재생성/이어쓰기/수정/삭제/후보 선택이 409(JSON `{message, status}`). SSE 경로의 400/404/409도 JSON 본문이다.
- `DELETE /messages/{id}` 응답: `{storyId, minTruncatedTurn, deletedCount, turnCount}`. `GET /messages/export`는 `text/markdown`.
- `MessageView`에 `emotion`은 없다. delta와 `content`에도 태그가 없지만, 방어용 첫 줄 숨김(§5.3)은 계획대로 둘 것.

**다음 작업자 주의점 (그 외)**
- T14: `AfterTurnHook` 빈을 등록하면 된다. `mode == REGENERATE`는 같은 턴 후보 교체이므로 10턴 트리거에서 거를지 판단할 것. `turnCount`는 저장 직후 값. `StoryChatInfo.recordedThroughTurn`(`ChatFlowService.state`)을 V7 칼럼으로 연결해야 한다.
- T13: 원문 범위 제한은 `ConversationBuilder.build`에서 하면 된다(지금은 전체). 지시 블록 위치는 T13의 BOTTOM 슬롯으로 옮길 때 `turnInstruction`을 `PromptContext.turnInstruction`으로 넘기면 된다.
- T16: `SendMessageRequest.command`가 `ChatFlowService.send(command)`까지 전달되지만 무시된다.
- 스토리 생성 락은 프로세스 메모리 락이다(서버 여러 대면 안 맞음 — 개인용 단일 서버 전제).
- 옛 파일 기반 대화(`chat_latest.md`)는 새 테이블로 옮기지 않았다. 옛 스토리를 새 API로 열면 대화가 비어 보인다.
- 통합 테스트는 `@AutoConfigureMockMvc(addFilters = false)` + `@Import(ChatTestConfig::class)`로 별도 컨텍스트를 쓴다. SSE 테스트는 반드시 `asyncDispatch`까지 마칠 것(위 OSIV 문제).

