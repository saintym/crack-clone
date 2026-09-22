# 오케스트레이션 로그

> [docs/PARALLEL.md](./PARALLEL.md) 방식으로 작업을 병렬 진행하면서 생긴 **오류, 충돌, 판단**을 기록한다.
> - 운영: 로컬 worktree(`scripts/task-worktree.sh`) + 오케스트레이터 세션 1개 + 작업 에이전트 최대 4개 동시
> - 머지: 오케스트레이터가 리뷰한 뒤 main에 `--no-ff` 머지 → DONE 처리 → push
> - 각 작업의 상세 기록은 해당 작업 파일(`docs/tasks/T??-*.md`)의 작업 로그에 있다. 여기에는 **운영 관점의 문제와 해결**만 모은다.

## 요약

| 라운드 | 시작 | 작업 | 결과 |
|---|---|---|---|
| 1 | 2026-09-23 | T03, T01, T02, T05 (+T04) | T03, T05, T01, T02, T06, T00, T04 머지 → 라운드 1 종료 |
| 2 | 2026-09-23 | T07, T08 | 진행 중 |

## 오류·문제 기록

기록 형식: `[작업] 증상 → 원인 → 해결 → 재발 방지`

### 사전 준비 단계 (2026-09-23)
- **[기준선] 백엔드 테스트 1건 실패** → `PromptAssemblerTest`가 BASE_RULE 문구 변경을 따라가지 못함 → 테스트 기대 문자열을 현재 문구로 갱신(`dd7fce2`) → 에이전트가 첫 테스트 실행에서 무관한 실패를 만나지 않도록 작업 시작 전에 기준선을 녹색으로 맞춘다.
- **[기준선] 프론트 빌드 실패** → `verbatimModuleSyntax`인데 `ReactNode`를 값 import함(TS1484) → `import type`으로 수정(`992b570`).
- **[기준선] 프론트 lint 오류 6건** → React Hooks 새 규칙(immutability, set-state-in-effect) 위반 → 범위가 커서 T00(ChatPage 외 페이지)과 T04(ChatPage)로 분리. 두 작업이 머지되기 전까지 기준은 "새 lint 오류를 만들지 않는다".
- **[스크립트] 새 작업 브랜치가 `origin/main`을 추적** → `git worktree add -b`가 원격 브랜치를 시작점으로 받으면서 자동으로 추적을 설정함 → `--no-track` 추가. 추적이 있으면 `git push` 한 번에 main으로 올라갈 위험이 있다.

### 라운드 1
- **[T03] 빌드·테스트 오류 없음.** 에이전트가 스스로 발견하고 처리한 함정:
  - JPA 1차 캐시 때문에 락을 잡은 뒤 다시 읽어도 옛 엔티티가 돌아옴 → 락을 건 뒤 JPQL로 다시 조회
  - `@Modifying(clearAutomatically)`가 락 걸린 Story 엔티티를 분리함 → 삭제 뒤 다시 읽어 갱신
  - H2(`ddl-auto`) 스키마에는 `ON DELETE CASCADE`가 없음 → 후보, 메시지 순으로 명시적 삭제
  - 테스트 설정 차이로 Spring 컨텍스트가 2개 떠서 같은 H2 메모리 DB를 `create-drop`으로 공유 → `@Import`를 통일해 컨텍스트 1개로
