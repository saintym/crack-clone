# T15 기억 패널

- **상태**: REVIEW
- **웨이브**: 4
- **의존**: T11, T14
- **브랜치**: `task/T15-frontend-memory-panel`
- **마이그레이션**: 없음
- **결정**: D7 (몰입 우선: 모달·승인 요청 금지)

## 목표
오른쪽 드로어(T04의 SidePanel)에 "기억" 탭을 추가한다. 스토리 문서를 보고 고치고, 기록 이력을 보고, 되돌릴 수 있게 한다. 채팅 화면에는 **작은 뱃지만** 띄운다.

## 범위
- 신규 `crack-frontend/src/components/panels/memory/**`, `src/api/memory.ts`, `src/api/storyDocuments.ts`
- `src/components/chat/ChatHeader.tsx`(뱃지와 패널 열기 버튼), `src/pages/ChatPage.tsx`(탭 등록만)

## 구현 내용
1. **문서 탭:** 연대기 · 주인공 · 인물(목록 → 문서) · 유저노트. 보기는 마크다운 렌더링이고, 편집은 텍스트 영역을 쓴다(T08 스토리 문서 API).
2. **기록 이력:** 회차 목록(범위, 상태, 시각). 항목을 열면 변경 파일별 diff(before → 현재)를 보여준다. diff 라이브러리는 `diff` npm 패키지 정도의 가벼운 것을 쓴다.
3. **되돌리기:** 가장 최근 DONE에만 버튼을 둔다. 확인은 패널 안의 인라인 확인으로 처리한다(모달 금지).
4. **수동 기록:** 패널의 "지금 기록" 버튼(`POST /memory/record`)
5. **뱃지:** `GET /messages`의 `story.memory` 상태로 표시한다. RUNNING은 작은 스피너, 새 기록은 점 하나. 패널을 열면 읽음 처리한다(`unseen`은 로컬에 저장해도 된다).
6. **RUNNING 중:** 5초 간격으로 폴링한다.

## 완료 조건
- [ ] `npm run build`, `npm run lint` 통과
- [ ] Fake 백엔드로 10턴 진행 → 뱃지 → 패널에서 변경 확인 → 되돌리기. 확인 내용을 작업 로그에 적는다
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23 시작
- `task/T15-frontend-memory-panel` 브랜치(worktree)에서 시작. 의존 작업 T11·T14 머지 확인.
- 병렬 작업 주의: T16(백엔드 지시·명령), T20(ChatBubble·ScenarioDetailPage)이 동시에 진행 중이다. ChatBubble·ScenarioDetailPage는 건드리지 않고, ChatPage는 탭 등록, ChatHeader는 뱃지·패널 버튼만 고친다.

### 2026-09-23 구현 완료 (REVIEW)

**한 일**
- `src/api/memory.ts`(기록 API 5개와 타입), `src/api/storyDocuments.ts`(스토리 문서 목록·GET·PUT).
- `src/components/panels/memory/**`
  - `useMemoryStatus`: 헤더 뱃지와 패널이 함께 쓰는 기억 상태(뱃지 상태, 기록 목록, `refresh`, `markSeen`).
  - `MemoryBadge`: RUNNING이면 작은 스피너, 읽지 않은 기록이 있으면 점 하나(실패면 붉은 점). 그 밖에는 아무것도 그리지 않는다.
  - `MemoryPanel`: "문서" / "기록 이력" 전환. 새 기록이 있으면 기록 이력부터 연다.
  - `DocumentsView` + `DocumentEditor`: 연대기 · 주인공 · 유저노트 · 인물 목록 → 문서. 보기는 마크다운(`chat-markdown` 스타일 재사용), 편집은 텍스트 영역. 저장하지 않고 나가면 인라인 확인. 없는 문서(404)는 편집해서 만들 수 있다.
  - `RecordsView` + `RecordDetail` + `DiffView`: "지금 기록"(`POST /memory/record`, 결과 문구 표시), 회차 목록(상태·범위·자동/수동·시각·바뀐 문서), 상세는 파일별 줄 diff(기록 직전 → 현재). 같은 줄이 긴 구간은 접고 눌러서 펼친다.
  - `RevertControl`: `revertable`인 기록에만 버튼. 확인은 그 자리에 펼치는 인라인 확인(모달 없음). 400/409는 서버 문구를 그 자리에 보인다.
  - `memoryPanelTab(storyId, memory)`: `SidePanelTab`을 만들어 준다. 패널 코드는 `MemoryPanelLoader`에서 지연 로딩한다.
