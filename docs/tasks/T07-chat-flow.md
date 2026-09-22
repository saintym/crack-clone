# T07 채팅 흐름 재작성

- **상태**: TODO
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
- [ ] `./gradlew test` 통과. Fake 프로바이더로 통합 테스트
  - 전송 → 저장 → done
  - 재생성 후보 누적과 선택
  - 스트림 실패 시 원본 보존
  - 수정 후 다음 요청 컨텍스트에 수정 내용 반영
  - 이어쓰기 지시문 미저장
  - 동시 생성 409
  - 감정 태그가 delta와 저장 본문 어디에도 없고 `emotion` 칼럼에만 저장됨(태그가 여러 조각으로 나뉘어 들어오는 경우 포함)
  - 과거 메시지 재생성 400
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그
