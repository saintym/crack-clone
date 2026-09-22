# T04 ChatPage 컴포넌트/훅 분리

- **상태**: DONE
- **웨이브**: 1
- **의존**: 없음
- **브랜치**: `task/T04-frontend-split`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §10

## 목표
715줄짜리 `ChatPage.tsx`를 DESIGN.md §10 구조로 나눈다. **동작은 바꾸지 않는다.** 이후 T11, T15, T18, T19, T20이 서로 다른 파일을 고치며 병렬로 작업할 수 있게 하려는 것이다.

## 범위
- `crack-frontend/src/pages/ChatPage.tsx`
- 신규 `crack-frontend/src/components/chat/**`, `crack-frontend/src/hooks/**`, `crack-frontend/src/types/**`
- `crack-frontend/vite.config.ts` (개발 서버 포트와 프록시 대상만)
- **수정 금지:** `src/api/**`(T11 담당), 다른 페이지

## 구현 내용
1. 표현 컴포넌트를 분리한다: ChatHeader(프로바이더 선택 포함), StorySidebar, MessageList, ChatBubble, MessageMenu(수정·삭제·분기·재생성), ChatInput(상황서술 토글 포함)
2. 로직을 훅으로 분리한다: `useMessages`(히스토리 로드, 파싱, 상태), `useChatStream`(fetch SSE 전송·재생성·이어하기, delta 누적, complete 호출), `useProviders`
3. 공용 타입은 `src/types/chat.ts`로 모은다.
4. 모달 대신 쓸 **빈 오른쪽 드로어 컨테이너**(`components/panels/SidePanel.tsx`, 탭 목록을 props로 받음)를 추가한다. 아직 탭은 비워 두며, T15/T18/T19가 탭을 추가한다.
5. **병렬 개발용 설정:** `vite.config.ts`의 개발 서버 포트와 `/api` 프록시 대상을 환경변수로 바꿀 수 있게 한다. 기본값은 지금과 같다(`CRACK_WEB_PORT` 기본 5173, `CRACK_API_TARGET` 기본 `http://localhost:8082`). 여러 worktree에서 앱을 동시에 띄우기 위해서다(docs/PARALLEL.md)

## 완료 조건
- [x] `npm run build` 통과. `npm run lint`에서 ChatPage 관련 오류 0 (기존 `ChatPage.tsx:74` immutability 오류 포함 해결. 다른 페이지 오류는 T00 담당)
- [x] `ChatPage.tsx` 200줄 이하
- [ ] 동작 동일성: 전송, 스트리밍, 재생성, 이어하기, AI 메시지 수정·삭제·분기, 프로바이더 선택, 상황서술 토글. 백엔드 없이 확인하기 어려우면 확인한 범위를 작업 로그에 적는다
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 기준선: `npm run build` 통과, `npm run lint` 오류 6건(ChatPage.tsx:74 1건, 나머지 5건은 다른 페이지로 T00 담당).
- 한 일
  - `types/chat.ts`(Message, MessageRole), `types/panel.ts`(SidePanelTab) 추가
  - 훅 분리: `useMessages`(히스토리 로드·`parseHistory`·수정·삭제), `useChatStream`(fetch SSE 파싱, 전송·재생성·이어하기, delta 누적, complete 호출), `useProviders`, `useStoryContext`(현재 스토리·시나리오·사이드바 목록·`refreshStories`)
  - 컴포넌트 분리: `components/chat/` ChatHeader(프로바이더 선택), StorySidebar, MessageList(인라인 편집 포함), ChatBubble, MessageMenu(재생성·이어하기·분기·수정·삭제), ChatInput(상황서술 토글), BranchDialog(분기 하단 시트)
  - `components/panels/SidePanel.tsx`: 탭을 props로 받는 빈 오른쪽 드로어(모바일은 하단 시트, 배경을 덮지 않음)
  - `ChatPage.tsx` 715줄 → 100줄. 조립만 한다
  - `vite.config.ts`: `CRACK_WEB_PORT`(기본 5173), `CRACK_API_TARGET`(기본 `http://localhost:8082`)