- `ChatPage.tsx`: `useMemoryStatus` 호출, `panelTabs = [memoryPanelTab(...)]`, 헤더에 뱃지 전달.
- `ChatHeader.tsx`: `panelBadge?: ReactNode` prop 하나. 패널 버튼 오른쪽 위에 겹쳐 그린다.
- 의존성: `diff@^9.0.0`(의존성 없음, 타입 내장).

**범위 밖 최소 수정**
- `src/types/chat.ts`(`StoryChatInfo.memory` 추가), `src/hooks/useMessages.ts`(받은 `story.memory`를 상태에 두고 `memory`로 반환, 7줄). 뱃지 기준값을 얻으려고 `GET /messages`를 한 번 더 부르지 않기 위해서다. T11 작업 로그가 권한 방식이다. 기존 동작은 바뀌지 않는다.

**설계 판단**
- **뱃지 상태의 출처:** 기준값은 작업 파일대로 `GET /messages`의 `story.memory`다. 다만 `GET /messages`는 메시지 전체를 돌려주므로(페이지네이션 없음) 턴마다·5초마다 다시 부르면 긴 스토리에서 무겁다. 그래서 갱신은 가벼운 `GET /memory/records`로 한다.
  - 턴이 끝났을 때(생성 중이 아니면서 마지막 메시지 id나 개수가 바뀜: 전송·이어쓰기·삭제·다른 탭 생성 완료) 목록을 한 번 받는다. 백엔드는 10턴 자동 기록의 RUNNING 행을 `done` 이벤트 전에 만들므로 바로 보인다(코드 확인: `GenerationStreamListener`의 `afterSave` → `send(DONE)`).
  - RUNNING이면 5초마다 목록을 다시 받는다.
  - `unseen`은 목록에 없으므로 "알던 것보다 새로 끝난 기록(다른 id이거나 RUNNING이던 기록)이 DONE/FAILED면 읽지 않음"으로 계산한다. 서버의 `unseen` 정의와 같은 결과가 되고, 서버 값이 새로 오면(`GET /messages`) 서버 값을 믿는다.
- **읽음 처리:** 기억 탭이 보이는 동안 `unseen`이면 `POST /memory/records/seen`. 단, **RUNNING 중에는 부르지 않는다.** 서버의 `markSeen`은 상태와 무관하게 전부 `seen = true`로 바꾸므로, 실행 중 기록까지 읽음이 되어 끝났을 때 새로 고치면 뱃지가 안 뜬다. 기록이 끝난 뒤(패널이 열려 있으면 그 즉시) 부른다.
- **편집 중 기록 반영:** 편집을 시작할 때의 내용을 기억해 두고, 편집 중에 기록·되돌리기로 서버 문서가 바뀌면 "저장하면 그 변경을 덮어씁니다" 안내와 "바뀐 내용으로 다시 편집" 버튼을 보인다(자동으로 덮지 않음). 보기 모드는 기록 상태가 바뀌면 다시 받는다.
- **diff:** "기록 직전 → 현재"(API가 주는 그대로). 가장 최근이 아닌 기록은 이후 기록·직접 수정도 섞여 보인다는 문구를 붙였다. 되돌린 기록은 보통 "변경 없음"으로 보인다.
- **재반영만 한 기록**(`toTurn < fromTurn`)은 "고친 턴 재반영 (턴 3, 5)"로, 재반영이 함께 있으면 "턴 11–20 · 재반영 턴 3"으로 표시한다.
- **탭 구조:** T18/T19도 `xxxPanelTab(...)` 함수가 `SidePanelTab`을 돌려주고 ChatPage가 `panelTabs` 배열에 넣는 방식으로 붙이면 된다. 헤더 뱃지는 `panelBadge`(ReactNode) 하나라서, 뱃지가 더 필요하면 여러 개를 감싼 노드를 넘기면 된다.
- **지연 로딩:** 패널을 넣자 메인 청크가 500kB를 넘어 Vite 경고가 났다. `React.lazy`로 패널을 분리해 경고를 없앴다(메인 475 → 276kB, react-markdown 등은 공유 청크로 분리됨).
- 스토리를 옮기면 `key={storyId}`로 패널 안 상태(열린 문서, 편집 내용)를 버린다.

