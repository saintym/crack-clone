# T21 실제 PostgreSQL 통합 검증 + 버그 수정

- **상태**: DONE
- **웨이브**: 6
- **의존**: T14, T15
- **브랜치**: `task/T21-pg-verification-bugfix`
- **마이그레이션**: 없음 (기존 V1~V7 검증만. 스키마 수정이 필요하면 DESIGN.md를 먼저 고치고 V8 사용)

## 목표
테스트는 H2로만 돌아서 PostgreSQL에서만 드러나는 문제가 있었다(BUG-008). 실제 PostgreSQL에서 V1~V7과 주요 흐름 전체를 검증하고, 발견된 버그를 고친다.

## 범위
- `crack-backend/src/main/kotlin/com/crack/message/repository/StoryMessageRepository.kt` (BUG-008)
- `crack-backend/src/main/kotlin/com/crack/memory/record/**` 중 읽음 처리 부분 (BUG-009)
- `crack-frontend/src/index.css` (BUG-010)
- 검증 중 발견한 **PG 전용 문제**의 최소 수정. 발견 목록과 수정 이유는 작업 로그에 적는다
- 관련 테스트, `bugs/`

## 구현 내용
1. **BUG-008:** `findEditedTurns`를 `since` 유무에 따라 나누거나 타입을 명시해 PG에서 동작하게 한다. 회귀 테스트를 추가한다(H2에서 재현이 안 되면 쿼리 분기 자체를 검증).
2. **BUG-009:** 읽음 처리는 DONE과 FAILED만 대상으로 한다. 테스트를 추가한다.
3. **BUG-010:** `index.css`의 전역 리셋을 `@layer base`로 감싼다. 주요 화면의 여백이 정상인지 브라우저로 확인한다.
4. **PG 스모크 테스트** (임시 클러스터, 사용자 DB 금지)
   - 빈 DB에 Flyway V1~V7 적용 → `ddl-auto: validate` 통과
   - Fake 프로바이더로 확인할 흐름:
     - 시나리오·스토리 생성, 프롤로그
     - 전송 21턴 이상(기억 기록 **2회 이상**), 재생성 후보, 수정
     - 기록 범위를 자르는 삭제 → 연쇄 되돌리기
     - 지시·명령(`/기록`, `/ooc`, 사용자 정의)
     - 키워드북·이미지 preview, 분기
     - 옛 스토리 이전(임시 폴더에 만든 옛 구조 픽스처)
   - 결과를 작업 로그에 표로 남긴다(흐름 / 결과 / 발견 버그).

## 완료 조건
- [x] `./gradlew test` 통과, `npm run build`와 `npm run lint` 통과
- [x] PG 스모크 결과 표. 발견한 버그는 수정했거나 `bugs/`에 등록
- [x] BUG-008~010 상태 갱신
- [x] 상태 `REVIEW` + 작업 로그

## 작업 로그

### 2026-09-23
- 시작. BUG-008~010 수정 후 임시 PG 클러스터로 스모크 테스트 진행 예정.

**한 일**
- **BUG-008:** `StoryMessageRepository.findEditedTurns`를 since 없는 쿼리와 `findEditedTurnsSince`(since 비교)로 나누고, `MessageService.editedTurnsSince`가 since 유무로 고른다. 공개 메서드 시그니처(DESIGN §5.1)는 그대로다.
  - 회귀 테스트 `EditedTurnsQueryTest`: H2는 원래 쿼리도 받아 주므로 ① 분기 호출(목 저장소) ② `com.crack` 아래 모든 저장소 인터페이스의 `@Query`에 `:파라미터 IS [NOT] NULL`이 없는지 검사한다. 같은 유형의 쿼리가 다시 들어오는 것을 막는다.
- **BUG-009:** `markAllSeen` → `markSeen(storyId, statuses)`(`status IN (DONE, FAILED)`). DESIGN.md §7.2 `/memory/records/seen` 설명을 먼저 고쳤다. 테스트 `MemoryRecordSeenTest`(RUNNING 제외, 끝난 뒤 뱃지, 다른 스토리 무관).
- **BUG-010:** `index.css`의 전역 리셋·`html/body/#root`·`body` 기본 스타일을 `@layer base`로. 브라우저 확인에서 같은 원인을 하나 더 찾아 `.safe-top`/`.safe-bottom`을 `@layer components`로 옮겼다(헤더 `py-4`의 위 여백, 입력창 `py-3`의 아래 여백, 시트의 `pb-8`이 0이 되던 문제).
- PG 스모크 테스트(아래 표). 새로 찾은 PG 전용 버그는 없다.

**설계 판단**
- BUG-008은 `CAST(:since AS timestamp)` 같은 타입 명시 대신 쿼리 분리를 택했다. JPQL에서 파라미터 캐스트는 Hibernate 방언에 기대는 부분이 있고, 분리한 쿼리는 H2와 PG 모두에서 뜻이 분명하다.
- 안전 영역 클래스는 `viewport-fit=cover`가 없어 inset이 항상 0이다. 그래서 유틸리티보다 아래 레이어로 내려도 동작 변화가 없다. cover를 도입할 때는 요소별 여백 + inset(calc)으로 다시 설계해야 한다(주석으로 남김).

