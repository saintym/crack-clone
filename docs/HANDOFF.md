# 인수인계 (2026-09-23)

> 로컬에서 v2 구현(T00~T21)을 모두 마치고 `main`에 머지·push한 시점의 인수인계 문서다. **이후 작업은 리모트 세션(Claude Code on the web)에서 이어서 한다.**
> 문서 지도: 결정 사항 [Plan-roadmap.md](../Plan-roadmap.md) · 계약 [DESIGN.md](./DESIGN.md) · 작업 명세와 로그 [tasks/](./tasks/README.md) · 병렬 운영 [PARALLEL.md](./PARALLEL.md) · 오류 기록 [ORCHESTRATION-LOG.md](./ORCHESTRATION-LOG.md) · 버그 [bugs/](../bugs/README.md) · 에이전트 규칙 [CLAUDE.md](../CLAUDE.md)

---

## 1. 현재 상태

- `main` = `origin/main`, 작업 트리 깨끗함. 작업 21개 + 운영 중 추가 1개(T21) 전부 머지됨(작업당 머지 커밋 1개).
- `docs/tasks/T00`~`T21` 상태는 모두 `DONE`. **계획된 남은 작업은 없다.**

| 항목 | 값 |
|---|---|
| 백엔드 테스트 | 415개 전부 통과 (`./gradlew test`) |
| 프론트 | `npm run build` 통과, `npm run lint` 오류 0, 메인 번들 약 294kB(패널은 lazy) |
| DB | Flyway V1~V7. 실제 PostgreSQL 14에서 적용과 `ddl-auto: validate` 통과 |
| 프롬프트 크기 | 마도생존기 기준 192KB → 56~79KB (−59~71%) |
| 버그 | BUG-001~011 중 10건 수정, BUG-007(낮음) 보류 |

### 구현된 기능과 위치
| 기능 | 백엔드 | 프론트 |
|---|---|---|
| 대화(전송·SSE·재생성 후보·수정·삭제·이어쓰기) | `chat/api`, `chat/flow`, `message/**` | `api/chat.ts`, `hooks/useChatStream.ts`, `components/chat/**` |
| 감정 태그 숨김 | `chat/flow/EmotionTagFilter` | `components/chat/emotionTag.ts` |
| 기억 기록(3계층 문서, 10턴·`/기록`, 되돌리기) | `memory/record/**`, `memory/docs/**` | `components/panels/memory/**` |
| 프롬프트 조립 v2(기여자, 활성 인물, 원문 범위) | `prompt/**` | — |
| 지속 OOC 지시 · `/` 명령 | `directive/**`, `command/**` | `components/panels/directives/**`, `components/chat/CommandPalette.tsx` |
| 키워드북 | `prompt/keyword/**` | 문서 편집으로 관리 |
| 인물 상태 패널 | `status/**` | `components/panels/status/**` |
| 이미지 카탈로그 | `image/**` | `hooks/useImageCatalog.ts`, `ChatBubble` |
| 스토리 격리·문서 API | `story/files/**`, `document/story/**` | `api/storyDocuments.ts` |
| 프롤로그 | `story/prologue/**` | `ScenarioDetailPage` "첫 메시지" 탭 |
| 옛 데이터 이전 | `migration/**` | — |

---

## 2. 사용자가 직접 해야 할 일 (리모트 작업 전에)

### 2.1 로컬 설정 갱신 (필수)
`crack-backend/src/main/resources/application.yml`은 gitignore 대상이라 자동으로 갱신되지 않는다. [application.yml.example](../crack-backend/src/main/resources/application.yml.example)을 보고 아래를 채운다.
- `crack.ai.cli.path` — `claude`가 PATH에 없으면 절대 경로. **옛 키 `crack.ai.claude-cli-path`는 더 이상 읽지 않는다.**
- `crack.ai.purpose-tiers`, `crack.ai.cli.models`, `crack.ai.fake.enabled`
- `claude.model` / `opus-model` / `haiku-model` — 최신 ID(`claude-sonnet-5`, `claude-opus-5`, `claude-haiku-4-5-20251001`)
- `crack.memory.budget.*`, `crack.memory.record.*`, `crack.prompt.*`, `crack.image.*`
- `spring.jpa.open-in-view: false` 유지(생성 중 DB 커넥션을 잡고 있지 않도록)

### 2.2 기존 데이터 이전 (T09, 1회)
**이전하기 전까지 옛 기본 스토리(`_legacy`)는 시나리오 원본 폴더를 직접 읽고 쓴다.** 즉 스토리 격리가 적용되지 않는다.
```bash
# 백엔드를 끈 상태에서 백업
cp -a data "data.backup-$(date +%Y%m%d%H%M)"
pg_dump -Fc -d crack -f "crack-$(date +%Y%m%d%H%M).dump"
```
그다음 `crack.migration.legacy.enabled=true`로 기동하거나 `POST /api/admin/migrate-legacy`를 호출한다. 멱등하므로 실패 시 원인을 고쳐 다시 실행하면 된다. 자세한 절차와 되돌리기는 `docs/tasks/T09-legacy-migration.md` 작업 로그에 있다.

