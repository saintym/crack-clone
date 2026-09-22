# crack-clone — 에이전트 작업 가이드

AI 캐릭터 채팅 앱 "크랙(Crack)"을 클론하는 **개인용 취미 사이트이자 포트폴리오 프로젝트**다. 핵심 가치는 **긴 기억력**과 **OOC 지시 유지**다.

이 저장소의 작업은 리모트 에이전트(Claude Code on the web)가 **작업 단위로 병렬 진행**한다. 사용자가 세션마다 작업 ID를 지정한다(예: "T07 진행해").

## 먼저 읽을 문서 (이 순서로)
1. 지정된 작업 파일 `docs/tasks/T??-*.md`: 범위, 구현 내용, 완료 조건
2. `docs/DESIGN.md`: 작업 간 계약(스키마, API, 파일 포맷, 인터페이스). **여기 적힌 이름과 형식을 그대로 쓴다**
3. `Plan-roadmap.md`: 결정 사항 D1~D21과 그 이유
4. 필요할 때만: `docs/tasks/README.md`(의존 관계), `docs/PARALLEL.md`(병렬 운영과 머지 방식), `Plan-crack-gap.md`(크랙 기능 조사)

`Plan-crack.md`와 `Plan-features.md`는 v1 기획 문서다. 현재 기준이 아니다.

## 구조
- `crack-backend/`: Spring Boot 3.4 + Kotlin 1.9, JDK 17, PostgreSQL(Flyway), 테스트는 H2
- `crack-frontend/`: React 19 + TypeScript + Vite + Tailwind v4
- `data/`: 시나리오 데이터. **git에는 `data/_templates/`만 있다.** 사용자의 실제 데이터는 로컬에만 있다
- `docs/`: 설계와 작업 보드 · `scripts/task-worktree.sh`: 로컬 병렬 작업용 worktree 관리 · `bugs/`: 버그 기록 · `blog/`: 개발기(포트폴리오용)

## 명령어
```bash
# 백엔드 테스트 (반드시 통과)
cd crack-backend && ./gradlew test

# 프론트 (반드시 통과)
cd crack-frontend && npm ci && npm run build && npm run lint
```

> **기준선:** 백엔드 테스트 전부 통과. 프론트 build 통과. lint 오류 0 (T00·T04로 복구).
> **백엔드 주의:**
> - KDoc/주석에 `/*` 문자열(예: `characters/*.md`, `/chat/*`)을 쓰면 Kotlin이 중첩 주석으로 읽어 컴파일이 깨진다.
> - MockMvc 테스트는 로컬 설정에 따라 AuthFilter가 끼므로 `@AutoConfigureMockMvc(addFilters = false)` 또는 `crack.auth.password=`로 고정한다.
> - SSE 테스트는 `asyncDispatch(result)`까지 실행해 요청을 끝낸다(안 하면 DB 커넥션 풀 고갈).
> - Mockito로 Kotlin 기본 인자 함수를 스텁할 때는 매처 개수를 맞춘다(`chat(any(), anyOrNull())`).
> - 설정 파일을 잠시 치울 때는 작업 공간 밖이 아니라 스크래치 경로에 둔다.
> **프론트 주의:** `.npmrc`(legacy-peer-deps)가 있어야 `npm ci`가 된다. React Hooks lint(v7)는 effect 안에서 부르는 async 함수의 `await` 뒤 setState도 오류로 본다. 로드 함수는 `.then` 체인이나 effect 안의 IIFE로 작성한다(T00 작업 로그 참고).

## 리모트 환경의 제약과 대응
- **Claude CLI와 API 키가 없다.** AI는 `FakeAiProvider`(T01 이후, `crack.ai.fake.enabled=true`)로 테스트한다. 실제 AI 호출에 의존하는 테스트는 만들지 않는다.
- **PostgreSQL이 없다.** 테스트는 H2(`src/test/resources/application-test.yml`, Flyway 꺼짐)로 돈다. Spring 컨텍스트가 필요한 통합 테스트는 `@SpringBootTest` + `@ActiveProfiles("test")`를 쓴다.
  - `src/main/resources/application.yml`은 gitignore되어 리모트에는 **없다.** 테스트 설정은 `application-test.yml`만으로 완결되어야 한다.
