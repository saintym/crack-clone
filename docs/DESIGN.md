# crack-clone v2 기술 설계

> 상위 문서: [Plan-roadmap.md](../Plan-roadmap.md)의 결정 사항(D1~D21). 작업 단위는 [docs/tasks/](./tasks/README.md)를 본다.
> 이 문서는 **작업 간 계약(인터페이스, 스키마, 파일 포맷)** 을 고정하는 문서다. 병렬로 작업하는 에이전트는 여기 정의된 이름과 형식을 그대로 따른다. 계약을 바꿔야 하면 코드보다 이 문서를 먼저 고치는 PR을 올린다.

---

## 1. 저장 원칙

| 데이터 | 저장소 | 이유 |
|---|---|---|
| 대화 메시지, 재생성 후보 | **DB** (`story_messages`, `message_variants`) | ID, 턴 번호, 후보, 수정 표시 같은 구조가 필요하고 중간 수정이 잦다 (D18) |
| 시나리오와 스토리 메타, 기억 기록 이력 | **DB** (`scenarios`, `stories`, `memory_records`) | 목록 조회와 상태 관리 |
| 세계관, 인물, 주인공, 연대기, 유저노트, 키워드북, 명령, 이미지 카탈로그 | **마크다운/JSON 파일** | 사람이 읽고 고치는 문서다. LLM도 통째로 읽고 쓴다 |
| 기억 기록 전 스냅샷 | **파일** (`memory/history/{recordId}/`) | 되돌리기용 |

- **스토리 격리 (D12, critical):** 플레이 중에는 스토리 폴더만 읽고 쓴다. 시나리오 원본은 스토리를 만들 때 복사하는 용도로만 쓴다.
- **경로 (BUG-005 근본 수정):** DB에는 절대 경로를 저장하지 않는다. 항상 `crack.data-path` + 시나리오 `name` + 스토리 `dir_name`으로 계산한다.

## 2. 파일 레이아웃

```
{data-path}/{scenario.name}/                 ← 시나리오 원본
  world.md
  scenario.md
  prologue.md            (선택) 첫 메시지 원본 (D16)
  characters/{이름}.md
  characters/protagonist.md
  keywords.md            (선택) 키워드북
  commands.md            (선택) 사용자 정의 / 명령
  images.md              (선택) 이미지 카탈로그 — 스토리에서 복사하지 않고 원본을 참조

{data-path}/{scenario.name}/stories/{story.dir_name}/   ← 스토리 (원본을 통째로 복사)
  story.json             { "scenarioName", "copiedAt", "formatVersion": 2 }
  world.md, scenario.md, prologue.md
  characters/{이름}.md       ← 원본 + "## 기억" 섹션
  characters/protagonist.md  ← 원본 + "## 변화 기록" 섹션
  keywords.md, commands.md
  chronicle.md           시나리오 연대기
  user_note.md           유저노트 (구 memory/must_remember.md)
  directives.json        지속 OOC 지시
  state.json             동행 인물, 위치, 시간
  memory/history/{recordId}/before/...   기록 직전 스냅샷 (변경된 파일만, 상대 경로 유지)
```

`dir_name`은 생성 시각 밀리초 문자열이다. 기존 방식과 같다.

- **옛 스토리 이전(T09):** `story.json`이 없는 스토리를 위 구조로 옮긴다. 옛 `chat/`, `memory/`는 지우지 않고 스토리 폴더의 `legacy/` 아래로 옮긴다. `_legacy` 스토리는 새 `stories/{밀리초}`로 옮기고 `dir_name`을 바꾼다. 실행은 `crack.migration.legacy.enabled=true`(기동 시) 또는 `POST /api/admin/migrate-legacy`.
- **분기(T09):** `POST /api/stories/{id}/branch` body `{title, messageId}`. 스토리 폴더를 통째로 복사하고(`memory/history`, `legacy/` 제외) 메시지는 기준 메시지의 seq까지 후보와 함께 복사한다. 과도기(T11 전)에는 `messageId` 대신 `messageIndex`(= seq)도 받는다. T12에서 `messageIndex`를 없앤다.