- 설계 판단
  - SSE 파싱 루프, 요청 본문, complete 호출 순서, 오류 로그 문구는 원본 코드를 그대로 옮겼다. 전송·재생성·이어하기에 공통인 "스트림 → streamContent 비우기 → 메시지 추가 → complete → refreshStories / 실패 시 로그·비우기 / finally streaming=false" 흐름만 `runStream`으로 묶었다
  - `parseHistory`를 모듈 함수로 옮겨 기존 `ChatPage.tsx:74` react-hooks/immutability 오류를 해결했다
  - DESIGN §10에 없는 파일을 더했다: `useStoryContext.ts`(스토리 컨텍스트 로드가 ChatPage에 남으면 200줄 목표와 "조립만" 원칙을 지키기 어려움), `useProviders.ts`(작업 파일 지시), `BranchDialog.tsx`(분기 시트 분리), `types/panel.ts`(react-refresh 규칙상 컴포넌트 파일에서 타입 외 export를 피하려고 별도 파일). MessageMenu에는 작업 파일대로 재생성·이어하기도 넣었다. 계약(API·스키마) 변경은 없어 DESIGN.md는 고치지 않았다
  - 동작 보존을 위한 선택
    - `useProviders`는 원본처럼 스토리가 바뀔 때마다 목록을 다시 받고 선택값을 서버 기본값으로 되돌린다(원본이 같은 effect에서 로드했기 때문)
    - 메뉴 열림·편집 상태는 MessageList, 입력값·상황서술 모드는 ChatInput, 프로바이더 메뉴 열림은 ChatHeader가 가진다. 같은 라우트에서 스토리를 옮겨도 컴포넌트가 다시 마운트되지 않으므로 원본처럼 상태가 유지된다
    - 전송 가드(`!input.trim() || streaming || !storyId`)가 통과할 때만 입력을 비우도록 ChatInput에 `sendBlocked`를 넘긴다. 수정 저장도 storyId가 없으면 편집 상태를 닫지 않던 원본 동작을 `editMessage`의 boolean 반환으로 유지했다
    - 분기 제목 기본값은 원본처럼 메뉴를 누른 시점의 스토리 제목으로 정한다
  - 알려진 미세 차이(UI로 도달 불가): 재생성·삭제에서 메뉴 닫기가 storyId 가드보다 먼저 실행된다. storyId가 무효(NaN)면 메시지가 로드되지 않아 메뉴 자체가 없다
  - SidePanel은 탭이 없으면 헤더의 여는 버튼을 숨기므로 현재 화면 변화는 없다
- 확인한 방법
  - `npm run build` 통과. `npm run lint`: ChatPage와 신규 파일 오류 0. 남은 것은 다른 페이지의 기존 오류 5건과 경고 2건(ScenarioDetailPage 3+경고1, ScenariosPage 1, StoriesPage 1+경고1, T00 담당)
  - 원본과 분리 후 코드를 기능별로 대조: 전송, 스트리밍(delta 누적·done 폴백), 재생성(UI에서 제거 후 재요청, 실패 시 히스토리 재로드), 이어하기(숨은 "계속" 사용자 메시지 추가), 수정·삭제(잘라내기)·분기(이동), 프로바이더 선택, 상황서술 토글(`**…**` 감싸기 후 해제)
  - `CRACK_WEB_PORT=15999 CRACK_API_TARGET=http://localhost:18999 npx vite --strictPort`로 15999 포트에서 뜨고 `/api` 요청이 18999로 프록시되는 것(연결 거부 502) 확인
  - 백엔드와 브라우저로 실제 채팅 흐름을 수동 확인하지는 않았다
- 다음 작업자가 알아야 할 것
  - 이 worktree에서는 `npm ci`가 peer 의존성 충돌(vite-plugin-pwa 1.2.0의 peer가 vite ^7까지, 프로젝트는 vite 8)로 실패한다. `npm ci --legacy-peer-deps`로 설치했다. lockfile은 바꾸지 않았다
  - 패널 탭 추가(T15/T18/T19): `ChatPage.tsx`의 `panelTabs`에 `{ id, label, content }`를 넣으면 헤더에 여는 버튼이 생긴다
  - T18 `/` 자동완성은 `ChatInput.tsx`, T20 이미지 태그는 `ChatBubble.tsx`, T11 API 교체는 `useChatStream.ts`·`useMessages.ts`만 고치면 된다
