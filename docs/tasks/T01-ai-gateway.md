# T01 AI 계층 리팩터링

- **상태**: REVIEW
- **웨이브**: 1
- **의존**: 없음
- **브랜치**: `task/T01-ai-gateway`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §4

## 목표
모든 AI 호출을 `AiGateway` 하나로 모은다. 목표는 세 가지다.
- 기본 프로바이더(CLI)에서도 요약과 기억 기능이 동작하게 한다.
- 스트리밍을 SseEmitter가 아닌 콜백 방식으로 바꾼다. 그래야 T07에서 서버가 응답을 저장할 수 있다.
- CLI·API 키가 없는 리모트 환경을 위해 Fake 프로바이더를 추가한다.

## 범위 (수정 가능한 파일)
- `crack-backend/src/main/kotlin/com/crack/ai/**`
- AI를 호출하는 곳의 **호출부만**: `chat/service/ChatService.kt`, `memory/service/MemoryService.kt`, `memory/service/StorySummaryService.kt`, `context/service/ContextService.kt`
  - `ClaudeService` 직접 호출을 `AiGateway`로 바꾸고, SseEmitter 연결은 어댑터로 감싼다. 로직은 바꾸지 않는다.
- `src/main/resources/application.yml.example`, `src/test/resources/application-test.yml`
- `src/test/kotlin/com/crack/ai/**` (신규 테스트)

## 구현 내용
1. `AiPurpose`, `StreamListener`, `AiProvider.stream(request, listener)`, `AiGateway`를 DESIGN.md §4 시그니처 그대로 만든다. `AiRequest.modelTier`는 `purpose`로 대체한다. 매핑은 `crack.ai.purpose-tiers`로 설정할 수 있게 한다.
2. **ClaudeApiProvider:** `ClaudeService`의 SDK 호출을 프로바이더로 흡수한다(ClaudeService는 삭제하거나 내부 구현으로만 남긴다). 키가 없으면 `isAvailable() = false`로 두고, 레지스트리는 사용 가능한 프로바이더만 노출한다.
3. **ClaudeCodeCliProvider:**
   - 프롬프트를 stdin으로 넘긴다.
   - `--include-partial-messages`를 쓰고 `stream_event`의 `content_block_delta` 텍스트를 파싱한다. 옛 `assistant` 블록이나 `result` 폴백은 중복 출력 없이 유지한다.
   - `--model <alias>`를 추가한다.
   - `crack.ai.cli.path` / `crack.ai.cli.timeout-seconds` 설정을 받고, `/Users/...` 하드코딩을 제거한다.
4. **FakeAiProvider:** `crack.ai.fake.enabled=true`일 때만 등록한다.
   - `FakeResponses`: purpose별 응답 등록(테스트와 다른 작업에서 사용). 기본 응답은 짧은 롤플레이 문장이다.
   - `stream`은 응답을 3~5조각으로 나눠 delta를 보낸 뒤 complete를 호출한다.
5. 기존 SSE 동작은 유지한다. `SseStreamListener`(SseEmitter 어댑터, 이벤트 `delta`/`done`/`error`)를 만들어 기존 ChatService가 계속 동작하게 한다. T07이 이 어댑터를 교체한다.
6. 예시 설정의 모델 ID를 갱신한다: opus `claude-opus-5`, sonnet `claude-sonnet-5`, haiku `claude-haiku-4-5-20251001`.
7. 테스트 프로필에서는 `crack.ai.fake.enabled=true`, 기본 프로바이더를 `fake`로 둔다.

## 완료 조건
- [x] `./gradlew test` 통과
- [x] 단위 테스트: Gateway 기본/지정 프로바이더 라우팅, Fake 스트림의 delta 순서와 complete 1회, CLI stream-json 파서(샘플 라인 픽스처로 `stream_event`/`assistant`/`result` 처리, 중복 없음)
- [x] `grep -rn "claudeService\." crack-backend/src/main` 결과가 ai 패키지 밖에서 0건
- [ ] 이 파일의 상태를 `REVIEW`로 바꾸고 작업 로그를 쓴 뒤 PR

## 작업 로그

### 2026-09-23
- 작업 시작. DESIGN.md §4 계약 기준으로 AI 계층 리팩터링 착수.

**한 일**
- `AiPurpose`, `StreamListener`, `AiProvider.stream(request, listener)`, `AiGateway`를 §4 시그니처대로 추가. `AiRequest.modelTier` → `purpose`(기본 `UTILITY`).
- `AiProperties`(`crack.ai.*`): `default-provider`, `purpose-tiers`, `cli.path|timeout-seconds|models.*`, `fake.enabled`. `AiConfig`에서 등록(global `AppConfig`는 손대지 않음).
- `ClaudeService` 삭제, SDK 호출은 `ClaudeApiProvider`로 흡수. 키가 없으면 `isAvailable()=false`.
- `AiProviderRegistry`: 사용 가능한 프로바이더만 노출. 기본값이 사용 불가면 첫 사용 가능 프로바이더로 대체, 하나도 없으면 `IllegalStateException`.
- `ClaudeCodeCliProvider`: 프롬프트 stdin 전달, `--model <alias>`, `--include-partial-messages`, 워치독 타임아웃(`timeout-seconds`, 기본 300), stderr 별도 소비, `/Users/...` 하드코딩 제거. 파싱은 `CliStreamJsonParser`로 분리.
- `FakeAiProvider`/`FakeResponses`(`crack.ai.fake.enabled=true`일 때만 빈 등록).
- `SseStreamListener`(이벤트 `delta`/`done`/`error`)로 기존 ChatService SSE 동작 유지.
- 호출부 교체: ChatService(CHAT), MemoryService 요약·캐릭터 갱신(RECORD), StorySummaryService(RECORD), ContextService(UTILITY). 로직은 그대로.
- 예시 설정 모델 ID 갱신, 테스트 프로필에 fake 기본 지정.

