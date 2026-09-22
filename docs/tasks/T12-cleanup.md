# T12 죽은 코드와 옛 경로 정리

- **상태**: REVIEW
- **웨이브**: 3
- **의존**: T07, T08, T09, T11
- **브랜치**: `task/T12-cleanup`
- **마이그레이션**: **V6**

## 목표
v2로 대체되었거나 쓰이지 않는 코드와 테이블을 지운다.

## 범위 (삭제 대상)
- `context/**` (ContextService)
- `state/**` (character_states, character_events: 상태 패널은 문서 기반, T19)
- `setting/**` (scenario_settings: D1, 템플릿 기능 불필요)
- `memory/service/MemoryService.kt`, `memory/service/StorySummaryService.kt`, `memory/entity/**`, `memory/repository/**`, 옛 `memory/controller`(must-remember → 스토리 문서 API로 대체)
  - `memory/docs/**`(T05)는 **유지**
- `chat/entity/ChatMessage.kt`, `chat/repository/**`, `chat/service/ChatFileService.kt`, 옛 `chat/controller/ChatController.kt`(`/chat/*`)
- `chat/service/MessageParser.kt`와 `/parse`: 프론트가 쓰지 않는다. 감정 태그는 T07의 `EmotionTagFilter`가 처리한다
- `PromptAssembler.loadConversationContext`와 요약 로딩
- `src/main/resources/db/migration/V6__drop_unused.sql`
- 삭제된 코드의 테스트
- 프론트의 남은 옛 API 호출
- 분기 요청의 과도기 필드 `messageIndex`(T09) 제거. `StoryService`에 남은 `chatFileService` 의존성 제거
- 옛 `ChatService`(T07에서 `@Deprecated` 처리)와 `ChatRequest`
- **버그 수정(삭제 아님): [BUG-006](../../bugs/BUG-006_시나리오_등록시_원본_문서_덮어쓰기.md).** `ScenarioService.copyTemplate`이 기존 파일을 덮어쓰지 않게 하고 회귀 테스트를 추가한다. `bugs/README.md` 상태를 갱신한다
- **보안 보강(삭제 아님):** `DocumentService.characterPath(charName)`가 이름을 검사하지 않는다(T08 발견). `DataPaths`처럼 경로 조각 하나만 허용하도록 검증을 추가하고, 테스트를 붙인다

## 구현 내용
1. 삭제 전에 `grep`으로 참조가 0건인지 확인하고, 확인 결과를 작업 로그에 남긴다.
2. **V6:** `DROP TABLE IF EXISTS chat_messages, story_summaries, character_events, character_states, scenario_settings`
3. `Plan-crack.md`의 "현재 진행 상황" 절 맨 위에 "v2 문서로 대체됨 → Plan-roadmap.md, docs/DESIGN.md" 안내를 추가한다.

## 완료 조건
- [ ] `./gradlew test`, `npm run build`, `npm run lint` 통과
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 시작. T07·T08·T09·T11 머지된 main(33cbb75)에서 `task/T12-cleanup` 브랜치로 진행. T13(prompt, ConversationBuilder)·T14(memory/record, chat/api)가 병렬 진행 중이라 해당 파일은 건드리지 않는다.