**PG 스모크 테스트** — 임시 클러스터(PostgreSQL 14.18, `initdb -U t21`, 127.0.0.1:55441, `unix_socket_directories=''`, DB `crack_t21`), bootJar를 datasource·`crack.data-path`(스크래치)·`--crack.ai.fake.enabled=true --crack.ai.default-provider=fake --crack.auth.password=`로 덮어 실행. Python(urllib) 드라이버로 API를 호출했다. 사용자 5432 서버·`crack` DB·실제 데이터는 건드리지 않았고, 끝난 뒤 서버·클러스터·스크래치를 모두 지웠다.

| 흐름 | 결과 | 발견 버그 |
|---|---|---|
| 빈 DB에 Flyway V1~V7 → `ddl-auto: validate` | 7개 적용, 기동 성공 | 없음 |
| 시나리오 등록(기존 폴더 보존), 스토리 생성, 프롤로그 | 턴 0 PROLOGUE 저장 | 없음 |
| 전송 10턴 → 자동 기록 #1 | DONE (1–10), since=null 경로 | 없음 |
| 턴 3 수정 → 10턴 더 → 자동 기록 #2 | DONE (11–20), 재반영 [3] — **BUG-008 경로(since 있음)가 PG에서 동작** | BUG-008 수정 확인 |
| 21턴째 전송, 재생성(지시 포함) → 후보 2개, 후보 선택, 수정, 이어쓰기 | 모두 200 | 없음 |
| 수동 기록 #3, 다시 수동 기록 | DONE (21–22), 이후 `NOTHING_TO_RECORD`(200, 예전엔 500) | 없음 |
| 읽음 처리, 기록 상세(diff 파일 4개), 내보내기 | 204·`unseen=false`, 200 | 없음 |
| `/ooc` 시스템 명령, 지시 추가·끄기·삭제 | 모두 정상, preview BOTTOM에 켜진 지시만 | 없음 |
| 사용자 정의 명령 `/일기` 전송(kind=COMMAND), 재생성, 모르는 명령 | 200, 200, 400 | 없음 |
| `/기록` 시스템 명령 → 기록 #4 | DONE (23–23) | 없음 |
| prompt-preview: 키워드북(`산적`→흑풍채), 이미지, 지시 | 세 섹션 모두 들어감. `GET /images` 2건 | 없음 |
| 분기(턴 15) → 분기에서 전송·수동 기록 | `recordedThroughTurn=15`, 기록 이력 없음, 기록 DONE (16), 지시 복사됨 | 없음 |
| 턴 5부터 삭제 → 연쇄 되돌리기 | 기록 4→3→2→1 모두 REVERTED, `recorded_through=0`, 연대기 원상태 | 없음 |
| 되돌린 뒤 6턴 전송 → 자동 기록, 명시적 되돌리기, 재되돌리기 | DONE (1–10), REVERTED, 400 | 없음 |
| 수동 기록 → 턴 2 수정 → 재반영만 있는 기록 | DONE (11–10 빈 범위, 재반영 [2]). since 있음 경로 한 번 더 | 없음 |
| 옛 스토리 이전(`_legacy` 행 + 옛 `chat/archive`·`chat_latest`·`memory/`) | MIGRATED(메시지 6, 턴 3), 재실행 SKIPPED, `user_note.md` 변환, 이전 후 전송 200 | 없음 |
| 스토리 보관·삭제, 시나리오 문서 API, 인증 verify | 모두 정상 | 없음 |
| 앱 로그 | ERROR/WARN 0건 | — |

기억 기록은 모두 9회 DONE(스토리 1에서 연속 4회 포함). 한 번도 FAILED가 없었다.

**확인한 방법**
- `./gradlew test`: 403개 전부 통과(새 테스트 5개 포함).
- `npm ci && npm run build && npm run lint`: 통과, lint 오류 0.
- 브라우저: 위 PG 백엔드 + Vite(`CRACK_WEB_PORT=15221`), 로컬 npx 캐시의 Playwright 1.55 + Chromium headless(420×860). 로그인·시나리오 목록·시나리오 상세·스토리 목록·채팅을 찍고, `p-*`/`px-*`/`py-*` 요소의 계산된 padding이 클래스 값과 같은지 확인했다(수정 전 안전 영역 클래스가 있는 헤더는 위 여백 0 → 수정 후 16px).

**다음 작업자가 알아야 할 것**
- 채팅 입력창의 placeholder("메시지를 입력하세요 (빈 채로 Enter: 이어쓰기)")가 폭 420px에서 두 줄로 넘친다. 여백이 살아나면서 보이게 된 것으로, `ChatInput`은 T18 범위라 고치지 않았다.
- PG에서 확인하지 못한 흐름: 생성 중 409(동시성), 기록 도중 삭제로 RUNNING 취소, 서버 재시작 시 RUNNING→FAILED, 실제 AI 프로바이더. Fake 응답이 짧아 경쟁 상황을 만들기 어렵다.
- 테스트는 여전히 H2다. 이번 검사 테스트는 "파라미터 IS NULL" 한 가지 유형만 막는다. PG 테스트 인프라(Testcontainers 등)는 후속 과제다.
- 버그 문서: BUG-008~010 수정완료로 갱신.
