# T03 메시지 DB 저장소

- **상태**: DONE
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
   - `emotion`은 메시지와 후보 양쪽에 저장한다. 후보를 선택하면 메시지의 `content`와 `emotion`을 함께 동기화한다. `MessageView`에는 emotion을 넣지 않는다(DESIGN.md §5.3).
   - `addVariant` / `selectVariant`는 `content`를 선택된 후보 사본으로 동기화한다. `selectVariant`는 가장 최근 ASSISTANT에만 허용하고, 아니면 `BadRequestException`을 던진다.
   - `edit`는 `edited_at`을 갱신하고, ASSISTANT면 선택된 후보 내용도 바꾼다.
   - `truncateFrom`은 삭제한 뒤 `stories.turn_count`를 남은 최대 `turn_no`로 다시 계산하고, 등록된 `TruncateHook` 빈 전부에 `(storyId, minTruncatedTurn)`을 알린다.
   - seq는 스토리 내에서 0부터 연속되게 유지한다.
3. `MessageView` DTO(§5.2)와 `MessageExporter`(마크다운 내보내기: `## 턴 N · USER` 형식)
4. 동시성: 같은 스토리에 대한 쓰기는 `@Transactional` + 스토리 행 비관적 락(`SELECT … FOR UPDATE`) 또는 seq 유니크 제약으로 재시도한다. 어느 쪽을 골랐는지 작업 로그에 적는다.

## 완료 조건
- [x] `./gradlew test` 통과
- [x] 테스트 항목
  - 턴 번호 규칙(일반, 이어쓰기, 프롤로그)
  - 후보 추가와 선택, 과거 메시지 선택 거부
  - 역할별 수정
  - `truncateFrom` 뒤 seq·turnCount·훅 호출
  - `editedTurnsSince`
  - 내보내기 형식
- [x] 상태 `REVIEW` + 작업 로그 (PR은 오케스트레이터가 머지)

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T03-message-store`.

**한 일**
- `V5__story_messages.sql`: DESIGN.md §3의 `story_messages`, `message_variants`를 그대로 옮겼다.
- `com.crack.message` 패키지 신설
  - `entity`: `StoryMessage`, `MessageVariant`, `MessageRole`(USER/ASSISTANT), `MessageKind`(NORMAL/PROLOGUE/CONTINUATION/COMMAND). `ai.dto.MessageRole`과 패키지로 분리
  - `repository`: `StoryMessageRepository`, `MessageVariantRepository`
  - `dto`: `MessageView`(§5.2, emotion 없음), `TruncateResult(storyId, minTruncatedTurn, deletedCount, turnCount)`
  - `service`: `MessageService`(§5.1 시그니처 전부), `TruncateHook`, `MessageExporter`
- 테스트 30개 추가(`MessageServiceTest` 26, `MessageExporterTest` 3, `MessageConcurrencyTest` 1).

**설계 판단**
- **동시성: 스토리 행 비관적 락을 골랐다.** 모든 쓰기 메서드는 `@Transactional` 안에서 `EntityManager.find(Story, id, PESSIMISTIC_WRITE)`(`SELECT … FOR UPDATE`)로 스토리를 잡은 뒤 seq/turn을 계산한다. seq 유니크 제약 재시도보다 코드가 단순하고, turn_count 갱신까지 같이 직렬화된다. `StoryRepository`를 고치지 않으려고 락은 `EntityManager`로 건다. 메시지 ID로 들어오는 메서드는 storyId만 조회 → 락 → 메시지를 쿼리로 다시 읽는다(락 대기 중 삭제 대비). `(story_id, seq)` 유니크 제약은 최후 방어선으로 남아 있다.
- **턴 번호 기준은 `stories.turn_count`가 아니라 `MAX(turn_no)`다.** DESIGN의 "turn_count = 남은 메시지의 최대 turn_no"를 원천으로 삼았다. 기존 스토리는 파일 기반 대화 때문에 turn_count가 이미 큰 값일 수 있는데, 새 테이블에는 메시지가 없으므로 그 값을 믿으면 턴이 어긋난다. 쓰기마다 turn_count를 다시 계산해 덮어쓴다.
- **`appendAssistant` 턴 결정:** PROLOGUE는 스토리에 메시지가 하나도 없을 때만(seq 0) 허용, 아니면 400. CONTINUATION은 `max+1`(turnNo 인자 무시). 그 외는 `turnNo ?: max(현재 최대 턴, 1)`이며, 명시한 turnNo가 현재 최대 턴보다 작거나 1 미만이면 400. 턴이 거꾸로 가는 것을 막기 위해서다.
- **`appendUser`의 kind는 NORMAL/COMMAND만 허용**한다. PROLOGUE/CONTINUATION은 ASSISTANT 전용이라 400.
- **`addVariant`도 가장 최근 ASSISTANT에만 허용**한다(D17 재생성 규칙). 작업 파일은 `selectVariant`만 명시했지만, 과거 메시지에 후보가 생기면 T07의 400 처리와 어긋나므로 저장소에서도 막았다. "가장 최근 ASSISTANT"는 seq가 가장 큰 ASSISTANT다(뒤에 USER가 있어도 해당).
- **`edit`:** emotion은 바꾸지 않는다. ASSISTANT면 선택된 후보의 content만 바꾸고 다른 후보는 그대로 둔다.
- **`truncateFrom`:** 후보 → 메시지 순서로 벌크 삭제한다(H2 `ddl-auto`에는 FK `ON DELETE CASCADE`가 없어서 명시 삭제). 훅은 **같은 트랜잭션 안에서** 삭제 뒤에 호출한다. 훅이 실패하면 삭제도 롤백된다. T14가 커밋 뒤에 처리하고 싶으면 훅 안에서 `TransactionSynchronization`을 쓰면 된다. 훅은 `ObjectProvider<TruncateHook>.orderedStream()`으로 받아서 빈이 없어도 동작한다(`@Order` 존중).
- **`editedTurnsSince`:** `1 <= turn_no <= throughTurn AND edited_at IS NOT NULL AND (since IS NULL OR edited_at > since)`의 중복 없는 턴을 오름차순으로 준다. **프롤로그(턴 0)는 기억 기록 대상이 아니므로 제외**했다(§3 턴 규칙).
- **내보내기 형식:** `# {스토리 제목}` 다음 메시지마다 `## 턴 N · ROLE` + 빈 줄 + 본문. 감정은 넣지 않는다. kind 표시는 넣지 않았다.
- `MessageService.view(message)`를 추가했다(계약에 없는 보조 메서드). T07이 SSE `user`/`done` 이벤트에 MessageView를 실을 때 쓰라는 용도다.

