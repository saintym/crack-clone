# 인수인계 (갱신 2026-09-29)

이 문서부터 읽는다. 그다음 [docs/DESIGN.md](./DESIGN.md)(작업 간 계약)와 [Plan-roadmap.md](../Plan-roadmap.md)(결정 D1~D43)를 본다.

> **이전 판(2026-09-23)에 적혀 있던 사용자 할 일은 전부 끝났다.** 로컬 설정, 데이터 이전, 실제 AI 검증, 브라우저 확인, 외부 공개까지 완료됐다. 지금은 **사용자가 실제로 플레이하면서 나온 문제를 고치는 단계**다.

---

## 1. 현재 상태

### 작업 보드
| | 개수 | 목록 |
|---|---|---|
| `DONE` | 42 | T00~T23, T27~T37, T39~T44 |
| `TODO` | 3 | **T24**(세계관 다이제스트·색인), **T25**(깊은 질의 폴백), 그리고 V2 |
| V2 (`TODO`) | 2 | **T38**(스토리 시계) → **T26**(능동적 세계 에이전트). 순서를 지킨다 |

전체 상태는 `grep -H '^- \*\*상태\*\*' docs/tasks/T*.md`로 본다.

### 기준선
- 백엔드 테스트 **592개 통과** (`cd crack-backend && ./gradlew test`)
- 프론트 **build 통과, lint 오류 0** (`cd crack-frontend && npm ci && npm run build && npm run lint`)
- 마이그레이션 V1~V8 적용됨. **다음 번호는 V9이고 T38에 배정돼 있다**

### 실측 지표 (포트폴리오용)
| 항목 | 값 | 조건 |
|---|---|---|
| 프롬프트 크기 | 15,761자 | 무림천하, 활성 인물 3명, 이미지 475개 등록 상태 |
| 응답 분량 | 평균 994자 | D33 적용 후. 적용 전 평균 1,339자(최대 2,032) |
| 한 턴 소요 | 19~21초 | 실제 CLI, opus |
| 인물 문서 비용 | 1명당 약 1,460자 | 활성 인물 상한(6명)을 둔 근거 |
| 테스트 | 109개 → 592개 | v2 시작 시점 대비 |

### 실행 중인 것 (사용자 로컬)
- Docker `crack-postgres` (PostgreSQL)
- 백엔드 `:8082` — **프론트 빌드까지 서빙한다**(`--crack.web.dist-path`). 실제 Claude CLI 프로바이더
- `cloudflared` 빠른 터널 — 외부 접속용 HTTPS. **끌 때마다 주소가 바뀐다**
- 로컬 개발용 Vite `:5173`은 필요할 때만 띄운다

---

## 2. 최근에 무엇을 했나 (2026-09-26 ~ 29)

리모트 병렬 작업(T00~T23)이 끝난 뒤, **사용자가 직접 플레이하며 지적한 것**을 고치는 흐름이었다. 이 구간의 작업은 대부분 **실제 플레이에서만 드러나는 문제**였다.

| 작업 | 무엇 | 왜 여기서만 드러났나 |
|---|---|---|
| T27·T30 | 인물 이미지 자동 삽입 → **대사 바로 앞 인라인 배치**(D31·D34) | 맨 위에 한 장만 뜨면 "이미지가 먼저 나오고 그 아래 소설이 흐르는" 모양이 된다 |
| T29 | 응답 분량 800~1,500자(D33) | 하한만 있던 프롬프트가 턴마다 응답을 길게 만들었다 |
| T31·T32 | **소설형 읽기 모드**(기본), 전송은 버튼만, 턴 구분선(D35·D36) | 한 턴이 1,000자 산문인데 채팅 말풍선에 갇혀 있었다 |
| T33 | 태그 앞 메타 텍스트 회수(BUG-025) | 20턴 중 1회, 모델이 영어 메타 문장을 먼저 뱉었다 |
| T34 | **외부 공개** — 정적 서빙, 로그인 대입 제한, 비밀값 교체, 터널(D37) | 인터넷에 여는 순간 인증 기준이 달라졌다 |
| T35 | **인지 범위** — `(비공개)` 섹션, `## 알고 있는 것`, 인지 규칙(D38) | 인물이 프롬프트에 있다는 이유로 주인공의 비밀을 다 알았다 |
| T36·T37 | 좁은 화면 메뉴 잘림, 터치 기기 선명도 | 핸드폰에서만 드러났다 |
| T39 | **설정 변경 신호**(D41) | 문서를 고쳐도 AI가 "바뀐 사실"을 몰라 옛 대화에 끌려갔다 |
| T40~T44 | 이미지 상황 변형, 일괄 등록, 예산 단위와 상한(D42·D43) | 이미지가 25개 → 475개가 되자 프롬프트에서 잘렸다 |