- **[T03] 계약 보정:** 턴 번호를 `stories.turn_count + 1`이 아닌 `MAX(turn_no) + 1`로 계산하도록 DESIGN.md §3을 고쳤다. 기존 스토리는 파일 기반 turn_count가 이미 커서, 새 테이블과 어긋나기 때문이다.
- **[T03→T02] 머지 순서 영향:** T03이 먼저 머지되어, T02(Story 생성자 변경)가 rebase할 때 `MessageTestSupport.createTestStory`를 함께 고쳐야 한다.
- **[T05] 별칭 파싱 테스트 실패** → 템플릿 안내 문구 `(쉼표로 구분, 선택)`가 쉼표에서 쪼개져 두 개의 별칭으로 읽힘. 목록 출력이 원문과 똑같아 원인 파악이 늦었다 → 값 전체가 괄호로 감싸져 있으면 빈 목록 처리. 템플릿 안내 문구가 데이터로 파싱되지 않는지 검사하는 `TemplatesTest` 추가.
- **[T05] 중간 커밋이 컴파일되지 않음** → 논리 단위로 쪼갠 커밋이 뒤 커밋의 클래스를 참조 → push 전이라 `reset --soft` 후 의존 순서대로 다시 커밋. **교훈:** 커밋을 나눌 때 각 커밋이 단독으로 빌드되는지 확인한다(CLAUDE.md 규칙 후보).
- **[T05] 계약 이름 불일치** → 작업 파일(`oldestEntries`/`replaceOldestWithSummary`)과 DESIGN(`split`/`compactOldest`)의 API 이름이 서로 달랐음 → 둘 다 제공(별칭). **교훈:** 작업 파일과 DESIGN에 같은 API를 두 번 적지 않고 DESIGN만 참조하게 한다.
- **[T01] CLI stream-json 형식 문서 부재** → 로컬 `claude`가 단일 바이너리라 소스를 grep할 수 없음 → `LC_ALL=C grep -a`로 바이너리 안의 문자열을 찾아 `stream_event` 구조 확인. 테스트 픽스처는 이 문자열을 보고 **손으로 작성한 것**이라 실제 CLI로 검증이 필요하다(사용자 로컬 확인 항목).
- **[T01] `--include-partial-messages` 중복 출력** → partial delta 뒤에 같은 내용의 `assistant` 메시지와 `result`가 또 옴 → partial을 받았으면 `assistant`는 무시하고, `result`는 텍스트가 전혀 없을 때만 폴백.
- **[T01] Mockito와 Kotlin 기본 인자 충돌** → `gateway.chat(req)`가 실제로는 `chat(req, null)`로 컴파일되어 `whenever(chat(any()))` 스텁의 매처 개수가 맞지 않음 → `chat(any(), anyOrNull())` 사용. 이후 작업들이 같은 함정에 빠질 수 있다.
- **[T01] Spring이 원치 않는 Executor를 주입할 위험** → 생성자에 `Executor` 기본 인자가 있으면 `applicationTaskExecutor`가 주입될 수 있음 → Spring용 `@Autowired` 보조 생성자를 따로 둠.
- **[T01] CLI 프로세스 교착 위험** → stderr 버퍼가 가득 차면 프로세스가 멈출 수 있음 → stderr를 별도 스레드에서 비우고, 워치독으로 타임아웃 처리.
- **[T01·T05] 에이전트 보고의 diff 착시** → 작업 중 main에 다른 작업이 머지되자 `git diff origin/main..HEAD`(점 2개)에 남의 파일이 "삭제"로 보임 → 리뷰할 때는 `origin/main...HEAD`(점 3개, 분기점 기준)를 쓴다. 에이전트 지시문도 점 3개로 바꾼다.
- **[운영] 사용자 로컬 설정 영향 (T01)** → CLI 경로 자동 탐색이 제거되어 로컬 `application.yml`에 `crack.ai.cli.path`가 필요할 수 있고, 모델 ID도 예시 파일을 보고 갱신해야 한다. 로컬 설정은 비밀값이 있는 사용자 파일이라 오케스트레이터가 고치지 않고 안내만 한다.
- **[T02] 머지 순서 때문에 컴파일 깨짐 예고** → T02가 브랜치를 딴 뒤 T03이 머지되어, T03 테스트 헬퍼가 T02에서 없앤 `Story.dataPath`를 사용 → T01까지 머지된 뒤 에이전트에게 rebase를 허가하고, 한 줄 수정 커밋을 추가해 해결.
- **[T02] rebase 충돌 1건** (`MemoryServiceTest.setUp`: T01의 목 교체와 T02의 임시 폴더 구조 변경) → 둘 다 반영. `ChatService`와 `MemoryService`는 T01(AI 호출부)과 T02(경로 계산부)로 범위를 나눠 둔 덕분에 자동 병합됐다. 사전 범위 분리가 효과가 있었다.
- **[T02] 중간 커밋 4개의 테스트 컴파일 실패** → 수정 커밋을 따로 추가하는 방식이라 그 사이 커밋에서는 테스트가 컴파일되지 않는다(main 코드는 컴파일됨). bisect에 약간의 공백이 생기지만 추가 왕복 비용이 커서 그대로 머지했다. **교훈:** rebase 시 필요한 수정은 `--exec`나 fixup으로 해당 커밋에 합치도록 지시한다.
- **[T02] 기존 버그 발견** → `ChatFileService.parseMessages`의 `split(Regex)`가 캡처 그룹을 결과에 넣지 않아 역할과 내용이 한 칸씩 밀림 → 분기의 turnCount 계산이 틀릴 수 있다. T07·T09·T12에서 이 코드를 대체·삭제하므로 따로 고치지 않았다.
- **[T02] 로컬 PG 검증 중 환경 문제** → 스크래치 경로가 길어 Unix 소켓 경로가 103바이트를 넘음(`unix_socket_directories=''` + TCP로 우회). psql 기본 역할이 없음(`initdb -U`로 해결). 스모크 테스트가 인증 필터에 막혀 401(빈 비밀번호로 기동). 사용자의 5432 서버와 `crack` DB는 건드리지 않고, 임시 클러스터를 띄워 검증한 뒤 삭제했다.
- **[T02] 격리 공백 주의** → `_legacy` 스토리(옛 기본 스토리)는 T09가 폴더를 옮기기 전까지 시나리오 원본 폴더를 직접 읽고 쓴다. 실제 데이터로 운영하기 전에 T09 실행이 필수다.
- **[T02] 사용자 데이터 확인 필요** → `crack-backend/data/테스트세계`와 `data/테스트세계`의 `chat_latest.md` 내용이 서로 다르다. 자동으로 지우지 않고, BUG-005 문서에 사용자가 확인하고 정리하는 절차를 적었다.
- **[T06] 오류 없음.** 2글자 미만 키 판정을 `String.length`로 하면 이모지(UTF-16 2 char)가 통과해 버려서 코드 포인트 기준으로 셈.
- **[T00] `npm ci`가 ERESOLVE로 실패** → `vite-plugin-pwa@1.2.0`의 peer 범위(vite ^3~^7)가 설치된 vite 8과 맞지 않음. 원래부터 있던 문제로, CLAUDE.md의 표준 명령이 그대로는 실패했다 → 오케스트레이터가 `crack-frontend/.npmrc`에 `legacy-peer-deps=true`를 추가(깨끗한 폴더에서 `npm ci` 통과 확인). 플러그인이 vite 8을 지원하면 제거한다.
- **[T00] `useCallback`으로 감싸도 `set-state-in-effect`가 남음** → eslint-plugin-react-hooks 7(React Compiler 분석)은 async 함수의 `await` 뒤 setState도 effect 안의 동기 호출로 판정 → 로드 함수를 `.then` 체인으로 바꿔 해결. 임시 probe 컴포넌트로 규칙 동작을 먼저 확인했다. **이후 프론트 작업(T04, T11, T15, T18~T20)에 같은 패턴을 쓰도록 안내한다.**
- **[T04] 오류 없음.** 에이전트가 따로 확인한 것:
  - `npm ci`가 똑같이 ERESOLVE로 실패했다(T00과 같은 원인). `.npmrc`가 main에 들어가기 전에 브랜치를 땄기 때문이다.
  - ESLint 9에서 `-f unix` 포매터가 빠져 `-f json`을 파싱해 오류 목록을 뽑았다.
  - 상태가 여러 컴포넌트로 흩어지면서 가드 순서가 바뀔 수 있는 지점(전송 차단 시 입력 유지, 수정 저장 실패 시 편집 유지)은 동작이 그대로 유지되도록 처리했다.
  - 실제 백엔드와 연동한 수동 확인은 하지 않았다. 원본 코드와 기능별로 대조했고, 프록시 환경변수는 dev 서버를 띄워 확인했다.
- **[T00·T04 병렬] 프론트 lint 기준선 회복** → T00(다른 페이지)과 T04(ChatPage)를 범위를 나눠 동시에 진행했고, 둘 다 머지한 뒤 lint 오류 0을 확인했다.

### 라운드 2
- **[운영] T07 시작을 T02 머지 뒤로 미룸** → T07은 ChatService를 대체하는데, T02도 ChatService의 경로 계산을 바꾸고 있었다. 먼저 시작하면 T07 쪽에 큰 rebase가 생긴다 → 미리 만든 T07 worktree를 지우고 T02 머지 후 다시 생성. 의존 관계에는 없지만 **같은 파일을 크게 바꾸는 작업은 순서를 두는 것**이 싸다.