## 3. DB 스키마

마이그레이션 번호는 **미리 배정**한다. 병렬 작업끼리 번호가 충돌하지 않게 하기 위해서다.

| 번호 | 작업 | 내용 |
|---|---|---|
| V4 | T02 | `scenarios.data_path`, `stories.data_path` 제거 · `stories.dir_name` 추가 (기존 data_path의 마지막 경로 조각으로 채움) |
| V5 | T03 | `story_messages`, `message_variants` 생성 |
| V6 | T12 | 사용하지 않는 테이블 삭제: `chat_messages`, `story_summaries`, `character_events`, `character_states`, `scenario_settings` |
| V7 | T14 | `memory_records` 생성 · `stories.recorded_through_turn` 추가 |

테스트는 H2(`ddl-auto: create-drop`, Flyway 꺼짐)로 돈다. 엔티티와 SQL이 둘 다 맞아야 한다. SQL은 PostgreSQL 문법으로 쓴다.

```sql
-- V5
CREATE TABLE story_messages (
    id               BIGSERIAL PRIMARY KEY,
    story_id         BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    seq              INT NOT NULL,                        -- 스토리 내 순서, 0부터
    turn_no          INT NOT NULL,                        -- 0 = 프롤로그. 유저 메시지와 그 응답은 같은 턴
    role             VARCHAR(20) NOT NULL,                -- USER | ASSISTANT
    kind             VARCHAR(20) NOT NULL DEFAULT 'NORMAL', -- NORMAL | PROLOGUE | CONTINUATION | COMMAND
    content          TEXT NOT NULL,                       -- 현재 보이는 내용 (ASSISTANT는 선택된 후보의 사본). 감정 태그는 제거된 상태
    emotion          VARCHAR(100),                        -- ASSISTANT만. 응답 첫 줄 [감정: …]에서 추출 (§5.3). 화면에 노출하지 않음
    selected_variant INT,                                 -- ASSISTANT만. 0부터
    edited_at        TIMESTAMP,
    created_at       TIMESTAMP DEFAULT NOW(),
    UNIQUE (story_id, seq)
);
CREATE INDEX idx_story_messages_turn ON story_messages(story_id, turn_no);

CREATE TABLE message_variants (
    id            BIGSERIAL PRIMARY KEY,
    message_id    BIGINT NOT NULL REFERENCES story_messages(id) ON DELETE CASCADE,
    variant_index INT NOT NULL,
    content       TEXT NOT NULL,
    emotion       VARCHAR(100),
    instruction   TEXT,                                   -- 재생성 지시 (선택)
    created_at    TIMESTAMP DEFAULT NOW(),
    UNIQUE (message_id, variant_index)
);

-- V7
CREATE TABLE memory_records (
    id            BIGSERIAL PRIMARY KEY,
    story_id      BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    from_turn     INT NOT NULL,
    to_turn       INT NOT NULL,
    reason        VARCHAR(20) NOT NULL,   -- AUTO | MANUAL
    status        VARCHAR(20) NOT NULL,   -- RUNNING | DONE | FAILED | REVERTED
    changed_files TEXT,                   -- JSON 배열: 스토리 폴더 기준 상대 경로
    rerecorded_turns TEXT,                -- JSON 배열: 이번에 재반영한 수정된 과거 턴
    error         TEXT,
    created_at    TIMESTAMP DEFAULT NOW(),
    finished_at   TIMESTAMP
);
ALTER TABLE stories ADD COLUMN recorded_through_turn INT NOT NULL DEFAULT 0;
```