**되돌아보면 패턴이 하나 있다.** H2·Fake 프로바이더로 통과한 기능이 실제 PostgreSQL·실제 AI·실제 기기에서 계속 깨졌다. 자동 테스트가 못 잡는 층이 있다는 뜻이고, 이 구간의 버그 대부분이 그 층에서 나왔다.

---

## 3. 리모트 세션에서 이어서 하는 방법

### 3.1 기본 규칙
[CLAUDE.md](../CLAUDE.md)가 규칙의 기준이다. 요약하면:
1. 지정된 작업 파일 하나만 보고 일한다. **범위 밖 파일은 고치지 않는다**
2. 계약을 바꿔야 하면 **`docs/DESIGN.md`를 코드보다 먼저** 고친다
3. 상태는 **자기 작업 파일에서만** 바꾼다. `DONE`은 머지한 사람이 main에서 바꾼다
4. 작업 로그에 **한 일 · 설계 판단과 이유 · 확인 방법 · 다음 사람이 알아야 할 것**을 남긴다
5. **비밀값을 파일에 적지 않는다.** 명세·문서·로그에도 안 된다(2026-09-26에 실제로 사고가 있었다)

### 3.2 리모트 환경의 제약
- **Claude CLI와 API 키가 없다.** `FakeAiProvider`로 테스트한다. 실제 AI 호출에 의존하는 테스트를 만들지 않는다
- **PostgreSQL이 없다.** H2(`application-test.yml`, Flyway 꺼짐)로 돈다. `src/main/resources/application.yml`은 gitignore라 **리모트에 없다**
- **실제 시나리오 데이터가 없다.** `src/test/resources/fixtures/sample-scenario/`를 쓴다
- 마이그레이션은 PostgreSQL 문법으로 쓴다. **V9는 T38에 배정돼 있다**

### 3.3 새 작업을 추가하는 절차
1. `docs/tasks/T45-*.md`를 만든다(번호는 기존 최대 + 1). 기존 파일을 형식 참고로 쓴다
2. 계약이 바뀌면 `docs/DESIGN.md`를 먼저 고친다. 결정이면 `Plan-roadmap.md`에 `D44`를 더한다
3. `docs/tasks/README.md`의 웨이브 표에 한 줄 더한다
4. 브랜치 `task/T45-…`에서 작업하고 PR을 올린다

### 3.4 다음에 할 만한 것 (우선순위 순)
1. **T24 세계관 다이제스트 + 색인** — 프롬프트에서 남은 큰 덩이는 `world`(1,986자)와 `characters`(활성 3명에 4,383자)다. 인물 문서를 줄이면 **활성 인물 상한 6명을 올릴 여지**가 생긴다
2. **T38 스토리 시계** — V2의 선행 과제지만 **단독으로 쓸모가 있다.** 지금 플레이하면 "사흘 뒤"인지 "그 자리에서 이어지는지"가 애매한 턴이 있다
3. **BUG-007** — 변형별 "수정됨" 표시. V9가 필요하지만 T38이 V9를 쓰므로 **T38 뒤에 V10으로** 해야 한다
4. **BUG-022** — 기억 기록 주기 "10턴" 문구가 화면에 하드코딩돼 있다. 설정값을 읽게 한다
5. **프론트 테스트가 없다** — Vitest가 없어서 `emotionTag.ts`, `imageTags.ts` 같은 순수 함수도 검증이 안 된다. 실제로 T36(메뉴 잘림)은 테스트로 잡을 수 있는 종류였다

---

## 4. 알아 두어야 할 제약과 함정

### 설계상 의도된 동작 (버그로 오해하기 쉽다)
- **스토리 격리(D12).** 스토리를 만들 때 시나리오 원본을 통째로 복사한다. **원본을 고쳐도 기존 스토리에는 반영되지 않는다.** 예외는 `images.md` 하나다(원본을 참조한다)
- **`user_note.md`는 스토리마다 빈 파일로 새로 만들어진다.** 시나리오에서 복사되지 않는다. 시나리오 단위 규칙은 `scenario.md`나 `world.md`에 둔다
- **이미지 목록은 "필요할 때 꺼내 쓰는" 방식이 아니다.** AI가 고를 수 있는 변형을 알아야 하므로 목록이 매 턴 들어간다. 좁히는 것은 **활성 인물 선택**이다
- **재생성은 가장 최근 AI 응답만** 가능하다. 후보가 쌓이고 `‹ n/m ›`로 넘긴다
- **첫 줄 `[감정: …]` 값은 아무것도 쓰지 않는다.** DB에만 저장된다. 이미지는 `[인물: …]`(폴백)과 본문 `{{img:}}`(주 경로)가 고른다

