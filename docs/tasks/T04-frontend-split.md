# T04 ChatPage 컴포넌트/훅 분리

- **상태**: IN_PROGRESS
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
- [ ] `npm run build` 통과. `npm run lint`에서 ChatPage 관련 오류 0 (기존 `ChatPage.tsx:74` immutability 오류 포함 해결. 다른 페이지 오류는 T00 담당)
- [ ] `ChatPage.tsx` 200줄 이하
- [ ] 동작 동일성: 전송, 스트리밍, 재생성, 이어하기, AI 메시지 수정·삭제·분기, 프로바이더 선택, 상황서술 토글. 백엔드 없이 확인하기 어려우면 확인한 범위를 작업 로그에 적는다
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 기준선: `npm run build` 통과, `npm run lint` 오류 6건(ChatPage.tsx:74 1건, 나머지 5건은 다른 페이지로 T00 담당).
