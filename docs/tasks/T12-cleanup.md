# T12 죽은 코드와 옛 경로 정리

- **상태**: TODO
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
- **보안 보강(삭제 아님):** `DocumentService.characterPath(charName)`가 이름을 검사하지 않는다(T08 발견). `DataPaths`처럼 경로 조각 하나만 허용하도록 검증을 추가하고, 테스트를 붙인다

## 구현 내용
1. 삭제 전에 `grep`으로 참조가 0건인지 확인하고, 확인 결과를 작업 로그에 남긴다.
2. **V6:** `DROP TABLE IF EXISTS chat_messages, story_summaries, character_events, character_states, scenario_settings`
3. `Plan-crack.md`의 "현재 진행 상황" 절 맨 위에 "v2 문서로 대체됨 → Plan-roadmap.md, docs/DESIGN.md" 안내를 추가한다.

## 완료 조건
- [ ] `./gradlew test`, `npm run build`, `npm run lint` 통과
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그