### 2.3 실제 AI로 확인 (품질 튜닝, 가장 중요)
Fake 프로바이더로는 검증할 수 없는 부분이다.
1. **CLI 출력 파서** — `ai/provider/ClaudeCodeCliProvider`와 `CliStreamJsonParser`. 테스트 픽스처는 실제 캡처가 아니라 바이너리 문자열을 보고 **손으로 쓴 것**이다. 실제 `claude` CLI로 스트리밍이 토큰 단위로 오는지, 중복 출력이 없는지 확인한다.
2. **기억 관리자 프롬프트** — `memory/record/RecordPrompts.kt`(시나리오/캐릭터/주인공/압축). 실제로 10턴씩 몇 회차 돌려 기록 품질(무엇을 남기고 무엇을 버리는지)을 보고 문구를 다듬는다. **출력 태그 이름을 바꾸면 `RecordOutputParser`와 Fake 응답도 함께 고쳐야 한다.**
3. 본 응답 품질(`prompt/contributor/BaseContributor`의 규칙, 분량, 문체)도 실제 모델로 보며 조정한다.

### 2.4 브라우저로 훑어보기
에이전트가 headless Chromium으로 확인했지만 실제 사용감은 직접 봐야 한다. 채팅(전송·재생성 후보·수정·이어쓰기), 오른쪽 패널 3탭(기억·지시·상태), `/` 명령, 시나리오 편집 탭(첫 메시지·키워드북·명령·이미지), 모바일 폭.

### 2.5 BUG-005 잔여 정리
`crack-backend/data/테스트세계`와 `data/테스트세계`의 `chat/chat_latest.md` 내용이 다르다. 어느 쪽이 최신인지 확인하고 정리한다(에이전트는 사용자 데이터라 손대지 않았다).

---

## 3. 리모트 세션에서 이어서 하는 방법

### 3.1 기본 규칙
1. 리모트 세션에서 이 저장소를 열면 기본 브랜치는 `main`이다. 첫 지시로 **작업 파일 경로를 알려주는 것**이 가장 잘 동작했다: 예) "`docs/tasks/T22-*.md` 대로 진행해".
2. 에이전트는 `CLAUDE.md`를 먼저 읽는다. 여기에 환경 제약(CLI·API 키·PG·실데이터 없음), 반복된 함정, 커밋·PR 규칙이 정리돼 있다.
3. 리모트에는 Claude CLI도 API 키도 없다. **AI가 필요한 검증은 `crack.ai.fake.enabled=true` + `default-provider=fake`로 한다.**
4. 상태는 자기 작업 파일의 `- **상태**:` 줄에만 적는다. `DONE`은 머지한 사람이 `main`에서 바꾼다.
5. 여러 작업을 동시에 돌릴 때는 [PARALLEL.md](./PARALLEL.md)의 범위 분리와 머지 순서를 따른다. 로컬 병렬은 `scripts/task-worktree.sh`를 쓴다.

### 3.2 새 작업을 추가하는 절차
1. `docs/tasks/T22-<슬러그>.md`를 만든다. 기존 파일과 같은 머리말을 쓴다.
   ```markdown
   - **상태**: TODO
   - **웨이브**: 7
   - **의존**: 없음
   - **브랜치**: `task/T22-<슬러그>`
   - **마이그레이션**: 없음   ← DB 변경이 필요하면 **V8부터** 사용
   ```
   그리고 `## 목표 / ## 범위 (수정 가능한 파일) / ## 구현 내용 / ## 완료 조건 / ## 작업 로그`를 채운다. 범위는 **파일 단위로** 좁게 적는 것이 병렬 작업에서 가장 효과가 컸다.
2. `docs/tasks/README.md` 표에 한 줄 추가한다(상태는 적지 않는다).
3. 계약(스키마·API·파일 포맷)이 바뀌면 `DESIGN.md`를 **먼저** 고치는 커밋을 둔다.
4. 다 끝나면 `main`에 `--no-ff`로 머지하고, 작업 파일 상태를 `DONE`으로 바꿔 push한다.

### 3.3 우선순위 후보 (다음에 할 만한 것)
| 순위 | 항목 | 이유 |
|---|---|---|
| 1 | **실제 AI 기반 프롬프트 튜닝**(§2.3) | 기능은 다 있고, 품질만 미검증이다. 코드 작업이 아니라 사용자 확인이 필요하다 |
| 2 | **PG 테스트 인프라**(Testcontainers) | BUG-008은 H2 테스트 400여 개를 통과하고도 살아 있었다. T21의 정적 가드는 한 유형만 막는다 |
| 3 | **프론트 테스트 러너**(Vitest) | 순수 함수(SSE 파서, 감정 태그, 이미지 태그, URL 검사)를 esbuild+node로 임시 확인만 했다 |
| 4 | BUG-007(후보별 "수정됨" 표시) | `message_variants.edited_at` 추가가 필요하다(V8) |
| 5 | 플레이키 테스트 `StoryMessageApiTest` | MockMvc 헤더 출력과 비동기 스레드의 경쟁(`ConcurrentModificationException`) |
| 6 | Fake 프로바이더 echo 모드 | 입력이 응답에 반영되는지 프론트에서 검증할 수 있게 된다 |
| 7 | 미결정 기능: 추천 답변, 시작 설정 여러 개 | 로드맵 §5. 도입 여부부터 결정해야 한다 |
| 8 | 보류: 분기 트리 뷰(D4) | 현재는 메시지 시점 분기만 있고 확장하지 않았다 |