### 턴 규칙
- 유저 메시지는 새 턴을 연다(`turn_no = 남은 메시지의 최대 turn_no + 1`. stories.turn_count 값을 믿지 않고 매번 계산한다). 그에 대한 AI 응답은 같은 턴이다.
- **이어쓰기(CONTINUATION)** 는 유저 메시지 없이 AI 메시지만으로 새 턴을 연다.
- **프롤로그**는 `turn_no = 0`, `seq = 0`, `kind = PROLOGUE`, `role = ASSISTANT`다. 턴 수와 기억 기록 대상에서 빠진다.
- `stories.turn_count` = 남아 있는 메시지의 최대 `turn_no`. 추가하거나 잘라낼 때마다 다시 계산한다.
- 과거 메시지를 수정하면 `edited_at`을 기록한다. 기록 파이프라인은 `turn_no <= recorded_through_turn AND edited_at > 마지막 DONE 기록의 finished_at`인 턴을 재반영 대상으로 잡는다 (D3).

## 4. AI 계층 (T01)

```kotlin
enum class ModelTier { OPUS, SONNET, HAIKU }            // 기존 유지
enum class AiPurpose { CHAT, RECORD, UTILITY }          // CHAT→OPUS, RECORD→SONNET, UTILITY→HAIKU (설정으로 변경 가능)

data class AiRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val purpose: AiPurpose = AiPurpose.UTILITY,
    val maxTokens: Int? = null,
)

interface StreamListener {
    fun onDelta(text: String)
    fun onComplete(fullText: String)
    fun onError(error: Throwable)
}

interface AiProvider {
    val name: String
    fun chat(request: AiRequest): String
    fun stream(request: AiRequest, listener: StreamListener)   // 비동기. 리스너는 정확히 한 번 complete 또는 error
    fun isAvailable(): Boolean = true                           // API 키 없음 등으로 쓸 수 없으면 false (T01에서 추가)
}

@Component
class AiGateway(registry: AiProviderRegistry) {   // 앱 코드는 모두 이것만 쓴다
    fun chat(request: AiRequest, provider: String? = null): String
    fun stream(request: AiRequest, listener: StreamListener, provider: String? = null)
}
```

- 지정한 프로바이더가 없거나 사용할 수 없으면 기본 프로바이더로 대체하고 warn 로그를 남긴다. 게이트웨이는 리스너를 `SafeStreamListener`로 감싸 종료 콜백이 정확히 한 번 가도록 보장한다.
- 설정 키: `crack.ai.default-provider`, `crack.ai.purpose-tiers.*`, `crack.ai.cli.path|timeout-seconds|models.*`, `crack.ai.fake.enabled`. 옛 `crack.ai.claude-cli-path`는 쓰지 않는다.
- **SseEmitter는 AI 계층에서 없앤다.** SSE 변환은 채팅 계층(T07)이 한다.
- **모든 AI 호출은 `AiGateway`를 거친다.** `ClaudeService` 직접 호출을 금지한다. 기본 프로바이더(CLI)에서도 기억 기록이 동작해야 하기 때문이다.
- **CLI 프로바이더:**
  - 프롬프트는 **stdin**으로 넘긴다(ARG_MAX 회피).
  - `--output-format stream-json --verbose --include-partial-messages`를 쓰고, `stream_event` 안의 `content_block_delta` 텍스트를 delta로 흘린다.
  - `--model`은 tier 매핑(`crack.ai.cli.models.opus|sonnet|haiku`, 기본 `opus|sonnet|haiku`)으로 넘긴다.
  - CLI 경로는 `crack.ai.cli.path`로 받는다(하드코딩 제거). 타임아웃도 설정값으로 받는다.
- **API 프로바이더:** 모델 ID는 설정값을 쓴다. 예시 설정은 최신 ID로 갱신한다(`claude-opus-5`, `claude-sonnet-5`, `claude-haiku-4-5-20251001`). 키가 없으면 레지스트리에서 사용 불가로 표시한다.
- **FakeAiProvider (`name = "fake"`)**: `crack.ai.fake.enabled=true`일 때 등록된다. 리모트 환경과 테스트에는 CLI도 API 키도 없어서 필요하다.
  - 응답은 결정적(deterministic)이다. 목적별로 `FakeResponses`에 등록된 응답을 쓰고, 없으면 기본 문장을 쓴다.
  - 스트림은 몇 조각으로 나눠 흘린다.
  - T14는 기록용 fake 응답(§7.3 형식)을 여기에 등록한다.

