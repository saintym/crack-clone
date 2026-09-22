# T01 AI 계층 리팩터링

- **상태**: IN_PROGRESS
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
- [ ] `./gradlew test` 통과
- [ ] 단위 테스트: Gateway 기본/지정 프로바이더 라우팅, Fake 스트림의 delta 순서와 complete 1회, CLI stream-json 파서(샘플 라인 픽스처로 `stream_event`/`assistant`/`result` 처리, 중복 없음)
- [ ] `grep -rn "claudeService\." crack-backend/src/main` 결과가 ai 패키지 밖에서 0건
- [ ] 이 파일의 상태를 `REVIEW`로 바꾸고 작업 로그를 쓴 뒤 PR

## 작업 로그

### 2026-09-23
- 작업 시작. DESIGN.md §4 계약 기준으로 AI 계층 리팩터링 착수.