**확인**
- `npm run build`, `npm run lint` 오류 0.
- **브라우저 확인(Fake 백엔드):** 스크래치에 `initdb` 임시 클러스터(127.0.0.1:55435, `unix_socket_directories=''`, DB `crack_t15`), `sample-scenario` 복사 데이터, bootJar를 `--crack.ai.fake.enabled=true --crack.ai.default-provider=fake --crack.auth.password= --server.port=18215`(데이터소스·data-path도 인자로 덮음), Vite `CRACK_WEB_PORT=15215 CRACK_API_TARGET=http://localhost:18215`. Playwright(로컬 캐시의 Chromium, headless)로 실제 화면을 조작했다.
  - 10턴을 입력창으로 전송 → 1~9턴에는 뱃지 없음, 10턴 직후 스피너(한 번 포착) → 점. `GET /memory/records`는 턴마다 1회.
  - 패널 열기 → 기록 이력이 먼저 열림, `턴 1–10 반영됨`. 뱃지 사라짐, 서버 `unseen=false`.
  - 상세: 연대기·상태·설월·주인공 4개 파일 diff(추가 줄, state.json 바뀐 줄, 긴 같은 구간 접힘).
  - 되돌리기 → 인라인 확인 → 확인 → `REVERTED`, `revertable=false`, 버튼 사라짐.
  - 문서 → 설월 → 마크다운 보기 → 편집·저장 → 스토리 문서에 반영, 시나리오 원본은 불변.
  - 지금 기록 → "기록을 시작했습니다" → `MANUAL DONE 1–10`, 되돌리기 버튼은 새 기록에만.
  - 폴링: `GET /memory/records` 응답을 가로채 RUNNING을 12초 유지 → 0.6s, 6.0s, 11.3s, 16.6s에 요청(5초 간격), 스피너 → 끝나자 점. 패널을 연 채 RUNNING이면 `seen`을 부르지 않고, 끝난 즉시 `POST seen` 후 뱃지 사라짐.
  - 연대기를 편집하는 중에 채팅으로 턴을 보내 자동 기록이 연대기를 바꾸게 함 → 안내 표시, 편집 내용 유지. 변경이 있는 채로 뒤로 → 인라인 확인 → 버리기.
  - 모바일 너비(390px) 하단 시트 표시 확인.
  - 끝난 뒤 백엔드·Vite 중지, 클러스터 중지, 스크래치 삭제.

**발견한 문제 (범위 밖, 고치지 않음)**
- **[백엔드, 중요] PostgreSQL에서 두 번째 기록부터 트리거가 실패한다.** `StoryMessageRepository.findEditedTurns`의 `(:since IS NULL OR m.editedAt > :since)`가 `since`가 null이 아닐 때(= 이전 DONE 기록이 있을 때) PG에서 `could not determine data type of parameter $4`(SQLState 42P18)로 실패한다. H2 테스트에서는 드러나지 않는다. 결과적으로 첫 DONE 이후에는 자동 기록이 조용히 안 돌고(AfterTurnHook 오류 로그만), `POST /memory/record`는 500이다(NOTHING_TO_RECORD여야 할 때도). 되돌려서 DONE이 없어지면 다시 된다. 쿼리를 `since` 유무로 둘로 나누거나 `CAST(:since AS timestamp)` 등으로 타입을 정해야 한다.
- **백엔드 `markSeen`이 RUNNING 기록도 읽음으로 바꾼다.** 프론트는 RUNNING 중에 부르지 않는 것으로 피했다. 서버에서 `status IN (DONE, FAILED)`만 바꾸는 편이 안전하다.
- **[프론트 전역] `index.css`의 레이어 밖 `* { margin: 0; padding: 0; }`가 Tailwind v4 유틸리티(`@layer utilities`)보다 우선해서, 앱 전체에서 `p-*`, `m-*`, `px-*` 등 여백 클래스가 먹지 않는다**(스크린샷에서 말풍선·버튼·패널 모두 여백 0). 기존 문제이고 모든 화면에 영향이 있어 고치지 않았다. `@layer base { … }`로 감싸면 된다.

**다음 작업자 주의점**
- T18/T19: `components/panels/memory/memoryPanelTab.tsx`처럼 탭 함수를 만들어 `ChatPage`의 `panelTabs`에 넣는다. SidePanel은 닫혀도 마지막으로 고른 탭을 기억한다.
- T16: `/기록` 명령이 채팅 전송으로 처리되면 턴 종료 시 목록을 다시 받으므로 뱃지가 따라온다. 전송 경로가 아닌 곳에서 기록을 시작하면 `memory.refresh()`를 부르면 된다.
- 개발 모드(StrictMode)에서는 패널을 열 때 요청이 두 번씩 나간다(정상).
