# T13 프롬프트 조립 v2

- **상태**: IN_PROGRESS
- **웨이브**: 4
- **의존**: T05, T06, T07, T08
- **브랜치**: `task/T13-prompt-v2`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §6 / 결정 D9, D10

## 목표
매 턴 프롬프트를 **필요한 것만** 담도록 다시 만든다. LLM 사전 호출은 하지 않는다. 뒤따르는 기능이 빈 하나만 추가하면 되도록 기여자(contributor) 구조를 도입한다.

## 범위
- `prompt/**` (PromptAssembler 재작성, 신규 `prompt/contributor/**`)
- `chat/flow/ConversationBuilder`(T07)의 원문 범위 로직
- 신규 `prompt/api/PromptPreviewController`
- 관련 테스트

## 구현 내용
1. `PromptContributor`, `PromptSlot`, `PromptContext`를 DESIGN.md §6 그대로 만든다. 조립기는 슬롯 순서대로 모은다.
2. **기본 기여자:** BASE(기본 규칙 + 출력 형식), WORLD, SCENARIO(scenario.md + 연대기), PROTAGONIST, CHARACTERS(§6.2 활성 인물), USER_NOTE
   - **BASE 규칙 보강:** 유저 입력의 `**…**`는 상황 묘사이고 `"…"`는 대사라는 설명을 추가한다
   - **연대기 넣는 방식:** `## 장 요약` + 최근 회차 원문(연대기 예산 안에서)
3. **활성 인물 (D9):** `StoryState.companions` ∪ `KeywordMatcher`(인물 파일명과 별칭, 최근 N=6 메시지와 이번 입력). N은 `crack.prompt.keyword-scan-messages`로 설정한다.
4. **대화 원문 범위:** DESIGN.md §6.1(`recorded_through_turn - overlap`, 상한 `max-raw-turns`). T14 이전이면 `recorded_through_turn`을 0으로 본다. T14가 먼저 머지됐으면 그 필드를 쓴다.
5. **BOTTOM 슬롯:** 마지막 유저 메시지 앞에 `[지시]` 블록으로 붙인다. 이번 턴 지시(`turnInstruction`: 이어쓰기, 재생성 지시, `/` 명령)를 여기에 넣는 기여자를 만든다. 지속 지시 기여자는 T16이 추가한다.
6. **측정**
   - 섹션별 글자 수를 INFO 로그로 남긴다
   - `GET /api/stories/{id}/prompt-preview`로 섹션별 크기와 전문을 돌려준다
   - 샘플 픽스처로 v1(모든 인물 주입)과 v2의 크기를 비교한 값을 작업 로그와 `Plan-roadmap.md` §2.4에 기록한다

## 완료 조건
- [ ] `./gradlew test` 통과
- [ ] 테스트 항목
  - 슬롯 순서
  - 동행 인물과 별칭 매칭으로 인물 선택(언급 없는 인물 제외)
  - 원문 범위 경계
  - BOTTOM 위치
  - preview 응답
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T13-prompt-v2`(worktree).
