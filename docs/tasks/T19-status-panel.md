# T19 인물 상태 패널

- **상태**: DONE
- **웨이브**: 6
- **의존**: T14, T15
- **브랜치**: `task/T19-status-panel`
- **마이그레이션**: 없음
- **결정**: D13 (기록 때만 갱신. 엔딩·실시간 수치 없음)

## 목표
기억 문서를 파싱해서 인물의 관계·소지품·기술과 동행·위치를 한눈에 보여준다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/status/**` (`GET /api/stories/{id}/status`)
- 신규 `crack-frontend/src/components/panels/status/**`, `src/api/status.ts`, `src/pages/ChatPage.tsx`(탭 등록만)
- 관련 테스트

## 구현 내용
1. **백엔드:** `StoryState` + 주인공 `## 변화 기록` + 인물별 `## 기억`의 하위 섹션을 구조화된 JSON으로 만든다(T05 파서 사용). 동행 인물을 앞에 두고, 기억이 있는 인물만 넣는다.
2. **프론트 "상태" 탭**
   - 위쪽: 현재 위치, 시간, 동행
   - 주인공 카드: 관계, 스탯·기술, 소지품, 신체
   - 인물 카드: 관계, 최근 사건 3개, 소지품
3. **갱신 시점:** 기억 기록이 끝났을 때만 갱신한다(T15 뱃지 상태 변화 감지). 매 턴 조회하지 않는다.

## 완료 조건
- [x] `./gradlew test`(파서 → JSON), `npm run build`, `npm run lint` 통과
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23 시작
- `task/T19-status-panel` 브랜치(worktree)에서 시작. 의존 작업 T14·T15 머지 확인.
- 병렬 작업 주의: T18(ChatInput, ChatPage 탭 등록), T21(`index.css`, 메시지 저장소·기록 읽음 처리)이 동시에 진행 중이다. 백엔드는 새 `status/**`, 프론트는 `panels/status/**`와 `api/status.ts`에 두고, `ChatPage.tsx`는 탭 등록만 고친다.

### 2026-09-23 구현 완료 (REVIEW)

**한 일**
- 계약: DESIGN.md §7.5 "인물 상태 API"(`GET /api/stories/{id}/status` 응답 형식, 순서·포함 규칙, 갱신 시점).
- 백엔드 `com.crack.status`: `StoryStatusController`, `StoryStatusService`(`build(storyDir, recordedThroughTurn)`은 파일만 읽는 순수 함수), DTO(`StoryStatusResponse`, `ProtagonistStatus`, `CharacterStatus`, `ItemDto`, `RelationDto`, `EventDto`). T05 `MemoryDocs.readState/readProtagonist/readCharacters`와 `CharacterDoc.memory()`, `ProtagonistDoc.changes()`를 그대로 쓴다.
- 프론트: `src/api/status.ts`, `components/panels/status/**`(`statusPanelTab`, `StatusPanelLoader`(React.lazy), `StatusPanel`, `StatusCards`, `format.ts`). `ChatPage.tsx`는 `panelTabs`에 `statusPanelTab(storyId, memory.status)`를 넣고 import 한 줄만 추가.
- 테스트: `StoryStatusServiceTest`(7개: 빈 폴더, 기록 전, 전체 구조화, 동행 순서·별칭, 기억 없는 동행 제외, 깨진 state.json, 이름 없는 주인공), `StoryStatusApiTest`(4개: JSON 필드, 기록 전, 시나리오 원본 격리, 404).

**설계 판단**
- **인물 포함·순서:** 작업 파일대로 기억이 하나라도 있는 인물만 넣는다. 동행이어도 기억이 없으면 카드는 없고 위쪽 동행 칩에만 보인다. 동행은 `state.companions`의 이름이 파일명 또는 별칭과 같으면 인정한다(시나리오 관리자가 별칭으로 적을 수 있어서). 동행 목록 순서 → 나머지 이름순.
- **최근 사건 3개는 프론트가 고른다.** API는 사건을 문서 순서대로 전부 준다(기억 패널 등 다른 용도에 재사용할 수 있게). 끝 턴이 큰 것 → 같으면 문서 뒤쪽 → 턴 표기 없는 것은 뒤로. 더 있으면 제목에 "전체 N개".
- **깨진 `state.json`**은 500 대신 빈 상태로 보이고 경고 로그만 남긴다. 사용자가 문서를 잘못 고쳐도 인물 카드는 보이게 하려는 것이다.
- **주인공 이름**은 `- **이름**:` 값, 없으면 "주인공". 주인공 파일이 없으면 `protagonist: null`.
- `recordedThroughTurn`(스토리 행 값)도 응답에 넣어 "턴 N까지 기록한 기억 기준"을 보여 준다. `state.updatedAtTurn`은 시나리오 관리자 출력에 따라 비어 있을 수 있어서다.
- **갱신 시점(D13):** 패널은 `useMemoryStatus`의 `status`만 받는다. `settledKey = RUNNING이면 null, 아니면 "lastRecordId:status"`가 바뀔 때만 다시 받는다. 기록이 시작될 때(RUNNING)는 이미 받은 값이 있으면 받지 않고, 끝날 때(DONE·FAILED·REVERTED) 받는다. 탭을 열 때(SidePanel은 활성 탭만 그리므로 탭 전환 = 다시 마운트)도 받는다. 매 턴 조회는 없다. 기억 패널에서 문서를 고친 뒤 상태 탭으로 오면 새 값이 보이는 것도 이 덕분이다.
- 기록 중이면 패널 위에 "기억을 기록하는 중입니다. 끝나면 갱신됩니다." 스피너를 보인다.
- 패널은 T15처럼 지연 로딩한다(StatusPanel 청크 5kB, 메인 청크 281kB, 번들 경고 없음).

**확인**
- `./gradlew test` 409개 전부 통과. 로컬 `application.yml`을 스크래치로 치운 상태에서도 통과 확인 후 원복.
- `npm run build`(경고 없음), `npm run lint` 오류 0.
- **브라우저 확인(Fake 백엔드):** 스크래치 `initdb` 임시 클러스터(127.0.0.1:55445, 소켓 끔, DB `crack_t19`), sample-scenario 복사 데이터, bootJar에 datasource·data-path·fake·auth·포트(18219)를 명령줄 인자로 덮음, Vite 15219. Playwright(headless Chromium)로 조작.
  - 패널 → 상태 탭: 위치·시간 "모름", 동행 "없음", 안내 문구, 주인공 카드 "아직 변화 기록이 없습니다".
  - 입력창으로 10턴 전송: 1~10턴 동안 `GET /status` 요청 0회. 10턴 자동 기록이 끝나자 1회 요청 → 위치·시간·동행(설월), "턴 10까지 기록한 기억 기준", 주인공 관계, 설월 카드(동행 태그, 관계, 최근 사건 t1–10) 표시.
  - 탭을 열 때마다 1회(개발 모드 StrictMode라 2회) 요청. 모바일 너비(390px) 하단 시트 확인.
  - 끝난 뒤 백엔드·Vite·클러스터 중지, 스크래치 삭제.

**다음 작업자 주의점**
- 스크린샷에서 카드 안 여백·목록 점이 보이지 않는 것은 BUG-010(`index.css`의 레이어 밖 `*` 리셋, T21에서 수정 중) 때문이다. 대상 이름·턴 표기 뒤 간격은 여백 클래스 대신 공백 문자로 넣어 리셋과 무관하게 보이게 했다.
- 되돌리기(REVERTED)로 인한 갱신은 같은 `settledKey` 경로라 따로 브라우저 확인은 하지 않았다(DONE 경로로 확인).
- T18이 `panelTabs`에 지시 탭을 추가하면 `ChatPage.tsx`의 같은 줄에서 충돌할 수 있다. 배열에 항목을 합치면 된다.