- **실제 시나리오 데이터가 없다.** 테스트는 임시 디렉터리나 `src/test/resources/fixtures/`(T08이 `sample-scenario` 생성)를 쓴다.
- 마이그레이션 SQL은 PostgreSQL 문법으로 쓴다. 번호는 미리 배정되어 있다(V4=T02, V5=T03, V6=T12, V7=T14). 다른 번호를 만들지 않는다.

## 작업 규칙
1. **의존 작업이 main에 머지(`DONE`)된 뒤에 시작한다.** 최신 main에서 작업 파일에 적힌 브랜치 이름으로 브랜치를 딴다.
2. **범위를 지킨다.** 작업 파일의 "범위"에 없는 파일은 고치지 않는다. 꼭 필요하면 최소한으로 고치고, 이유를 작업 로그와 PR에 적는다. 병렬 작업과의 충돌을 막기 위해서다.
3. **계약을 바꿔야 하면** 코드보다 `docs/DESIGN.md`를 먼저 고친다. 바뀐 내용을 PR 설명 맨 위에 적는다.
4. **상태 갱신은 자기 작업 파일에서만 한다.**
   - 시작할 때: `- **상태**: IN_PROGRESS` + 작업 로그에 시작 기록
   - PR을 올릴 때: `REVIEW`
   - 막혔을 때: `BLOCKED` + 이유
   - `DONE`은 머지한 사람이 main에서 바꾼다. 작업 세션은 `DONE`으로 바꾸지 않는다.
   - `docs/tasks/README.md`와 다른 작업 파일의 상태는 고치지 않는다.
5. **작업 로그**(작업 파일 맨 아래)에 날짜별로 남길 것:
   - 한 일
   - 내린 설계 판단과 이유
   - 확인한 방법(테스트, 수동 확인)
   - 다음 작업자가 알아야 할 것(남은 문제, 주의점)
6. **커밋은 논리 단위로 나눈다.** 메시지는 기존 스타일(Conventional Commits 접두사 + 한국어)을 따른다.
   - 예: `feat(message): 메시지 저장소와 턴 번호 규칙 추가`, `test(message): …`, `docs(tasks): T03 작업 로그 갱신`
7. **PR 전에 최신 main으로 rebase**하고 충돌을 해결한다. 테스트와 빌드 통과를 확인한다.
   - PR 제목: `[T07] 채팅 흐름 재작성`
   - PR 본문: 요약, 완료 조건 체크리스트, 범위 밖 수정(있으면), 계약 변경(있으면)
8. **이 저장소는 공개(public)다.** 비밀값(`application.yml`, API 키, 비밀번호), `data/` 아래 사용자 데이터, 개인 경로를 커밋하지 않는다.

## 제품 원칙 (설계 판단이 애매할 때)
- **몰입이 먼저다.** 플레이 중 채팅 흐름을 끊는 모달, 승인 요청, diff 팝업을 만들지 않는다. 배경 처리 후 작은 뱃지를 띄우고, 자세한 내용은 사용자가 여는 패널에 둔다.
- **스토리 격리는 critical이다.** 같은 시나리오의 스토리끼리 어떤 파일도 공유해서 쓰지 않는다. 플레이 중에는 스토리 폴더만 읽고 쓴다.
- **매 턴 응답 전에는 LLM을 추가로 호출하지 않는다.** 무거운 작업(기억 기록)은 10턴마다 또는 명령으로 배경에서 한다.
- **제외 범위:** 턴별 모델 선택(CLI/API 프로바이더 선택만), 파티챗, 엔딩과 실시간 스탯, 이미지 생성, 과금.