## 5. 채팅 계층 (T03 저장소, T07 흐름)

### 5.1 MessageService (T03)

```kotlin
class MessageService {
    fun list(storyId: Long): List<MessageView>
    fun appendUser(storyId: Long, content: String, kind: MessageKind = NORMAL): StoryMessage
    fun appendAssistant(storyId: Long, content: String, emotion: String? = null, kind: MessageKind = NORMAL, turnNo: Int? = null): StoryMessage
    fun addVariant(messageId: Long, content: String, emotion: String?, instruction: String?): StoryMessage  // 새 후보를 추가하고 선택
    fun selectVariant(messageId: Long, index: Int): StoryMessage                          // 가장 최근 ASSISTANT만 허용
    fun edit(messageId: Long, content: String): StoryMessage   // 역할 무관. ASSISTANT면 선택된 후보 내용도 갱신
    fun truncateFrom(messageId: Long): TruncateResult          // 해당 메시지와 그 뒤를 전부 삭제. 잘린 최소 turn_no를 반환
    fun turnsInRange(storyId: Long, fromTurn: Int, toTurn: Int): List<StoryMessage>
    fun editedTurnsSince(storyId: Long, throughTurn: Int, since: LocalDateTime?): List<Int>
}
```

`truncateFrom`의 결과는 `TruncateHook` 빈들에 전달된다. T14가 이 훅으로 기억 기록을 되돌린다. T03은 인터페이스만 만든다.

### 5.2 REST / SSE API (T07)

모든 경로는 `/api/stories/{storyId}` 아래에 둔다.

| Method | Path | 설명 |
|---|---|---|
| GET | `/messages` | `{ story: {turnCount, recordedThroughTurn, generating}, messages: MessageView[] }` |
| POST | `/messages` (SSE) | body `{content, provider?, command?}`. 유저 메시지를 저장한 뒤 응답을 생성한다 |
| POST | `/messages/continue` (SSE) | body `{provider?}`. 가상 지시로 이어쓰기하며, 지시문은 저장하지 않는다 |
| POST | `/messages/regenerate` (SSE) | body `{provider?, instruction?}`. 마지막 ASSISTANT에 후보를 추가한다. 마지막이 USER면 첫 응답을 생성한다 |
| PUT | `/messages/{id}/variant` | body `{index}`. 후보 선택 (가장 최근 ASSISTANT만) |
| PATCH | `/messages/{id}` | body `{content}`. 수정 (역할 무관) |
| DELETE | `/messages/{id}` | 이 메시지부터 끝까지 삭제 |
| GET | `/messages/export` | 마크다운 내보내기 (`text/markdown`) |

`MessageView = {id, seq, turn, role, kind, content, variantIndex, variantCount, edited, createdAt}`. `emotion`은 **넣지 않는다**(§5.3).

**SSE 이벤트:** `user`(저장된 유저 MessageView JSON), `delta`(텍스트), `done`(저장된 ASSISTANT MessageView JSON), `error`(메시지)

- **서버가 저장한다.** 프로바이더의 `onComplete`에서 트랜잭션으로 저장한 뒤 `done`을 보낸다. 클라이언트가 끊겨도 저장된다. `/chat/complete`는 없앤다.
- **실패 시 원본 보존.** 재생성이 실패하면 아무것도 바뀌지 않는다(후보는 성공했을 때만 추가). 전송이 실패하면 유저 메시지는 남고, 클라이언트는 `regenerate`로 다시 시도한다.
- **스토리별 동시 생성 금지.** 생성 중이면 409를 돌려준다(`StoryGenerationLock`).
- **저장 후 훅.** ASSISTANT를 저장한 뒤 `AfterTurnHook` 빈들을 호출한다. T14가 여기에 10턴 트리거를 건다.
- 기존 `/chat/*` 경로는 T11이 프론트를 옮긴 뒤 T12에서 삭제한다. T07은 새 경로만 추가하고 기존 경로는 그대로 둔다.
- **재생성은 가장 최근 ASSISTANT 메시지만** 대상으로 한다(D17). 과거 메시지 재생성 요청은 400을 돌려준다.

