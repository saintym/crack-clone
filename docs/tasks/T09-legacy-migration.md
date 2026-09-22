# T09 기존 데이터 이전 + 분기 이식

- **상태**: REVIEW
- **웨이브**: 2
- **의존**: T03, T08
- **브랜치**: `task/T09-legacy-migration`
- **마이그레이션**: 없음 (코드 기반 1회성 이전)

## 목표
사용자 로컬에 있는 기존 스토리를 v2 구조로 옮긴다. 사용자가 로컬에서 직접 실행할 수 있게, 재실행해도 안전한(멱등) 방식으로 만든다. 분기 기능도 새 저장소 위로 옮긴다(확장은 하지 않음, D4).

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/migration/**`
- `story/service/StoryService.kt`의 `branch`, `story/controller/StoryController.kt`의 branch 요청 형식(`messageIndex` → `messageId`)
- 관련 테스트(픽스처는 테스트 안에서 생성)

## 구현 내용
1. `LegacyStoryMigrator`: `story.json`이 없는 스토리 폴더를 대상으로 한다.
   - ① 원본 문서 중 스토리에 없는 것을 복사한다. 이미 있는 스토리별 `characters/*.md`는 **유지**한다.
   - ② `memory/must_remember.md` → `user_note.md`
   - ③ `chat/archive/turn_*.md`를 번호 순으로, 그다음 `chat/chat_latest.md`를 DB 메시지로 가져온다.
     - 형식은 `## USER` / `## ASSISTANT`이다. 파서는 **이 패키지 안에 자체 구현**한다. `ChatFileService`는 T12에서 삭제되기 때문이다.
     - 턴 번호는 이어서 매긴다.
   - ④ `memory/summary_*.md`를 합쳐 `chronicle.md`의 `## 장 요약` 초안으로 만든다.
   - ⑤ `story.json`을 쓴다.
   - `_legacy` 스토리(T02)는 시나리오 폴더에 있던 chat과 memory를 새 `stories/{dir}`로 옮기고 `dir_name`을 갱신한다.
2. **실행 방식:** `crack.migration.legacy.enabled=true`일 때 기동 시 실행하는 `ApplicationRunner`, 또는 `POST /api/admin/migrate-legacy`. 결과 보고서(스토리별 가져온 메시지 수, 경고)를 로그와 응답으로 남긴다.
3. **원본 보존:** 옮긴 옛 파일은 지우지 않고 `legacy/` 하위로 옮긴다.
4. **분기:** 스토리 폴더를 통째로 복사한다(memory/history 제외). 메시지는 기준 `messageId`의 seq까지 복사한다. `recorded_through_turn`은 T14 이전이면 0이다. T14가 머지된 뒤라면 `min(원본값, 분기 턴)`으로 두고 작업 로그에 적는다.

## 완료 조건
- [x] `./gradlew test` 통과 (303개)
- [x] 임시 디렉터리에 옛 구조 픽스처를 만들어 이전 → 메시지 수, 턴 번호, user_note, 멱등성(두 번 실행) 검증
- [x] 분기 테스트: 메시지 절단 지점, 문서 복사, 원본 불변
- [x] 사용자용 실행 안내를 이 파일의 작업 로그에 작성
- [x] 상태 `REVIEW` + 작업 로그 (PR은 오케스트레이터가 머지)

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T09-legacy-migration`(worktree).

**한 일**
- 신규 `com.crack.migration`
  - `legacy/LegacyChatParser`: `## USER` / `## ASSISTANT` 줄 단위 파서(자체 구현). 첫 줄 `[감정: …]` 분리(§5.3 정규식).
  - `legacy/LegacyFiles`: 옛 파일 찾기(원래 위치 → 없으면 `legacy/` 아래), 아카이브 번호 순 정렬, `must_remember.md` → `user_note.md` 변환, `summary_*.md` → `chronicle.md` 장 요약 초안.
  - `legacy/LegacyStoryMigrator`: 이전 본체. `legacy/LegacyMigrationReport`: 보고서. `legacy/LegacyMigrationRunner`: `crack.migration.legacy.enabled=true`일 때 기동 시 실행. `controller/MigrationController`: `POST /api/admin/migrate-legacy`.
- 분기: 신규 `story/service/StoryBranchService`, `story/dto/StoryBranchRequest`. `StoryController`(두 경로 모두)가 이 서비스를 부른다. `StoryService.branch`는 삭제했다.
- `DESIGN.md` §2에 이전 규칙과 분기 API(`{title, messageId}`, 과도기 `messageIndex`) 두 줄을 추가했다.
- 테스트 26개 추가, 1개 삭제: `LegacyChatParserTest`(6), `LegacyFilesTest`(7), `LegacyStoryMigratorTest`(8, `@SpringBootTest`+MockMvc), `StoryBranchTest`(5, 〃). `StoryServiceTest`의 옛 파일 기반 분기 테스트는 삭제했다.

**이전 동작 (스토리 하나)**
0. `_legacy`: `stories/{밀리초}` 폴더를 만들고 표식 파일 `.legacy-migration`(내용: 스토리 ID)을 먼저 쓴 뒤 시나리오 폴더의 `chat/`, `memory/`를 그리로 옮기고 DB `dir_name`을 JPQL UPDATE로 바꾼다. 도중에 끊겨도 다음 실행이 표식으로 같은 폴더를 찾는다.
1. 원본 문서(`StoryFiles.COPIED_FILES` + `characters/*.md`) 중 스토리에 **없는 것만** 복사한다. 스토리별 인물 문서는 유지한다. `images.md`는 복사하지 않는다.
2. `user_note.md`가 없으면 `memory/must_remember.md`로 만든다. 제목 `# 필수 기억사항` → `# 유저노트`, 옛 템플릿 안내 문구 줄은 뺀다. 템플릿 그대로면 빈 유저노트(`# 유저노트`)다.
3. `chronicle.md`가 없으면 요약들을 `**턴 a–b**` 소제목으로 이어 `## 장 요약`에 넣는다(요약 안의 `#` 제목 줄은 굵은 글씨로 바꿔 섹션 구조를 지킨다). `directives.json`, `state.json`도 없으면 만든다.
4. 아카이브(번호 순) → `chat_latest.md` 순으로 DB에 가져온다. 한 트랜잭션이며 턴은 `MessageService`가 매긴다.
5. `chat/`, `memory/` → `legacy/chat/`, `legacy/memory/` (같은 이름이 있으면 시각을 붙여 옮긴다).
6. 표식 파일을 지우고 `story.json`을 쓴다. 결과는 `initFromScenario`로 만든 새 스토리와 같은 파일 세트 + `legacy/`.

**설계 판단**
- **멱등성:** `story.json`을 맨 마지막에 쓰므로 끝난 스토리는 다음 실행에서 `SKIPPED`다. 끝나지 않은 스토리는 각 단계가 "없을 때만" 하므로 다시 실행하면 이어서 한다. 메시지는 **스토리에 DB 메시지가 하나라도 있으면 가져오지 않는다**(경고). 가져오기 트랜잭션 뒤, `story.json` 전에 끊긴 경우의 중복을 막기 위해서다.
- **대상:** DB의 모든 스토리(보관 포함). 스토리마다 따로 실패 처리하고(`FAILED` + 오류), 나머지는 계속한다. DB에 없는 고아 폴더는 건드리지 않는다. 동시 실행은 `synchronized`로 직렬화한다.
- **턴 매기기:** USER는 새 턴, USER 바로 뒤 ASSISTANT는 같은 턴(NORMAL), ASSISTANT 뒤 ASSISTANT는 `CONTINUATION`(새 턴), **맨 처음 메시지가 ASSISTANT면 `PROLOGUE`(턴 0)**. 옛 파일에는 응답 실패로 USER가 연달아 있거나 빈 ASSISTANT가 있다(실제 데이터 형식을 읽기만 해서 확인). 빈 메시지와 감정 태그만 있는 응답은 건너뛰고 개수를 경고로 남긴다. 연달은 USER는 각자 턴을 연다(§3 규칙 그대로).
- **감정 태그:** 옛 응답은 첫 줄에 `[감정: …]`이 그대로 들어 있다. §5.3에 맞춰 content에서 떼고 emotion에 넣는다(100자 제한).
- **`dir_name` 갱신:** `Story.dirName`이 `val`이라 엔티티를 고치지 않고(범위 밖) 이전기 안에서 JPQL `UPDATE`로 바꿨다.
- **실행 방식은 둘 다** 만들었다. 기동 시 러너는 실패해도 서버 기동을 막지 않는다.
- **분기를 `StoryBranchService`(새 파일)로 뺐다.** `StoryService` 생성자를 바꾸면 T10(`create`에 프롤로그 삽입, 생성자 변경 가능성)과 충돌하기 쉬워서, `StoryService`에서는 `branch`만 지우고 생성자는 그대로 뒀다. 그래서 `StoryService`의 `chatFileService` 의존성은 이제 쓰이지 않는다(T12에서 `ChatFileService`와 함께 제거).
- **분기 복사 제외 목록:** `memory/history`(작업 파일 명시), `legacy/`(이전 전 옛 파일은 원본 것이므로), `story.json`(새로 씀), `.`으로 시작하는 파일(원자적 쓰기 임시 파일 등). 메시지는 seq·턴·kind·emotion·선택 후보·`edited_at`·`created_at`을 그대로, 후보(instruction 포함)도 복사한다. `turn_count` = 복사한 메시지의 최대 턴.
- **이전되지 않은 스토리(`_legacy` 또는 `story.json` 없음)의 분기는 400**이다. 메시지가 DB에 없어 기준을 잡을 수 없고, `_legacy`는 폴더가 시나리오 원본이라 통째 복사하면 `stories/`까지 복사된다.
- **분기 요청:** `{title, messageId}`. 과도기용 `messageIndex`는 **메시지 seq**로 해석한다(seq는 0부터 빈틈없이 이어진다). 둘 다 있거나 둘 다 없으면 400, 제목이 비면 400, 다른 스토리의 `messageId`는 404, 없는 `messageIndex`는 400.
- **`recorded_through_turn`:** T14가 아직 머지되지 않아 칼럼이 없다. 새 스토리는 기본값 0이 된다. 연대기·인물 문서는 폴더째 복사되므로 분기 시점 이후 기록이 들어 있을 수 있다(작업 파일 명세대로 통째 복사).

**확인 방법**
- `cd crack-backend && ./gradlew test`: 303개 전부 통과(기준선 278 - 1 + 26).
- `LegacyStoryMigratorTest`는 임시 데이터 폴더(`${java.io.tmpdir}/crack-test-data/legacy-{uuid}`)에 샘플 시나리오 + 옛 구조를 만들어 검증한다: 메시지 10개·턴 6·역할·순서·CONTINUATION·감정 분리 / 파일 세트(원본 복사, 스토리별 인물 유지, user_note, chronicle, directives, state, story.json, `legacy/` 보존) / 두 번 실행 시 SKIPPED + 파일·메시지 불변 / 메시지 가져온 뒤 끊긴 경우 중복 없음 / `_legacy` 이전(dir_name, 원본 문서 불변, 재실행) / `_legacy` 중단 후 표식 폴더 재사용 / v2 스토리 불변 / 관리 API 응답.
- `StoryBranchTest`: 절단 지점(seq 3까지 4개, 턴 2), 후보·수정 표시 복사, 문서 복사와 제외 목록, 원본 파일·메시지 불변, 분기 후 상호 독립, `messageIndex` 과도기 경로(시나리오 하위 경로), 잘못된 요청 400/404와 부작용 없음, 이전 전 스토리 400.
- 실제 사용자 데이터(`data/`, `crack-backend/data`)는 파일 목록과 `chat_latest.md` 형식만 읽어서 확인했다. 수정·실행하지 않았다.

**겪은 문제**
- 처음 셸 스크립트로 파일을 만들 때 `controller/` 폴더가 없어서 첫 `cat >`가 실패했다(바로 뒤에서 폴더를 만들고 다시 써서 해결).
- 이전기 통합 테스트는 `migrateAll`이 DB의 **모든** 스토리를 보므로, 같은 H2(`testdb`)를 쓰는 다른 테스트의 스토리도 대상이 된다. 검증은 테스트가 만든 스토리 ID의 결과만 골라서 하고, 테스트 시나리오 이름은 UUID로 겹치지 않게 했다.

**범위 밖 수정**
- `docs/DESIGN.md` §2에 두 줄 추가(계약 문서화, CLAUDE.md 규칙 3).
- `story/service/StoryBranchService.kt`, `story/dto/StoryBranchRequest.kt` 신규: 범위의 "StoryService의 branch / StoryController의 branch 요청 형식"을 별도 파일로 둔 것이다(T10 충돌 회피).

**다음 작업자 주의점**
- **T11:** `src/api/stories.ts`의 `branch`를 `{ messageId, title }`로 바꾸면 된다. 응답은 기존과 같은 `StoryResponse`. 이전되지 않은 스토리는 400(메시지가 DB에 없음).
- **T12:** `StoryBranchRequest.messageIndex`와 `StoryBranchService`의 `messageIndex` 분기를 지운다. `StoryService` 생성자의 `chatFileService`는 이제 쓰이지 않으니 `ChatFileService`와 함께 지운다. 이전이 끝난 뒤에는 `DataPaths`의 `_legacy` 분기, 문서 API의 `_legacy` 쓰기 차단, `PromptAssembler`의 `memory/must_remember.md` 폴백을 지울 수 있다(사용자가 실제로 이전했는지 확인한 뒤). 이전기(`com.crack.migration`) 자체는 사용자가 실행을 마칠 때까지 남겨 두는 것을 권장한다.
- **T14:** 분기에서 `recorded_through_turn = min(원본값, 분기 턴)`을 넣어야 한다(`StoryBranchService`의 `Story(...)` 생성 부분). `memory_records`는 복사하지 않는다(`memory/history`도 복사하지 않으므로 되돌리기 대상이 없다).
- 옛 `ChatService`(T07 전)는 여전히 `chat/chat_latest.md`에 쓴다. **이전 후에 옛 채팅 화면으로 대화하면 그 대화는 다시 파일에만 쌓이고, 이미 `story.json`이 있어 다시 이전되지 않는다.** 그래서 사용자 실행은 T07(DB 채팅)과 T11(새 채팅 화면)이 머지된 뒤를 권장한다.

**사용자용 실행 안내**
> 권장 시점: T07·T11이 main에 머지된 뒤(새 채팅 화면이 DB 메시지를 쓰는 상태). 이전 중에는 채팅하지 않는다.

1. **백업(필수)** — 백엔드를 끈 상태에서
   ```bash
   # 데이터 폴더 (crack.data-path가 가리키는 곳. 기본 설정 예시는 ../data)
   cp -a data "data.backup-$(date +%Y%m%d-%H%M)"
   # DB
   pg_dump -U <사용자> -d crack -Fc -f "crack-$(date +%Y%m%d-%H%M).dump"
   ```
2. **실행** — 둘 중 하나
   - 기동 시 실행: `crack-backend/src/main/resources/application.yml`의 `crack:` 아래에 `migration: { legacy: { enabled: true } }`를 넣거나, 환경 변수 `CRACK_MIGRATION_LEGACY_ENABLED=true`로 백엔드를 띄운다. 기동 로그의 `옛 스토리 이전 결과: 이전 N, 건너뜀 N, 실패 N`과 스토리별 줄(메시지 수, 턴, 경고)을 확인한다. 끝나면 설정을 다시 끈다(켜 둬도 이미 이전한 스토리는 건너뛴다).
   - API: 서버가 떠 있는 상태에서 `curl -X POST http://localhost:8082/api/admin/migrate-legacy -H "Authorization: Bearer <로그인 토큰>"` (비밀번호를 안 쓰면 헤더 생략). 응답 JSON이 보고서다.
3. **확인** — 각 스토리 폴더(`data/{시나리오}/stories/{dir}/`)에 `story.json`, `user_note.md`, `chronicle.md`가 있고, 옛 `chat/`, `memory/`는 `legacy/` 아래에 있다. `_legacy`였던 기본 스토리는 새 `stories/{밀리초}` 폴더로 옮겨지고 시나리오 폴더의 `chat/`, `memory/`는 사라진다(원본 문서는 그대로). 화면에서 대화 내용과 턴 수를 확인한다.
4. **실패가 있으면** 보고서의 `error`를 보고 원인을 고친 뒤 다시 실행한다. 끝난 스토리는 건너뛰고 나머지를 이어서 한다.
5. **되돌리기** — 백엔드를 끄고 1의 백업으로 복원한다: 데이터 폴더를 백업본으로 바꾸고 `pg_restore -U <사용자> -d crack --clean <덤프 파일>`.
