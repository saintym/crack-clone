# T11 프론트 채팅을 새 API로 연동

- **상태**: REVIEW
- **웨이브**: 3
- **의존**: T04, T07, T10
- **브랜치**: `task/T11-frontend-chat`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §5.2, §10

## 목표
프론트를 `/messages*` API로 옮긴다. 이 작업으로 다음 기능이 화면에 드러난다.
- 재생성 후보 넘기기(‹ 2/3 ›)
- 유저·AI 메시지 수정
- 프롤로그 표시
- 빈 입력창에서 Enter로 이어쓰기

## 범위
- `crack-frontend/src/api/chat.ts`(교체), `src/hooks/**`, `src/components/chat/**`, `src/types/**`, `src/pages/ChatPage.tsx`
- **수정 금지:** 백엔드

## 구현 내용
1. `GET /messages`로 구조화된 목록을 받는다. 정규식으로 마크다운을 파싱하던 코드를 제거한다.
2. SSE 이벤트 `user`/`delta`/`done`/`error`를 처리한다. `done`의 MessageView로 로컬 상태를 교체한다. `/complete` 호출을 제거한다.
3. **ChatBubble**
   - 가장 최근 AI 메시지에 후보가 2개 이상이면 `‹ n/m ›`를 표시하고, 선택하면 `PUT /variant`
   - 수정은 모든 메시지(유저 포함)에서 가능하며 인라인 편집기를 쓴다
   - 수정된 메시지는 작은 "수정됨" 표시
4. **재생성**
   - 가장 최근 AI 메시지에서만 가능
   - 재생성 지시 입력(선택)은 메뉴의 "지시하고 재생성"으로 넣는다
   - 실패하면 원래 답변을 그대로 둔다
5. **감정 태그:** 서버가 이미 제거해서 보낸다. 방어용으로 첫 줄이 `[감정: …]`이면 숨긴다. 기존 ChatBubble의 태그 제거 로직을 이 규칙으로 정리한다
6. **프롤로그:** 첫 메시지로 표시한다. 메뉴에는 수정만 두고 재생성 버튼은 숨긴다(백엔드는 400, T07).
7. **이어쓰기**
   - 입력창이 비어 있을 때 Enter 또는 버튼으로 실행
   - 기존 "계속 이어서" 문구 전송 방식은 제거
8. **삭제와 분기:** 인덱스 대신 `messageId`를 쓴다. 분기 요청은 `{messageId, title}`(T09). 삭제 확인창에 "이 메시지부터 끝까지 삭제됩니다"를 명시한다.
9. **409 응답**(생성 중)은 입력을 잠그는 것으로 처리한다.