**한 일**
- 삭제(백엔드): `context/**`, `state/**`, `setting/**`, `memory/service`(MemoryService, StorySummaryService), `memory/entity`, `memory/repository`, `memory/controller`(must-remember·summaries), `memory/dto`(위 둘만 쓰던 DTO), `chat/entity`(ChatMessage), `chat/repository`, `chat/service`(ChatService, ChatFileService, MessageParser, SseStreamListener), `chat/controller`(`/api/stories/{id}/chat`, `/parse` 포함), `chat/dto`(ChatRequest, ParsedResponse). 해당 테스트(ContextServiceTest, StateServiceTest, SettingServiceTest, MemoryServiceTest, StorySummaryServiceTest, ChatFileServiceTest, MessageParserTest)도 삭제.
- `StoryService` 생성자의 `chatFileService` 제거(StoryServiceTest 수정).
- 분기 요청 `messageIndex` 제거: `StoryBranchRequest`, `StoryBranchService.branch(storyId, messageId, title)`, 컨트롤러 두 곳. `messageId`가 없으면 400. `StoryBranchTest`의 과도기 테스트는 "시나리오 경로로도 분기한다"로 바꾸고, `messageIndex`만 보내면 400인지 확인하도록 고쳤다. DESIGN.md 분기 문장 갱신.
- V6 `V6__drop_unused.sql`: `DROP TABLE IF EXISTS chat_messages, story_summaries, character_events, character_states, scenario_settings`. 엔티티를 모두 지운 뒤의 커밋에 넣어, 어느 커밋에서든 `ddl-auto: validate`와 어긋나지 않게 했다.
- BUG-006 수정: `ScenarioService.copyTemplate`이 대상 파일이 있으면 건너뛴다(`REPLACE_EXISTING` 제거, `CREATE_NEW`). 같은 문제가 있던 `chat/chat_latest.md` 초기화도 없을 때만 쓴다. 회귀 테스트 2개. `bugs/README.md`, BUG-006 문서 갱신.
- 보안 보강: `DocumentService.characterPath`에서 인물 이름을 경로 조각 하나로 제한(빈 값, `.`, `..`, `/`, `\`, NUL 거부 → 400). 테스트 2개.
- `Plan-crack.md` "현재 진행 상황" 맨 위에 v2 대체 안내.

**삭제 전 참조 확인(grep, `crack-backend/src`)**
- `ContextService`·`com.crack.context`: 자기 패키지와 ContextServiceTest뿐.
- `com.crack.state`·`CharacterState`·`CharacterEvent`: state 패키지, context 패키지, 두 테스트뿐.
- `com.crack.setting`·`ScenarioSetting`: setting 패키지와 SettingServiceTest뿐.
- `MemoryService`·`StorySummary`·`com.crack.memory.dto`/`entity`/`repository`: 옛 memory 패키지와 MemoryServiceTest·StorySummaryServiceTest뿐. `memory/docs`는 참조 없음(유지).
- `chat.entity.ChatMessage`·`ChatMessageRepository`: 옛 chat/memory/context 코드뿐. (`ChatMessage` 이름의 다른 참조는 `com.crack.ai.dto.ChatMessage`로 무관.)
- `ChatFileService`: 옛 ChatService, MemoryService, StoryService(미사용 필드), 테스트, `LegacyChatParser`의 KDoc 문장뿐(코드 참조 아님).
- `ChatService`·`ChatRequest`·`MessageParser`·`SseStreamListener`·`com.crack.chat.dto`: 옛 chat 코드와 MessageParserTest뿐.
- 삭제 후 위 이름 grep: `LegacyChatParser` KDoc의 과거 설명 두 줄만 남음.
- 프론트: `src/api`의 호출 경로는 `/ai/providers`, `/auth/*`, `/scenarios/**`(시나리오·스토리·문서·인물), `/stories/{id}`, `/stories/{id}/branch`, 새 메시지 API뿐. `/chat/*`(API), `/parse`, `/memory`, `/settings`, `/states`, `messageIndex` 호출 없음. `/chat/:storyId`는 프론트 라우트다. **프론트는 고칠 것이 없어 건드리지 않았다.**

**판단**
- **`PromptAssembler.loadConversationContext`·`loadSummaries`·`parseChatLatest`는 남겼다.** 옛 ChatService 삭제로 main 코드 호출은 0건이 됐지만(`PromptAssemblerTest`만 호출), T13이 prompt 패키지를 재작성 중이라 충돌을 피하려고 건드리지 않았다. T13이 지운다.
- `memory/dto`는 작업 파일 목록에 없지만 옛 MemoryService·MemoryController만 쓰던 DTO라 함께 지웠다. T14의 새 DTO는 `memory/record` 아래에 둔다고 되어 있다.
- `StoryBranchService` KDoc의 `recorded_through_turn` 메모는 T14가 고칠 부분이라 그대로 뒀다(이 파일은 `branch` 시그니처와 기준 메시지 조회만 고쳤다. T14와 머지 시 같은 파일이 바뀔 수 있다).
- BUG-006: 시나리오 생성이 여전히 `memory/must_remember.md`, `chat/` 같은 옛 구조를 만드는 것은 이번 범위(덮어쓰기 버그) 밖이라 그대로 뒀다. 없을 때만 만드므로 원본을 해치지 않는다.
- 인물 이름 검증은 `DataPaths.requireSegment`가 private이라 `DocumentService` 안에 같은 규칙으로 따로 뒀다(`global/config`를 건드리지 않기 위해).

**확인**
- `./gradlew clean test`: 303개 전부 통과. 로컬 `application.yml`을 스크래치로 치운 상태에서도 303개 통과 후 원복.
- 각 커밋마다 `compileTestKotlin`(삭제 커밋) 또는 관련 테스트(분기·시나리오·문서)를 돌려 단독 컴파일을 확인.
- BUG-006 회귀 테스트는 수정 전 `ScenarioService`로 돌리면 2개가 실패하는 것을 확인.
- V6는 H2 테스트에서 실행되지 않는다(Flyway 꺼짐). PostgreSQL 문법(`DROP TABLE IF EXISTS a, b, ...`)이며, 다른 테이블이 이 테이블들을 참조하지 않는다(V1~V3 확인). 실제 DB에서 돌려보지는 않았다.

**다음 작업자**
- T13: `PromptAssembler.loadConversationContext`/`loadSummaries`/`parseChatLatest`와 그 테스트(`PromptAssemblerTest` 231~320행 부근)를 지우면 된다.
- 남은 과도기 코드(T09 메모): `DataPaths`의 `_legacy` 분기, 문서 API의 `_legacy` 쓰기 차단, `PromptAssembler`의 `memory/must_remember.md` 폴백, `com.crack.migration`은 사용자가 이전을 마친 뒤 지운다. 이번에는 지우지 않았다.
- 배포 시 V6가 옛 테이블을 지운다. 옛 DB 데이터(`chat_messages` 등)를 보존하려면 마이그레이션 전에 백업한다(대화 원문은 파일 `chat/*.md`에 있고 T09 이전기가 그것을 읽는다).
