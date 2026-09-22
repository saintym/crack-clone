# T16 지속 OOC 지시 + `/` 명령 백엔드

- **상태**: REVIEW
- **웨이브**: 5
- **의존**: T13, T14
- **브랜치**: `task/T16-directives-commands`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §8.1, §8.2 / 결정 D10, D11

## 목표
OOC 지시가 20턴쯤 지나면 잊히는 크랙의 문제를 해결한다. 지시를 파일로 **지속 보관**하고 매 턴 **가장 강한 위치**에 넣는다. `/` 명령 체계도 만든다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/directive/**`, `command/**`
- `chat/flow/**`에서 `command` 필드 처리 부분(T07이 받아 둔 필드)
- 신규 PromptContributor 빈 2개(지속 지시, 명령 프롬프트)
- 관련 테스트

## 구현 내용
1. **`DirectiveService`:** `directives.json` CRUD(DESIGN.md §8.1). 쓰기는 T05의 `AtomicFiles`를 쓴다.
2. **지시 주입:** 켜진 지시를 BOTTOM 슬롯에 넣는 `DirectiveContributor`. 형식은 "다음 지시는 해제될 때까지 항상 지켜라:" + 번호 목록.
3. **`CommandService`**
   - `commands.md` 파서
   - `GET /commands`: 시스템 명령(`기록`, `ooc`)과 사용자 정의 명령
   - `POST /commands/system`: body `{name, args}`. `기록`은 T14 trigger(MANUAL), `ooc`는 args가 있으면 지시 추가
4. **사용자 정의 명령**
   - `POST /messages`에 `command`가 있으면 유저 메시지를 `kind = COMMAND`로 저장한다
   - 명령 프롬프트와 args는 `PromptContext.turnInstruction`으로 넣는다(T13 BOTTOM)
   - 모르는 명령이면 400

## 완료 조건
- [x] `./gradlew test` 통과
- [x] 테스트 항목
  - 지시가 30턴 뒤에도 프롬프트 BOTTOM에 존재(preview로 검증)
  - 끈 지시 제외
  - `/기록` 트리거
  - 사용자 정의 명령의 turnInstruction이 그 턴에만 적용
- [x] 상태 `REVIEW` + 작업 로그 (PR 대신 로컬 브랜치를 오케스트레이터가 머지)

## 작업 로그

### 2026-09-23
- 시작. 로컬 worktree `task/T16-directives-commands`에서 진행.
- **한 일**
  - DESIGN.md §8.1·§8.2에 API 형식, `commands.md` 파싱 규칙, 이번 턴 지시 형식, 재생성 규칙을 먼저 적었다.
  - `directive/`: `Directive`·`DirectiveFile`(`directives.json`, `AtomicFiles`로 씀), `DirectiveService`(CRUD, 스토리별 직렬화), `DirectiveController`(`/directives`), `DirectiveContributor`(BOTTOM order 0, name `directives`).
  - `command/`: `CommandsParser`, `CommandService`(목록, 시스템 명령 실행, 사용자 정의 명령 조회, 이번 턴 지시 생성), `CommandController`(`GET /commands`, `POST /commands/system`).
  - `chat/flow/ChatFlowService`: `send`의 `command` 처리(COMMAND 저장 + turnInstruction), `regenerate`에서 명령 턴이면 지시 재적용. `chat/api/StoryMessageDtos`는 KDoc 한 줄만 고쳤다.
- **설계 판단**
  - 작업 파일의 "신규 PromptContributor 빈 2개(지속 지시, 명령 프롬프트)" 중 명령 프롬프트 기여자는 **만들지 않았다.** T13의 `TurnInstructionContributor`(BOTTOM 100)가 이미 `turnInstruction`을 넣으므로, 같은 일을 하는 빈을 또 두면 두 번 들어가거나 `PromptContext`에 필드를 늘려야 한다(공용 prompt 파일 수정). 구현 내용 4의 "turnInstruction으로 넣는다"를 따랐다.
  - 명령 턴을 재생성하면 같은 명령 지시를 다시 넣는다. "그 턴에만 적용"의 그 턴을 다시 만드는 것이기 때문이다. 명령 이름은 DB에 따로 없으므로 저장된 `content`의 `/이름`에서 다시 찾는다. 그사이 `commands.md`에서 명령이 사라졌으면 지시 없이 생성한다(재생성은 막지 않음).
  - `command`가 빈 문자열이면 일반 메시지로 보낸다. 시스템 명령(`기록`, `ooc`)을 `command`로 보내면 400(REST로만 실행).
  - `/ooc` 인자가 없으면 백엔드는 400. 패널 열기는 프론트가 로컬에서 처리한다(§8.2).
  - `_legacy` 스토리는 지시 쓰기 400(스토리 문서 API와 같은 이유). 깨진 `directives.json`은 쓰기를 500으로 거부해 사용자의 파일을 덮어쓰지 않고, 주입은 로그만 남기고 건너뛴다.
  - `commands.md`는 매 요청마다 파싱한다(작은 파일, 명령 사용 때만 읽음). 키워드북 같은 캐시는 두지 않았다.
- **확인**
  - `./gradlew test` 전부 통과(384개). 로컬 `application.yml`을 스크래치로 치운 상태에서 `clean test`도 통과 후 원복.
  - `DirectiveApiTest`: CRUD·400·404·legacy·깨진 파일, **35턴 뒤 preview의 BOTTOM과 마지막 유저 메시지 `[지시]` 블록 맨 앞에 지시 존재**(원문 범위에서 첫 턴이 빠진 상태), 끈 지시 제외, 이번 턴 지시보다 앞.
  - `CommandApiTest`: 목록 순서, `/기록` → MANUAL 기록 생성(메시지 안 남김), `/ooc` → 지시 추가 후 다음 전송 프롬프트에 반영, 사용자 정의 명령 COMMAND 저장과 **다음 턴에는 지시 없음**, 명령 턴 재생성 시 지시 재적용, 모르는 명령·시스템 명령 400(저장 안 함). SSE는 `asyncDispatch`까지 실행.
  - `CommandsParserTest`: 예시 형식, 여러 줄 프롬프트, 버리는 규칙, 지시 형식.
- **T18(프론트)가 쓸 API** (모두 `/api/stories/{storyId}` 아래, DESIGN.md §8.1·§8.2)
  - `GET /commands` → `[{name, description, type: "SYSTEM"|"CUSTOM"}]` (name에 `/` 없음, 시스템 먼저)
  - `POST /commands/system` `{name, args?}` → `{name, record, directive}`. `기록`이면 `record = {result, record}`(§7.2 `POST /memory/record`와 같음), `ooc`면 `directive = Directive`. `/ooc` 단독은 프론트가 패널을 열고 호출하지 않는다(호출하면 400)
  - `POST /messages` `{content: "/일기 오늘은…", command: "일기"}` → SSE. `user` 이벤트의 `kind`가 `COMMAND`
  - `GET /directives` → `Directive[]`, `POST /directives` `{text, enabled?}` → 201 `Directive`, `PATCH /directives/{id}` `{text?, enabled?}`, `DELETE /directives/{id}` → 204. `Directive = {id, text, enabled, createdAt}`
- **주의점**
  - 대화 원문에는 COMMAND 유저 메시지의 `content`(`/일기 …`)가 그대로 남는다. 명령 프롬프트는 저장하지 않는다.
  - `prompt-preview`는 `command`를 받지 않는다(명령 턴 미리보기는 안 됨). 필요하면 T13 영역에서 쿼리를 추가.
  - 지시 텍스트 길이 제한은 없다. 지시가 많아지면 매 턴 프롬프트가 길어진다.