### 5.3 감정 태그 (D19)
AI는 응답 첫 줄에 `[감정: 경계심, 호기심]`을 쓴다. 이 태그는 출력 품질과 이미지 선택(T20)을 돕는 **내부 신호**다. **사용자에게는 절대 보이지 않는다.**
- **스트림 필터 `EmotionTagFilter`(T07):**
  - 응답 시작부를 첫 줄바꿈까지(최대 200자) 버퍼링한다.
  - `^\[\s*감정\s*:\s*(.+?)\]\s*$` 형식에 맞으면 태그를 떼어 내고 감정 값만 기록한다. 맞지 않으면 버퍼를 그대로 흘린다.
  - 그래서 `delta` 이벤트에 태그가 절대 실리지 않는다.
- **저장:** `content`에는 태그를 뺀 본문을 넣고, `emotion` 칼럼에 감정 값을 넣는다.
- **AI 입력:** 대화 기록으로 넘기는 과거 응답은 태그가 빠진 `content`다(토큰 절약). 태그 출력 규칙은 시스템 프롬프트(BASE)가 매번 요구한다.
- **프론트:** 받은 내용을 그대로 렌더링한다. 방어용으로, 첫 줄이 감정 태그면 숨기는 처리를 한 번 더 둔다(T11).

## 6. 프롬프트 조립 (T08 격리, T13 v2)

T13 이후 `PromptAssembler`는 **섹션 기여자(contributor)** 구조를 쓴다. 뒤따르는 기능(T16 지시, T17 키워드북, T20 이미지)이 조립기 본체를 고치지 않고 빈 하나만 추가하면 되게 하려는 것이다.

```kotlin
interface PromptContributor {
    val slot: PromptSlot          // 아래 순서
    val order: Int                // 같은 slot 안의 순서
    fun contribute(ctx: PromptContext): String?   // null이면 생략
}
enum class PromptSlot { BASE, WORLD, SCENARIO, PROTAGONIST, CHARACTERS, KEYWORDS, USER_NOTE, IMAGES, BOTTOM }

data class PromptContext(
    val storyId: Long,
    val storyDir: Path,
    val recentText: String,        // 최근 N개 메시지 + 이번 입력 (키워드 매칭용)
    val userInput: String?,
    val turnInstruction: String?,  // 이어쓰기, 재생성 지시, / 명령의 이번 턴 한정 지시
)
```

**조립 결과:** `system` = BASE…IMAGES 슬롯. `messages` = 대화 원문(§6.1) + 맨 끝 유저 메시지. **BOTTOM 슬롯**(지속 OOC 지시, 이번 턴 지시)은 마지막 유저 메시지 **앞에** `[지시]` 블록으로 붙인다. 가장 강하게 반영되는 위치다.

### 6.1 대화 원문 범위
- `turn_no > recorded_through_turn - overlap` (overlap 기본 2턴)
- 기록이 계속 실패해도 폭주하지 않도록 상한을 둔다: 최근 `max-raw-turns`(기본 30턴)
- 프롤로그는 turn 0이므로 기록 전에는 포함되고, 첫 기록 이후에는 빠진다

### 6.2 활성 인물 선택 (D9)
`state.json.companions` ∪ `KeywordMatcher`가 `recentText`에서 찾은 인물(파일명과 `별칭`). 주인공은 항상 포함한다.