---

## 4. 알아 두어야 할 제약과 함정

### 설계상 의도된 동작
- **스토리 격리(critical):** 플레이 중에는 스토리 폴더만 읽고 쓴다. 시나리오 원본을 고쳐도 기존 스토리에는 반영되지 않는다(새 스토리에만). 예외는 `images.md` 하나로, 시나리오 원본을 읽기 전용 참조한다.
- **감정 태그:** AI는 첫 줄에 `[감정: …]`을 쓰지만 서버가 떼어 `emotion` 칼럼에 저장한다. 사용자와 이후 AI 입력에는 보이지 않는다.
- **기억은 배경 처리:** 플레이를 끊는 모달·승인·diff 팝업을 만들지 않는다. 채팅 화면에는 작은 뱃지만 둔다.
- **매 턴 응답 전에는 LLM을 부르지 않는다.** 활성 인물은 동행 목록과 키워드 매칭으로 고른다.
- **상태 패널은 기억 기록 때만 갱신**한다(매 턴 조회 없음).

### 반복해서 걸린 함정 (CLAUDE.md에도 있음)
- KDoc/주석에 `/*`가 들어간 문자열(`characters/*.md`)을 쓰면 Kotlin 컴파일이 깨진다.
- MockMvc 테스트는 로컬 `application.yml` 유무로 인증 동작이 달라진다 → `@AutoConfigureMockMvc(addFilters = false)` 또는 `crack.auth.password=`로 고정.
- SSE 테스트는 `asyncDispatch(result)`까지 실행해야 DB 커넥션 풀이 마르지 않는다.
- Mockito로 Kotlin 기본 인자 함수를 스텁할 때는 매처 개수를 맞춘다(`chat(any(), anyOrNull())`).
- React Hooks lint v7은 effect가 부르는 async 함수의 `await` 뒤 setState도 오류로 본다 → `.then` 체인이나 effect 안 IIFE.
- 템플릿(`data/_templates/*.md`)을 추가하면 "템플릿 파싱 결과가 빈 값"인지 검사하는 테스트를 붙인다(안내 문구가 데이터로 읽히는 문제가 두 번 났다).
- JPQL에 `:param IS NULL` 패턴을 쓰면 PostgreSQL에서 타입 추론이 실패한다(BUG-008). 쿼리를 분리한다. 정적 가드 테스트 `EditedTurnsQueryTest`가 막아 준다.
- 브라우저 확인 없이는 CSS 레이어 문제(BUG-010)와 CORS 문제(BUG-011)를 잡을 수 없다.

### 아직 확인되지 않은 것
- 실제 AI 프로바이더(CLI/API)로 돌린 적이 없다: 스트리밍 파서, 기억 프롬프트 품질.
- PG에서 확인 못 한 흐름: 생성 중 409(동시성), 기록 도중 삭제로 RUNNING 취소, 재시작 시 RUNNING→FAILED 전환.
- 안전 영역(`safe-*`) 여백은 `viewport-fit=cover`를 도입하면 다시 설계해야 한다.

---

## 5. 명령 요약

```bash
# 테스트 · 빌드
cd crack-backend && ./gradlew test
cd crack-frontend && npm ci && npm run build && npm run lint

# 로컬 실행 (PostgreSQL 필요)
cd crack-backend && ./gradlew bootRun          # :8082
cd crack-frontend && npm run dev               # :5173 (CRACK_WEB_PORT / CRACK_API_TARGET로 변경 가능)

# AI 없이 띄우기 (Fake 프로바이더)
cd crack-backend && ./gradlew bootRun --args='--crack.ai.fake.enabled=true --crack.ai.default-provider=fake'

# 병렬 작업용 worktree
scripts/task-worktree.sh create T22    # 작업 파일의 브랜치로 ../crack-clone-wt/T22 생성
scripts/task-worktree.sh status        # 작업별 상태
scripts/task-worktree.sh remove T22
```

**주의:** 수동 확인용으로 앱을 띄울 때 worktree에는 사용자 `crack` DB를 가리키는 `application.yml`이 복사되어 있다. 반드시 datasource와 `crack.data-path`를 명령줄 인자로 덮어써서 실데이터와 사용자 DB를 건드리지 않게 한다(PARALLEL.md §5.2).