**확인 방법**
- `cd crack-backend && ./gradlew test` → 139개 전부 통과(기준선 109 + 신규 30).
- 리모트 환경 재현을 위해 로컬 `application.yml`을 잠시 치운 상태로도 전체 테스트를 돌려 통과를 확인했다(`application-test.yml`만으로 컨텍스트가 뜬다).
- 동시성: 트랜잭션 없는 `MessageConcurrencyTest`가 6개 스레드 × 5회 `appendUser`를 동시에 실행해 seq 0..29, turn 1..30이 겹침 없이 연속되는 것을 확인했다.
- V5 SQL은 로컬에 PostgreSQL이 없어 실제 적용은 못 했다. DESIGN.md §3 SQL과 동일하다.

**다음 작업자 주의점**
- **T02 머지 후:** `Story` 생성자에서 `dataPath`가 빠지고 `dirName`이 생기면 테스트 헬퍼 `src/test/kotlin/com/crack/message/MessageTestSupport.kt`의 `createTestStory`만 고치면 된다. 서비스 코드는 `turnCount`만 쓴다.
- **T07:** 채팅 흐름 연결 시 `appendUser` → 생성 → `appendAssistant(storyId, content, emotion, turnNo = user.turnNo)` 순서를 권장한다. 이어쓰기는 `kind = CONTINUATION`. 재생성 첫 응답(마지막이 USER)도 `appendAssistant`로 저장하면 된다. 예외는 `BadRequestException`(400) / `NotFoundException`(404)이다.
- **T14:** `TruncateHook` 빈을 등록하면 `(storyId, minTruncatedTurn)`이 삭제와 같은 트랜잭션 안에서 전달된다.
- 기존 파일 기반 대화를 새 테이블로 옮기는 마이그레이션은 이 작업 범위가 아니다.
- 테스트 3개 클래스는 `@Import(MessageTestConfig::class)`로 같은 Spring 컨텍스트를 공유한다. 설정이 다른 컨텍스트가 늘어나면 같은 H2 메모리 DB(`testdb`)를 `create-drop`으로 공유하므로 주의.