**설계 판단**
- `SafeStreamListener`: 게이트웨이가 리스너를 감싸 종료 콜백 정확히 1회를 강제하고, 리스너 예외가 프로바이더 스레드를 죽이지 않게 한다. `stream()` 동기 예외(프로바이더 없음, API 키 없음 등)도 `onError`로 바꾼다.
- 지정한 프로바이더가 없거나 사용 불가면 기존 동작처럼 기본 프로바이더로 대체하고 warn 로그를 남긴다.
- `purpose-tiers`는 `Map<String,String>`으로 받아 대소문자 무시로 해석한다(enum 키 바인딩 변환 문제 회피). 잘못된 값은 기본 매핑으로.
- CLI 파서 중복 방지: partial(`stream_event` 텍스트 delta)을 한 번이라도 받으면 `assistant` 블록은 무시, `result`는 텍스트를 하나도 못 받았을 때만 폴백. `is_error: true` result는 텍스트가 없으면 `onError`.
- CLI 스트림은 종료 코드가 0이 아니고 텍스트가 없으면 `onError`(기존은 빈 `done`). 텍스트가 있으면 complete.
- CLI 동기 `chat()`은 `--output-format json`의 `result` 필드를 쓴다(기존과 동일, stdin만 변경).
- Fake 스트림 조각 수: `(코드포인트 수 / 20)`을 3~5로 제한(짧으면 글자 수만큼). 서러게이트 쌍은 쪼개지 않는다. 기본 생성자는 데몬 스레드 풀로 비동기, 테스트는 동기 Executor 주입 가능.
- 테스트 프로필에서 `claude.api-key`를 빈 값으로 바꿔 claude-api가 노출되지 않게 했다(실수로 실제 호출하지 않도록).

**`stream_event` 형식 확인**: 로컬 Claude Code 2.1.278 번들에서 `yield{type:"stream_event",event:Es}`를 확인했다. `event`는 Anthropic 원시 스트림 이벤트(`content_block_delta` + `delta.type=text_delta`)다. 실제 CLI를 돌려 캡처한 것은 아니고, 테스트 픽스처는 이 형식을 따라 손으로 쓴 샘플 라인이다(`session_id` 등 부가 필드는 파서가 쓰지 않음).

**범위 밖 수정 (불가피)**
- `chat/service/SseStreamListener.kt` 신규: §4가 "SseEmitter는 AI 계층에서 없앤다"고 해서 ai 패키지에 둘 수 없었다. ChatService.kt 안에 넣으면 T02와 충돌 위험이 커서 별도 파일로 분리. T07이 교체/삭제한다.
- `src/test/kotlin/com/crack/{memory/MemoryServiceTest, memory/StorySummaryServiceTest, context/ContextServiceTest}.kt`: `ClaudeService` 목을 `AiGateway` 목으로 바꾸는 기계적 치환만. `aiGateway.chat(request)`는 기본 인자 때문에 `chat(request, null)`로 호출되므로 스텁/검증을 `chat(any(), anyOrNull())`로 바꿨다.

**확인 방법**
- `./gradlew test`: 135개 전부 통과(기존 109 + 신규 26).
- 로컬 전용 `application.yml`을 잠시 치운 상태로 `./gradlew clean test`도 통과(리모트와 같은 조건에서 `@SpringBootTest` 컨텍스트가 뜨는지 확인).
- `grep -rn "claudeService\." crack-backend/src/main` → ai 패키지 밖 0건. ai 패키지에 `SseEmitter` 0건, `/Users/` 하드코딩 0건.
- 실제 CLI/API 호출로 수동 확인은 하지 않았다.

**다음 작업자 주의점**
- 로컬 `application.yml`에서 CLI가 PATH에 없으면 `crack.ai.cli.path`에 절대 경로를 넣어야 한다(예전의 자동 경로 탐색을 없앴다). 옛 키 `crack.ai.claude-cli-path`는 더 이상 읽지 않는다.
- 로컬 `application.yml`의 `claude.*` 모델 ID는 옛 값이면 그대로 쓰인다. 예시 파일을 보고 갱신할 것.
- T07: `SseStreamListener`를 서버 저장 흐름으로 교체. `AiGateway.stream`은 비동기이며 리스너 콜백은 프로바이더 스레드에서 온다.
- T14: 기록용 fake 응답은 `FakeResponses.register(AiPurpose.RECORD, ...)`로 등록. 테스트 간 누수 방지를 위해 `reset()` 호출 권장.
- 목에서 `AiGateway.chat`/`stream`을 스텁할 때는 기본 인자 때문에 `anyOrNull()`을 마지막 인자로 넣어야 한다.
