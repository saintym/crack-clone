# T03 메시지 DB 저장소

- **상태**: TODO
- **웨이브**: 1
- **의존**: 없음
- **브랜치**: `task/T03-message-store`
- **마이그레이션**: **V5**
- **설계**: DESIGN.md §3 (스키마, 턴 규칙), §5.1

## 목표
대화 메시지를 ID, 턴 번호, 재생성 후보, 수정 표시를 가진 DB 저장소로 옮길 **기반**을 만든다. 이 작업은 저장소 계층만 만든다. 채팅 흐름 연결은 T07이 한다.

## 범위
- 신규 패키지 `crack-backend/src/main/kotlin/com/crack/message/**` (entity, repository, service, dto)
- `src/main/resources/db/migration/V5__story_messages.sql`
- `src/test/kotlin/com/crack/message/**`
- **수정 금지:** 기존 `chat/**`(T07 담당), `stories` 테이블 스키마(T02, T14 담당). 턴 수 갱신은 `StoryRepository`를 주입받아 `turnCount`만 바꾼다.

## 구현 내용
1. 엔티티 `StoryMessage`, `MessageVariant`와 enum `MessageRole`(USER/ASSISTANT), `MessageKind`(NORMAL/PROLOGUE/CONTINUATION/COMMAND). 기존 `ai.dto.MessageRole`과 이름이 겹치지 않게 패키지를 분리한다.
2. `MessageService`는 DESIGN.md §5.1 시그니처를 모두 구현한다.
   - **턴 규칙:** 유저 메시지는 새 턴을 연다. 응답은 같은 턴이고, CONTINUATION은 새 턴, PROLOGUE는 턴 0이다.
   - `appendAssistant`로 첫 응답을 저장할 때 후보 0번도 함께 만든다.
   - `addVariant` / `selectVariant`는 `content`를 선택된 후보 사본으로 동기화한다. `selectVariant`는 가장 최근 ASSISTANT에만 허용하고, 아니면 `BadRequestException`을 던진다.
   - `edit`는 `edited_at`을 갱신하고, ASSISTANT면 선택된 후보 내용도 바꾼다.
   - `truncateFrom`은 삭제한 뒤 `stories.turn_count`를 남은 최대 `turn_no`로 다시 계산하고, 등록된 `TruncateHook` 빈 전부에 `(storyId, minTruncatedTurn)`을 알린다.
   - seq는 스토리 내에서 0부터 연속되게 유지한다.
3. `MessageView` DTO(§5.2)와 `MessageExporter`(마크다운 내보내기: `## 턴 N · USER` 형식)
4. 동시성: 같은 스토리에 대한 쓰기는 `@Transactional` + 스토리 행 비관적 락(`SELECT … FOR UPDATE`) 또는 seq 유니크 제약으로 재시도한다. 어느 쪽을 골랐는지 작업 로그에 적는다.

## 완료 조건
- [ ] `./gradlew test` 통과
- [ ] 테스트 항목
  - 턴 번호 규칙(일반, 이어쓰기, 프롤로그)
  - 후보 추가와 선택, 과거 메시지 선택 거부
  - 역할별 수정
  - `truncateFrom` 뒤 seq·turnCount·훅 호출
  - `editedTurnsSince`
  - 내보내기 형식
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그