## 완료 조건
- [x] `npm run build`, `npm run lint` 통과
- [x] 백엔드(Fake 프로바이더)와 연동해 수동 확인 (브라우저 없이 API·SSE 층까지. 작업 로그 참고)
  - 전송 → 새로고침 후 유지
  - 재생성 3회 → 후보 넘기기
  - 유저 메시지 수정 후 다음 응답에 반영
  - 프롤로그
  - 빈 Enter로 이어쓰기

  확인한 내용을 작업 로그에 적는다
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T11-frontend-chat`(worktree).

**한 일**
- `src/types/chat.ts`: `Message`(= `MessageView`), `MessageRole`(`USER`/`ASSISTANT`), `MessageKind`, `StoryChatInfo`, `MessagesResponse`, `TruncateResult`.
- `src/api/chat.ts`(교체): `/messages*` 클라이언트(`list`, `selectVariant`, `edit`, `truncateFrom`, `send`, `regenerate`, `continueStory`), fetch 기반 SSE 파서 `createSseParser`, 오류 도우미(`ChatApiError`, `isConflict`, `isAbort`, `errorMessage`). 옛 `/chat/*` 호출(`history`, `complete`, 인덱스 수정·삭제)은 모두 없앴다.
- `src/hooks/useMessages.ts`: `GET /messages`로 로드(마크다운 정규식 파싱 제거), 서버가 돌려준 메시지로 교체(`applyMessage`), 후보 선택·수정·삭제(`messageId`), 생성 중 잠금(`busy`)과 폴링.
- `src/hooks/useChatStream.ts`: 전송·재생성·이어쓰기 SSE. `user` → 목록 반영, `delta` → 스트리밍 말풍선, `done` → 목록 교체, `error` → 입력창 위 안내. 스토리를 옮기거나 화면을 떠나면 읽기만 중단(AbortController).
- `components/chat`
  - `ChatBubble`: 역할 값 변경, 첫 줄 감정 태그 숨김(`emotionTag.ts`)으로 정리, 첫 delta 전 입력 중 표시.
  - `MessageMenu`: `‹ n/m ›`, 재생성, 이어쓰기, 응답 받기(마지막이 유저 메시지일 때), 더보기(지시하고 재생성·분기·수정·삭제). 삭제는 메뉴 안에서 "이 메시지부터 끝까지 삭제됩니다"를 한 번 더 확인.
  - `MessageList`: 유저·AI 모두 인라인 편집기, "수정됨" 표시, 재생성 지시 입력, 재생성 중에는 원래 답변 자리에 스트림 표시.
  - `ChatInput`: 빈 입력창에서 Enter·버튼 = 이어쓰기(버튼 아이콘이 바뀜), 잠금, 안내·오류 문구, 저장되지 않은 전송은 입력 내용 복구.
  - `BranchDialog`: 실패 문구 표시.
- `src/pages/ChatPage.tsx`: 새 훅 조립, 분기 `{messageId, title}`.

**판단**
- **SSE 파서:** 이벤트 단위(빈 줄)로 `data:` 줄을 `\n`으로 잇는다. `data:` 뒤 공백을 **지우지 않는다**(Spring이 공백을 넣지 않고, 실제로 `data: 잠시 머뭇거리다`처럼 공백으로 시작하는 delta가 온다). 줄 끝 `\r`만 떼고, `:` 주석 줄은 무시. done/error 없이 연결이 끝나면 `interrupted`로 보고 목록을 다시 받는다(서버는 저장을 계속하므로).
- **재생성 위치:** 스트리밍하는 동안 대상 AI 메시지 자리에 새 응답을 보이고, 실패하면 스트림만 사라져 원래 답변이 그대로 보인다(로컬 상태는 `done` 전까지 건드리지 않는다). 재생성은 항상 `messageId`를 보낸다(T07 권장).
- **후보 넘기기:** "가장 최근 AI 메시지"는 역할 기준 마지막 ASSISTANT다(백엔드 `requireLatestAssistant`와 같다). 재생성·지시하고 재생성은 그 메시지가 대화의 마지막일 때만(프롤로그 제외). 끝에서는 화살표를 비활성화(순환하지 않음).
- **전송 실패 재시도:** 대화의 마지막이 응답 없는 유저 메시지면 그 메시지 아래에 "응답 받기"를 두고 `regenerate {messageId: 유저 메시지}`로 첫 응답을 만든다. 이때 이어쓰기는 막는다(백엔드 400 조건과 같음).
- **409 처리:** `story.generating`이거나 어떤 요청이든 409를 받으면 `busy`로 입력과 메시지 조작을 잠그고 2.5초마다 `GET /messages`를 다시 받는다. `generating == false`가 되면 새 목록(다른 탭에서 만든 응답 포함)으로 바꾸고 풀린다. 연결이 끊긴 경우(`interrupted`)도 목록을 다시 받아 같은 흐름을 탄다.
- **삭제 확인:** 흐름을 끊는 모달 대신 더보기 메뉴 안에서 확인한다(D7). 문구는 "이 메시지부터 끝까지 삭제됩니다. 되돌릴 수 없습니다."
- **스토리 전환:** 목록 상태에 `storyId`를 함께 두고 현재 스토리와 다르면 버린 것으로 본다. 늦게 온 응답이 다른 스토리에 섞이지 않고, effect 안에서 동기 setState를 하지 않는다(Hooks lint v7).
- **한글 IME:** 조합 중 Enter(`isComposing`/keyCode 229)는 무시한다. 조합 확정 Enter가 빈 입력 이어쓰기로 새는 것을 막기 위해서다.
- **스크롤:** 메시지 수나 스트림이 바뀔 때만 아래로 내린다(과거 메시지 수정·후보 넘기기에는 움직이지 않음).
- **모르는 필드:** 응답을 그대로 담고 타입에 있는 필드만 쓴다. T14가 `story.memory`를 추가해도 영향 없다.

**확인**
- `npm run build`, `npm run lint` 오류 0.
- 파서·감정 태그 도우미를 Node(`--experimental-strip-types`)로 확인: 3바이트씩 쪼갠 스트림에서 `user`/여러 줄 `delta`(빈 줄 포함, 앞 공백 보존)/`\r\n`/주석/`done` 파싱, 태그 줄 제거·공백 변형·첫 줄 아닌 태그 유지·같은 줄 본문 유지·스트리밍 중 태그 앞부분 숨김·다른 대괄호 유지.
- **백엔드 연동(Fake 프로바이더):** 스크래치에 `initdb` 임시 클러스터(127.0.0.1:55433, `unix_socket_directories=''`, DB `crack_t11`), 데이터는 `fixtures/sample-scenario` 복사본, 백엔드 `bootJar`를 `--crack.ai.fake.enabled=true --crack.ai.default-provider=fake --crack.auth.password= --server.port=18211`로, 프론트는 `CRACK_WEB_PORT=15211 CRACK_API_TARGET=http://localhost:18211` Vite dev 서버. 브라우저가 없어 **실제 `src/api/chat.ts`(파서 포함)를 Node에서 Vite 프록시 경유로 호출**하는 드라이버로 확인했다(`client.ts`만 baseURL 스텁).
  - 프롤로그: 스토리 생성 직후 `GET /messages`에 `PROLOGUE` 턴 0 한 개. 프롤로그 재생성은 400 "프롤로그는 재생성할 수 없습니다".
  - 전송: `user`(seq1 턴1) → delta 3개 → `done`(seq2), delta를 이은 값 == `done.content`. 다시 `GET`(새로고침 상당)해도 그대로.
  - 재생성 3회(2회째 지시 포함): 같은 id에 `variantIndex/variantCount` 1/2 → 2/3 → 3/4. 후보 3번을 수정해 구분한 뒤 0 → 3 → 2 선택 시 내용이 바뀜. 과거 메시지(`messageId`=유저 메시지, 마지막 아님) 재생성은 400.
  - 유저 메시지 수정: `edited: true`, 내용 반영. (Fake는 항상 같은 문장을 돌려주므로 "다음 응답에 반영"은 응답 내용으로는 확인할 수 없다. 대화 입력을 DB에서 만드는 것은 T07 `ConversationBuilder` 테스트 범위.)
  - 이어쓰기: `CONTINUATION` 새 턴(턴 2). 마지막이 유저 메시지면 400. 그 상태에서 `regenerate {messageId: 유저}`로 첫 응답 생성.
  - 생성 중 409: 전송 직후 수정·이어쓰기 → 둘 다 409, `isConflict` true, JSON 문구 추출 확인.
  - 삭제: `{storyId, minTruncatedTurn, deletedCount, turnCount}`, 이후 목록과 turnCount 일치.
  - 원시 SSE(curl, 프록시 경유): `content-type: text/event-stream`, `event:delta` / `data:…`(콜론 뒤 공백 없음), 이벤트 사이 빈 줄.
- **확인하지 못한 것:** 브라우저 렌더링과 상호작용(버튼 배치, 인라인 편집, 스크롤, 폴링으로 잠금이 풀리는 모습, IME). 리뷰 때 브라우저로 한 번 확인이 필요하다. 감정 태그·여러 줄 delta는 Fake 응답에 없어 실제 스트림으로는 못 봤다(파서 단위 확인만).
- 끝난 뒤 백엔드·Vite 중지, 임시 클러스터 중지·삭제, 스크래치 데이터 삭제.

**범위 밖 수정**
- `src/api/stories.ts`의 `branch`: `{messageIndex}` → `{messageId}`. T09 작업 로그가 T11에 요청한 변경이고, 분기 요청을 `messageId`로 바꾸려면 필요하다(함수 하나).

**겪은 문제**
- 커밋마다 빌드되게 하려고 첫 커밋은 분기를 과도기 `messageIndex = seq`로 부르고, 다음 커밋에서 `messageId`로 바꿨다.
- 수동 확인 준비 중 `POST /api/scenarios`가 이미 있는 시나리오 폴더의 `world.md`, `scenario.md`, `characters/protagonist.md`를 템플릿으로 **덮어쓴다**는 것을 코드에서 확인했다(`ScenarioService.copyTemplate`의 `REPLACE_EXISTING`, 템플릿이 없으면 `# world.md` 한 줄). 스크래치 복사본이라 피해는 없었다. 기존 폴더를 등록하는 흐름이 있다면 원본 문서가 날아간다(백엔드 범위라 고치지 않음).

**다음 작업자 주의점**
- **"수정됨"은 메시지 단위다.** 백엔드 `edited`가 `edited_at != null`이라, 후보 하나를 수정하면 다른 후보로 넘겨도 "수정됨"이 계속 보인다. 후보별로 보이려면 백엔드가 후보별 수정 여부를 줘야 한다.
- T12: 프론트는 더 이상 `/chat/*`를 부르지 않는다(`grep -rn "/chat" src/api`로 확인). `StoryBranchRequest.messageIndex`도 프론트가 쓰지 않는다.
- T15/T18/T19: 패널은 `ChatPage`의 `panelTabs`에 추가. 기억 상태가 필요하면 `useMessages`가 받는 `GET /messages`의 `story`를 넓히면 된다(지금은 `generating`만 쓴다).
- T16: `/` 명령은 `chatApi.send`의 body에 `command`를 더하면 된다(`SendBody`).
- T20: 감정 태그 숨김은 `components/chat/emotionTag.ts` 한 곳이다.
