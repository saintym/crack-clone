# T14 기억 기록 파이프라인

- **상태**: REVIEW
- **웨이브**: 4
- **의존**: T01, T05, T07, T08
- **브랜치**: `task/T14-memory-pipeline`
- **마이그레이션**: **V7**
- **설계**: DESIGN.md §7.2~7.4 / 결정 D3, D6, D7, D8

## 목표
10턴마다 또는 `/기록` 명령으로, 시나리오 관리자 → 캐릭터 관리자(병렬) → 주인공 반영 순서로 기억 문서를 **배경에서** 갱신한다. 이력을 남기고 되돌릴 수 있게 한다. **플레이를 끊지 않는다.**

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/memory/record/**`(서비스, 관리자 프롬프트, 파서, 컨트롤러)
- `story/entity/Story.kt`(`recordedThroughTurn` 필드 추가만)
- `src/main/resources/db/migration/V7__memory_records.sql`
- `ai/fake/**`(기록용 fake 응답 등록만)
- 관련 테스트

## 구현 내용
1. **테이블과 필드:** `memory_records` 엔티티·리포지토리, `stories.recorded_through_turn`(DESIGN.md §3 V7)
2. **`MemoryRecordService.trigger(storyId, reason)`:** 싱글 플라이트, 범위 고정, 재반영 턴 계산(§7.2). 전용 실행기에서 비동기로 돈다.
3. **관리자 프롬프트:** 세 관리자(시나리오, 캐릭터, 주인공)와 압축용 프롬프트. 각 프롬프트에 §7.4 기록 기준을 그대로 넣는다. 출력 형식은 §7.3의 XML 태그다.
4. **실행**
   - 캐릭터 관리자는 병렬로 돌린다(`crack.memory.record.concurrency`, 기본 3)
   - 모든 호출은 `AiGateway`(purpose = RECORD)를 거친다
5. **원자적 반영:** 모든 결과를 모은 뒤 → before 스냅샷 → `MarkdownSections.replaceSection`과 `Chronicle`, `StoryState`(T05)로 쓰기 → DONE. 실패하면 파일을 쓰지 않고 1회 재시도한 뒤 FAILED.
6. **트리거**
   - `AfterTurnHook` 구현: `turnCount - recordedThrough >= crack.memory.record.every-turns`(기본 10)면 AUTO
   - `POST /api/stories/{id}/memory/record`는 MANUAL
7. **되돌리기와 삭제 연동**
   - `POST /memory/records/{id}/revert`: 가장 최근 DONE만 허용
   - `TruncateHook` 구현: 잘린 턴이 기록 범위 안이면 연쇄로 되돌린다(§7.2)
8. **조회**
   - `GET /memory/records`
   - `GET /memory/records/{id}`: 변경 파일별 before와 현재 내용. diff는 프론트가 계산한다
   - 메시지 목록 응답(`GET /messages`)의 `story`에 `memory: {status, lastRecordId, unseen}`을 추가한다. T07 DTO에 필드만 추가한다
9. **분기 연동(T09 메모):** 분기로 만든 스토리의 `recorded_through_turn`은 `min(원본값, 분기 기준 턴)`으로 설정한다(`StoryBranchService`). 기억 문서는 폴더째 복사되지만 `memory/history`는 복사되지 않으므로, 분기 스토리에서는 분기 전 기록을 되돌릴 수 없다.
10. **Fake 응답:** 기록용 fake 응답을 등록해 리모트에서도 파이프라인 전체를 돌릴 수 있게 한다.

## 완료 조건
- [x] `./gradlew test` 통과. Fake 프로바이더로 통합 테스트
  - 10턴에 자동 실행되고 인물·주인공·연대기·state가 갱신되며 원본 설정 부분은 불변
  - 실패 주입 시 파일 변경 0
  - 되돌리기로 파일과 recorded_through 복원
  - 기록 범위를 자르는 삭제 시 연쇄 되돌리기
  - 수정된 과거 턴 재반영
  - 싱글 플라이트
- [x] 상태 `REVIEW` + 작업 로그 (PR·머지는 오케스트레이터)

## 작업 로그

### 2026-09-23 시작
- `task/T14-memory-pipeline` 브랜치에서 시작. 의존 작업 T01·T05·T07·T08(+T09) 머지 확인.
- 병렬 작업 주의: T13(prompt, ConversationBuilder)과 T11(프론트)이 동시에 진행 중이다. prompt 패키지와 ConversationBuilder는 건드리지 않는다.

### 2026-09-23 구현 완료 (REVIEW)

**한 일**
- V7: `memory_records`(+ `seen` 칼럼, `(story_id, id)` 인덱스), `stories.recorded_through_turn`. `Story.recordedThroughTurn` 필드.
- `memory/record/**`
  - `RecordPrompts`: 시나리오·캐릭터·주인공·압축(섹션/연대기) 프롬프트. §7.4 기록 기준 원문을 모든 프롬프트에 넣고, 입력 구획과 출력 태그 형식을 예시와 함께 지시한다. 튜닝은 이 파일만 고치면 된다.
  - `RecordInputs`(입력 조립), `RecordOutputParser`(§7.3 태그 파서)
  - `MemoryRecordPipeline`: ①~④ 계산만 한다(파일을 쓰지 않음). 캐릭터 관리자는 `MemoryRecordExecutors.workers`(크기 = concurrency)에서 병렬.
  - `MemoryRecordService`: 트리거(싱글 플라이트, 범위 고정, 재반영 턴), 배경 실행, 원자적 반영, 1회 재시도 후 FAILED, 되돌리기, 삭제 연동, 조회, 읽음 처리, 기동 시 남은 RUNNING → FAILED.
  - `MemoryRecordFiles`: `memory/history/{id}/before/…` 스냅샷과 `created.json`(기록 전에 없던 파일 목록).
  - `MemoryRecordAfterTurnHook`(AUTO), `MemoryRecordTruncateHook`, `MemoryRecordController`(+409 핸들러).
- `ai/fake/FakeRecordResponder`: 기동 시 `FakeResponses`에 RECORD 응답기를 등록한다. 시스템 프롬프트의 출력 태그로 관리자를 가리고 입력에서 결정적 응답을 만든다.
- 범위 밖 최소 수정
  - `chat/flow/ChatFlowService.kt`: `StoryChatInfo.recordedThroughTurn`을 칼럼 값으로, `memory` 필드 추가(기존 필드·형식 불변). T07 주의점에 적힌 연결 작업이다.
  - `story/service/StoryBranchService.kt`: 분기 스토리 `recordedThroughTurn = min(원본값, 기준 턴)`(작업 파일 9번).
  - `application.yml.example`: `crack.memory.budget.*`, `crack.memory.record.*` 예시.

**설계 판단 (DESIGN.md §3, §5.2, §7.2, §7.3를 먼저 고쳤다)**
- **재반영 기준 시각을 `finished_at` → `created_at`**(범위를 고정한 시각)으로 바꿨다. 기록이 도는 동안 고친 턴은 이번 기록이 원문을 읽은 뒤일 수 있어서, `finished_at` 기준이면 영영 재반영되지 않는다. 일찍 잡아 한 번 더 읽는 쪽이 안전하다.
- **재반영만 있는 기록은 `from = recorded+1, to = recorded`(빈 범위)**로 저장한다. 되돌리기 규칙(`recorded = from - 1`)이 그대로 맞는다. 이때 시나리오 관리자는 `<chronicle>없음</chronicle>`을 낼 수 있고, 고친 턴이 든 기존 회차는 선택 태그 `<revised entry="N">`로 통째로 고친다(연대기는 append만 있어서 과거 회차를 고칠 방법이 필요했다).
- **범위의 끝:** 마지막 메시지가 응답 없는 USER면 그 턴은 빼고 기록한다(생성 중 `/기록`).
- **반영 직전 검사:** 스토리 행 잠금 아래에서 ① 기록이 아직 RUNNING인지(삭제 연동으로 취소됐는지) ② 읽었던 문서(연대기, state, 주인공, 관련 인물)가 그대로인지 확인한다. ②가 어긋나면(문서 API로 사용자가 고침) 그 시도를 버리고 다시 계산한다. 사용자의 수정을 덮어쓰지 않기 위해서다.
- **파일과 DB 원자성:** 파일은 트랜잭션에 묶이지 않으므로 반영·되돌리기 때 `TransactionSynchronization.afterCompletion`으로 롤백 시 파일을 원래대로 돌린다. 삭제 연동의 되돌리기도 삭제 트랜잭션이 롤백되면 파일을 다시 돌린다.
- **삭제 연동:** 잘린 최소 턴 ≤ `recorded_through`이면 최근 DONE부터 되돌린다. DONE이 없는데 `recorded_through`가 남아 있으면(분기 스토리) 파일은 두고 턴만 내린다. 턴 0(프롤로그) 삭제는 턴 1 기준으로 본다. 실행 중 기록의 `to`가 잘린 턴 이상이면 FAILED(취소)로 바꾸고 반영하지 않는다. 삭제 연동에서 스냅샷이 없으면 삭제를 막지 않도록 로그만 남기고 상태와 턴은 내린다(명시적 되돌리기 API는 400).
- **되돌리기는 실행 중이면 409.** 실행 중 기록은 되돌리기 전 상태를 기준으로 계산하고 있어서, 되돌린 뒤 반영하면 범위가 어긋난다.
- **재생성(`mode = REGENERATE`)은 AUTO를 트리거하지 않는다.** 턴이 늘지 않으며, 실패한 기록이 후보 넘기기마다 다시 돌지 않게 하려는 것이다. SEND·CONTINUE는 트리거한다.
- **`unseen`은 서버에 저장한다**(`memory_records.seen` + `POST /memory/records/seen`). T15는 로컬 저장도 허용했지만, 기기를 바꿔도 뱃지가 맞도록 서버에 두었다. 프론트가 안 써도 된다.
- **involved 이름 매칭:** 파일명 또는 별칭과 정확히 같아야 한다. 주인공과 모르는 이름은 버린다. 주인공 문서가 없으면 주인공 변화는 버린다(경고 로그).
- **압축:** 인물·주인공 섹션은 예산을 넘은 문서만 `COMPRESS_SECTION`으로 줄인다. 연대기는 회차 원문이 예산을 넘으면 오래된 회차 절반(최소 1개, 최근 1개는 남김)을 `COMPRESS_CHRONICLE`로 기존 장 요약과 합친다.
- 실행기는 `Executor` 타입 빈으로 노출하지 않았다(스프링 부트 기본 작업 실행기 자동 설정이 물러나면 MVC 비동기(SSE)에 영향이 간다).

**확인 방법**
- `cd crack-backend && ./gradlew test`: 394개 전부 통과. 로컬 `application.yml`을 스크래치 경로로 치운 상태에서도 394개 통과 확인 후 원복.
- 신규 테스트 25개: `RecordOutputParserTest`(7), `FakeRecordResponderTest`(3), `MemoryRecordPipelineTest`(14, `@SpringBootTest` + Fake), `StoryBranchTest`(+1).
  - 10턴째를 실제 SSE 전송으로 보내 AfterTurnHook → AUTO 기록 → 인물 `## 기억`·주인공 `## 변화 기록`·연대기·state 갱신, 원본 설정 부분 불변, 원문에 없는 인물(무극) 불변, 스냅샷, `story.memory` 뱃지와 읽음 처리
  - 실패 주입(캐릭터 관리자 예외, 형식 오류) → 2회 시도 후 FAILED, 파일 변경 0, 이력 폴더 없음 / 첫 시도만 실패하면 재시도로 DONE
  - 되돌리기(스택, 400, 파일·recorded_through 복원, 되돌린 뒤 같은 범위 재기록), 삭제 연쇄 되돌리기(턴 15 → 두 번째만, 턴 5 → 전부), 범위 밖 삭제, 기록 없이 턴만 내리기
  - 수정된 과거 턴 재반영(빈 범위 기록, `<revised>`로 회차 1 교체, 다시 트리거하면 NOTHING_TO_RECORD)
  - 싱글 플라이트(실행 중 ALREADY_RUNNING, 되돌리기 409), 실행 중 삭제로 취소, 기록 중 문서 수정 시 재계산으로 사용자 수정 보존, 예산 초과 압축, API 응답 형식, 이전되지 않은 스토리

**겪은 문제**
- `RecordPrompts`에서 여러 줄 상수를 `$RECORD_CRITERIA`로 끼운 뒤 `trimIndent()`를 부르면, 끼운 줄의 들여쓰기가 0이라 전체 들여쓰기가 하나도 안 벗겨진다. `{{…}}` 자리표시자로 두고 `trimIndent()` 뒤에 치환하도록 바꿨다(테스트로 고정).
- `StoryMessageApiTest`의 `재생성 스트림이 실패하면 error를 보내고 원본은 그대로다`가 한 번 `ConcurrentModificationException`으로 실패했다(MockMvc `PrintingResultHandler`가 응답 헤더를 출력하는 동안 비동기 스레드가 헤더를 쓰는 경쟁). T14 변경 전 코드에서도 나는 기존 간헐 실패이고, 다시 돌리면 통과한다. 범위 밖이라 고치지 않았다.

**다음 작업자 주의점**
- **T13 연결(오케스트레이터):** 마지막 기록 턴은 `Story.recordedThroughTurn`(`stories.recorded_through_turn`)이다. `storyRepository.findById(id).recordedThroughTurn`으로 읽으면 된다. 기록 파이프라인이 DONE·되돌리기·삭제 연동 때 갱신한다. 대화 원문 범위(§6.1)는 이 값을 읽기만 하면 된다. 동행 인물은 기록 때 `state.json.companions`가 갱신된다.
- **T15(프론트) API 형식:** DESIGN.md §7.2 "API" 표.
  - `GET /api/stories/{id}/messages` → `story.memory = {status: "NONE"|"RUNNING"|"DONE"|"FAILED"|"REVERTED", lastRecordId: number|null, unseen: boolean}`. RUNNING이면 스피너, `unseen`이면 점.
  - `POST /memory/record` → `{result: "STARTED"|"ALREADY_RUNNING"|"NOTHING_TO_RECORD", record: MemoryRecordSummary|null}` (항상 200, 결과를 기다리지 않음)
  - `GET /memory/records` → `MemoryRecordSummary[]`(최근 것부터). `MemoryRecordSummary = {id, fromTurn, toTurn, reason, status, changedFiles, rerecordedTurns, error, createdAt, finishedAt, revertable}`. 되돌리기 버튼은 `revertable`로 판단. 재반영만 한 기록은 `toTurn < fromTurn`이다(범위 대신 "고친 턴 재반영" 등으로 표시).
  - `GET /memory/records/{id}` → 위 필드 + `files: [{path, before, current}]`(null 가능). diff는 프론트가 계산.
  - `POST /memory/records/{id}/revert` → `MemoryRecordSummary`. 최근 DONE이 아니면 400, 실행 중이면 409(`{message, status}`).
  - `POST /memory/records/seen` → 204.
- **T16:** `/기록`은 `POST /api/stories/{id}/memory/record`를 부르면 된다.
- **T12:** 옛 `memory/service/MemoryService`, `StorySummaryService`, `memory/controller`와 이름·경로가 겹치지 않는다(새 경로는 `/memory/record`, `/memory/records…`). 옛 것을 지워도 새 코드에 영향이 없다.
- 관리자 프롬프트 품질은 Fake로는 검증할 수 없다. 실제 CLI로 몇 회차 돌려 보고 `RecordPrompts.kt`를 튜닝할 것. 태그 이름을 바꾸면 `RecordOutputParser`와 `ai/fake/FakeRecordResponder`도 같이 바꿔야 한다.
- 기록 실행 중 싱글 플라이트는 프로세스 메모리 + DB RUNNING이다(개인용 단일 서버 전제). 기동 시 남은 RUNNING은 FAILED로 바꾼다.
- 로컬 PostgreSQL에서는 기동 시 Flyway가 V7을 적용한다.