### 반복해서 걸린 함정
- **한 글자 인물 이름은 키워드로 잡히지 않는다.** `KeywordMatcher.MIN_KEY_LENGTH`가 2다. 파일명이나 별칭을 두 글자 이상으로 둔다 (T44에서 테스트가 이것 때문에 죽었다)
- **KDoc·주석에 `/*` 문자열**(`characters/*.md`)을 쓰면 Kotlin이 중첩 주석으로 읽어 컴파일이 깨진다
- **MockMvc 테스트**는 `@AutoConfigureMockMvc(addFilters = false)` 또는 `crack.auth.password=`로 고정한다
- **SSE 테스트**는 `asyncDispatch(result)`까지 실행해 요청을 끝낸다(안 하면 DB 커넥션 풀 고갈)
- **프론트 `npm ci`**는 `.npmrc`(legacy-peer-deps)가 있어야 된다. React Hooks lint(v7)는 effect 안 async 함수의 `await` 뒤 setState도 오류로 본다
- **PostgreSQL에서 `:param IS NULL` 형태의 JPQL은 깨진다**(BUG-008). 정적 가드 테스트가 이것을 막는다

### 사용자 환경에서만 걸리는 것
- **프론트를 고치면 `npm run build`를 다시 해야 외부에 반영된다.** 백엔드가 `dist`를 서빙하므로 Vite처럼 자동 반영이 아니다
- **`application.yml`은 jar에 박힌다.** 설정을 고치면 `./gradlew bootJar` → 재시작이 필요하다
- **빠른 터널 주소는 끌 때마다 바뀐다.** 고정 주소가 필요하면 Cloudflare 계정 + 도메인으로 명명 터널을 만든다

### 아직 확인되지 않은 것
- 기억 문서 **압축 단계**가 실제로 도는 모습(예산 3,000자를 넘길 만큼 길게 플레이해야 한다)
- **화자 재등장 시 이미지가 다시 붙는지**(T30). 모델이 한 인물 대사를 한 덩이로 모아 써서 실측으로 재현하지 못했다
- **"지시하고 재생성"이 사용자 화면에서 어떻게 동작했는지.** 서버는 정상(후보가 붙고 지시를 따른다)이고 코드 경로도 맞아 재현하지 못했다. 사용자가 본 상황을 들어야 한다
- 오래 플레이했을 때 기억력이 체감상 좋아졌는지
- 터널을 오래 띄웠을 때 끊김·재연결 동작

---

## 5. 명령 요약

```bash
# 테스트 · 빌드 (반드시 통과)
cd crack-backend && ./gradlew test
cd crack-frontend && npm ci && npm run build && npm run lint

# 작업 상태 한눈에
grep -H '^- \*\*상태\*\*' docs/tasks/T*.md

# 로컬 실행 (PostgreSQL 필요)
cd crack-backend && ./gradlew bootRun
cd crack-frontend && npm run dev          # 개발용 :5173

# 외부 공개로 띄우기 (프론트까지 백엔드가 서빙)
cd crack-frontend && npm run build
cd ../crack-backend && ./gradlew bootJar
java -jar build/libs/crack-backend-0.0.1-SNAPSHOT.jar \
  --crack.data-path=<저장소>/data \
  --crack.web.dist-path=<저장소>/crack-frontend/dist
cloudflared tunnel --url http://localhost:8082 --no-autoupdate

# AI 없이 띄우기 (Fake 프로바이더)
CRACK_AI_FAKE=true CRACK_AI_PROVIDER=fake ./gradlew bootRun

# 프롬프트 크기·내용 확인 (LLM 호출 없음, 공짜)
curl -s "localhost:8082/api/stories/{id}/prompt-preview" -H "Authorization: Bearer <토큰>"

# 병렬 작업용 worktree
scripts/task-worktree.sh create T45 && scripts/task-worktree.sh status
```

---

## 6. 문서 지도

| 문서 | 무엇 |
|---|---|
| [CLAUDE.md](../CLAUDE.md) | 에이전트 작업 규칙. **비밀값 금지 규칙 포함** |
| [docs/DESIGN.md](./DESIGN.md) | 작업 간 계약. 스키마·API·파일 포맷·프롬프트 구조 |
| [Plan-roadmap.md](../Plan-roadmap.md) | 결정 D1~D43과 그 이유. 마일스톤 M0~M6 |
| [docs/tasks/](./tasks/) | 작업 명세와 로그. 상태는 각 파일에만 |
| [docs/PARALLEL.md](./PARALLEL.md) | 병렬 운영, worktree, 머지 순서 |
| [docs/ORCHESTRATION-LOG.md](./ORCHESTRATION-LOG.md) | 진행 중 겪은 오류와 결정 |
| [bugs/](../bugs/) | 버그 기록. BUG-007·BUG-022가 미수정 |
| [docs/journal/](./journal/) | 개발기(포트폴리오용). 11편 + INDEX + SERIES |
| [Plan-crack-gap.md](../Plan-crack-gap.md) | 원작 크랙 기능 조사와 사용자 피드백 |