### 6.3 크기 측정
조립할 때마다 섹션별 글자 수를 INFO 로그로 남긴다. `GET /api/stories/{id}/prompt-preview`로 섹션별 크기와 전문을 돌려준다(포트폴리오 지표와 디버깅용).

## 7. 기억 시스템 v2 (T05 포맷, T14 파이프라인)

### 7.1 문서 포맷 (T05 `MemoryDocs` 라이브러리)

**인물 문서:** 원본 설정 부분은 LLM이 **절대 수정하지 않는다.** 파이프라인은 `## 기억` 섹션만 통째로 교체한다.

```markdown
# 캐릭터: 설월
## 기본 정보
- **이름**: 설월
- **별칭**: 월아, 설 소저            ← KeywordMatcher가 읽는 줄 (선택)
...원본 설정...

## 기억
### 관계
- 주인공: 목숨을 빚진 뒤 경계를 풀고 신뢰하기 시작함 (t21)
### 사건
- t18–21: 흑풍채 습격에서 주인공이 대신 칼을 맞음
### 소지품·기술·신체
- 옥패 (주인공에게 받음, t21)
```

- **주인공 문서:** `## 변화 기록` 섹션만 교체한다. 하위 섹션은 `### 관계`(인물별), `### 스탯·기술`, `### 소지품`, `### 신체`.
- **연대기 `chronicle.md`:**
  ```markdown
  # 연대기
  ## 장 요약
  (오래된 회차를 압축한 요약. 없으면 섹션 생략)
  ## 회차 3 (턴 21–30)
  - ...
  ```
- **`state.json`:** `{"companions": ["설월"], "location": "흑풍채 근처 숲", "time": "3일차 밤", "updatedAtTurn": 30}`
- **예산(글자 수, 설정값):** 인물 `## 기억` 3000, 주인공 `## 변화 기록` 4000, 연대기 회차 원문 12000. 키는 `crack.memory.budget.character|protagonist|chronicle`(T05에서 확정). 넘으면 파이프라인이 압축 단계를 추가로 실행한다.
- **T05 제공 API:** `readSection`, `replaceSection`(없으면 끝에 추가), `parseAliases`, `Chronicle.append/split/compactOldest`, `StoryState` 읽기·쓰기, 예산 검사.

### 7.2 파이프라인 (T14)

```
trigger(storyId, reason)
  · 싱글 플라이트: 스토리당 RUNNING은 1개. 실행 중이면 무시
  · 범위 고정: from = recorded_through + 1, to = turnCount (둘 다 트리거 시점 값)
  · 재반영: editedTurnsSince(recorded_through, 마지막 DONE.finished_at)
  · to < from 이고 재반영할 턴도 없으면 아무것도 하지 않는다

① 시나리오 관리자 (RECORD, 1회)
   입력: 연대기 최근 부분 · state.json · 인물 목록(이름과 별칭) · 대상 턴 원문 · 재반영 턴 원문
   출력: <chronicle> · <state> · <involved>
② 캐릭터 관리자 (RECORD, involved 인물마다, 병렬, 동시 실행 기본 3)
   입력: 해당 인물 문서 전체 · 대상 원문 · 기록 기준(§7.4)
   출력: <memory>(새 ## 기억 섹션 전체) · <protagonist_changes>
③ 주인공 반영 (RECORD, 1회): ②의 protagonist_changes 전부 + 현재 ## 변화 기록 → <changes>(새 섹션 전체)
④ 예산 초과 시 압축 (RECORD): 해당 섹션이나 연대기 오래된 회차만 압축
⑤ 원자적 반영: 결과를 모두 메모리에 모은 뒤 → before 스냅샷 저장 → 파일 쓰기 → DONE,
   recorded_through = to, changed_files 기록
   · 어느 단계든 실패하면 파일을 하나도 쓰지 않고 FAILED (1회 재시도 후)
```

- **트리거:** `AfterTurnHook`에서 `turnCount - recorded_through >= 10`이면 비동기 실행(AUTO). `POST /api/stories/{id}/memory/record`는 MANUAL이며 `/기록` 명령이 이것을 호출한다.
- **되돌리기:** `POST /memory/records/{id}/revert`. **가장 최근 DONE만** 되돌릴 수 있다(스택). before 스냅샷을 복원하고 `recorded_through = from - 1`, 상태는 REVERTED.
- **삭제 연동:** `TruncateHook`에서 잘린 최소 턴이 `recorded_through` 이하이면, `recorded_through < 잘린 턴`이 될 때까지 최근 기록부터 차례로 되돌린다.
- **조회:** `GET /memory/records`(목록과 상태), `GET /memory/records/{id}`(변경 파일, before 대비 현재 diff).

### 7.3 LLM 출력 형식
각 관리자는 XML 태그 블록으로 답한다. 파서는 태그가 없거나 비어 있으면 실패로 처리한다.

```
<chronicle>…마크다운…</chronicle>
<state>{"companions":[…],"location":"…","time":"…"}</state>
<involved>설월, 무극</involved>
```

### 7.4 기록 기준 (관리자 프롬프트에 그대로 넣는다)
- **기록한다:** 관계에 영향을 준 큰 사건, 감정·태도의 뚜렷한 변화, **영구적인** 소지품·기술·신체 변화
- **기록하지 않는다:** 일상 대화, 일시적 감정, 사소한 행동, 대사 원문
- 기존 항목과 같은 내용이면 합쳐서 갱신하고, 중복해서 쓰지 않는다. 턴 번호 `(tNN)`을 붙인다

## 8. 명령, 지시, 키워드북, 이미지

### 8.1 지속 OOC 지시 (T16)
- 파일: `directives.json` = `[{"id":"uuid","text":"말투는 반말","enabled":true,"createdAt":"…"}]`
- API: `GET/POST /directives`, `PATCH /directives/{id}`(text, enabled), `DELETE /directives/{id}`
- 켜진 지시는 BOTTOM 슬롯 기여자가 주입한다

### 8.2 `/` 명령 (T16)
- **시스템 명령**(즉시 실행, 메시지를 남기지 않음, REST로 처리)
  - `/기록` → 기억 기록 실행
  - `/ooc <내용>` → 지시 추가
  - `/ooc` → 지시 패널 열기(프론트 처리)
- **사용자 정의 명령**: `commands.md`에 정의한다.
  ```markdown
  ## /일기
  설명: 주인공의 하루를 일기 형식으로 정리
  프롬프트: 지금까지의 일을 주인공 시점의 일기로 써라. 이야기는 진행하지 마라.
  ```
  - 실행 흐름: `POST /messages`에 `{content: "/일기 오늘은…", command: "일기"}`를 보낸다. 유저 메시지는 `kind = COMMAND`로 저장되고, 명령 프롬프트는 이번 턴의 `turnInstruction`으로만 들어간다.
- `GET /commands`: 시스템 명령과 사용자 정의 명령 목록(자동완성용)

### 8.3 키워드북 (T17)
- **`keywords.md` 형식:**
  ```markdown
  ## 천마신교
  키워드: 천마신교, 마교, 신교
  (주입할 내용)
  ```
  위에 있는 항목이 우선한다.
- **매칭:** `KeywordMatcher`(T06)가 `recentText`에서 찾는다.
- **동시 발동 수:** 설정값 `crack.prompt.keyword-max-active`(기본 3)
- **주입 위치:** KEYWORDS 슬롯

### 8.4 KeywordMatcher (T06)
```kotlin
data class KeywordEntry(val id: String, val keys: List<String>)
class KeywordMatcher {
    fun match(entries: List<KeywordEntry>, text: String, limit: Int? = null): List<String>  // 매칭된 id, 입력 순서 유지
}
```
- 부분 문자열 매칭을 쓴다. 한국어 조사 때문이다("설월이"도 "설월"에 매칭).
- 2글자 미만 키는 무시하고, 대소문자는 구분하지 않는다.

### 8.5 이미지 카탈로그 (T20)
- **`images.md`**(시나리오 원본, 스토리에서 참조): `- 설월_미소: https://…/a.webp | 설월이 옅게 웃는 모습`
- **프롬프트:** IMAGES 슬롯에 태그 목록과 설명을 넣고, "장면에 맞으면 `{{img:태그}}`를 한 줄에 단독으로" 쓰라고 지시한다.
- **프론트:** `GET /api/stories/{id}/images`로 카탈로그를 받아 `{{img:태그}}`를 `<img>`로 바꾼다. 모르는 태그는 숨긴다.

## 9. 스토리 문서 API (T08)

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/stories/{id}/documents` | 문서 목록 `[{path, kind, size}]` (world, scenario, prologue, protagonist, characters, chronicle, user_note, keywords, commands) |
| GET | `/api/stories/{id}/documents/content?path=characters/설월.md` | 내용 |
| PUT | `/api/stories/{id}/documents/content?path=…` | 저장. body `{content}` |

- path는 화이트리스트 패턴으로만 허용한다. `..`와 절대 경로는 거부한다.
- **목록 항목 `{path, kind, size}`**(T08에서 확정)
  - `kind`는 위 이름을 그대로 쓴다. 인물 파일은 `characters`다.
  - `size`는 바이트 수다.
  - 순서는 world → scenario → prologue → protagonist → characters(이름순) → chronicle → user_note → keywords → commands이고, 실제로 있는 파일만 나온다.
- **GET·PUT content 응답**은 `{path, kind, content}`다. 없는 문서 GET은 404, PUT은 없던 문서도 만든다(새 인물 추가 가능).
- `_legacy` 스토리(T09 이전 전)는 읽기만 되고 PUT은 400이다. 폴더가 곧 시나리오 원본이기 때문이다.
- 스토리 폴더는 `StoryDirs.locate(storyId).dir`로 찾는다. 복사 로직은 `StoryFiles.initFromScenario`, 메타는 `StoryMeta`.
- 테스트 픽스처는 `src/test/resources/fixtures/sample-scenario/`(설월·무극·주인공)이고, `SampleScenario.copyTo(dir)`로 쓴다.
- 시나리오 원본 편집 API(`/api/scenarios/{name}/documents`)는 그대로 두고, `prologue`, `keywords`, `commands`, `images` 타입을 추가한다.

## 10. 프론트엔드 구조 (T04 이후)

```
src/pages/ChatPage.tsx              조립만 담당 (T04 완료 시 100줄)
src/hooks/useChatStream.ts          SSE 전송, 재생성, 이어쓰기
src/hooks/useMessages.ts            메시지 목록 상태
src/hooks/useProviders.ts           프로바이더 목록과 선택
src/hooks/useStoryContext.ts        현재 스토리, 시나리오, 사이드바 목록
src/types/chat.ts, types/panel.ts   공용 타입
src/components/chat/ChatHeader.tsx
src/components/chat/MessageList.tsx
src/components/chat/ChatBubble.tsx  마크다운 렌더링 (T20에서 이미지 태그 처리)
src/components/chat/MessageMenu.tsx 수정, 삭제, 분기, 재생성, 이어쓰기
src/components/chat/BranchDialog.tsx 분기 제목 입력 하단 시트
src/components/chat/ChatInput.tsx   (T18에서 / 자동완성)
src/components/chat/StorySidebar.tsx
src/components/panels/SidePanel.tsx 탭을 props로 받는 드로어 (PC 오른쪽, 모바일 하단 시트, 배경을 덮지 않음)
src/components/panels/…             T15 기억 패널, T18 지시 패널, T19 상태 패널
```

패널은 채팅 화면 오른쪽 드로어(모바일은 하단 시트)에 탭으로 모은다. **플레이 흐름을 가리는 모달은 쓰지 않는다** (D7).
