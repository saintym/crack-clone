# T47 대화 원문 범위를 턴 수에서 글자 예산으로

- **상태**: REVIEW
- **웨이브**: 8
- **의존**: T13(프롬프트 조립), T29(응답 분량)
- **브랜치**: `task/T47-raw-conversation-budget`
- **마이그레이션**: 없음
- **결정**: D25

## 목표
프롬프트에 넣는 대화 원문 범위를 **"최근 N턴"에서 "최근 N자"로** 바꾼다.

## 왜
턴 길이가 제각각이라 실제 크기를 예측할 수 없다. 실측(D33 전후):
```
720자 · 1,151 · 1,454 · 2,032   ← 같은 스토리의 연속된 네 턴
```
D33으로 800~1,500자 구간에 들어왔지만 여전히 두 배 차이가 난다. 그리고 사용자 입력 길이는 전혀 제약이 없다. **같은 "3턴"이 2,000자일 수도 6,000자일 수도 있다.**

## 범위
- `prompt/config/PromptProperties.kt`(새 설정)
- `chat/flow/ConversationBuilder.kt`, `prompt/service/PromptAssembler.kt`
- `prompt/api/**`(`prompt-preview`에 실제 담긴 턴 수 노출)
- `crack-backend/src/main/resources/application.yml.example`
- `docs/DESIGN.md`(§6), 관련 테스트

**`prompt/contributor/BaseContributor.kt`를 건드리지 않는다.** T46이 그 파일을 고친다.
**`memory/` 아래를 건드리지 않는다.** T24가 거기를 고친다.

## 구현 내용

### 1. 설정
```yaml
crack:
  prompt:
    raw-budget-chars: 6000       # 대화 원문에 쓸 글자 예산
    raw-min-turns: 2             # 예산을 넘겨도 반드시 넣는 최소 턴 수
    max-raw-turns: 30            # 안전 상한(기존 설정을 그대로 둔다)
```

### 2. 담는 규칙
- **최근 턴부터** 거꾸로 담는다. 누적 글자 수가 `raw-budget-chars`를 넘으면 멈춘다
- **턴을 쪼개지 않는다.** 유저 메시지와 그 응답은 같은 턴이므로 함께 넣거나 함께 뺀다
- **예산을 넘겨도 `raw-min-turns`(2)는 반드시 넣는다.** 한 턴이 예산보다 길어도 대화가 끊기면 안 된다
- `max-raw-turns`는 안전 상한으로 남긴다(예산이 아무리 커도 이 이상은 안 넣는다)
- 기존 `overlap-turns`(마지막 기록 턴과 겹쳐 넣는 턴 수)와의 관계를 정리한다. 의미가 없어지면 작업 로그에 이유를 적고 정리한다

### 3. 관측
- `prompt-preview` 응답에 **실제로 담긴 턴 수와 글자 수**를 넣는다(기존 `rawWindow` 필드 활용 또는 확장)
- 조립 때 INFO 로그에도 남긴다

## 완료 조건
- [x] 예산 안에서 최근 턴부터 담긴다
- [x] 턴이 쪼개지지 않는다
- [x] 한 턴이 예산보다 길어도 최소 턴 수는 들어간다
- [x] `max-raw-turns` 상한이 지켜진다
- [x] `prompt-preview`에서 담긴 턴 수와 글자 수를 볼 수 있다
- [x] `./gradlew test` 전부 통과
- [x] 상태 `REVIEW` + 작업 로그

## 작업 로그

### 2026-09-29

**한 일**
- `PromptProperties`: `rawBudgetChars`(6000), `rawMinTurns`(2) 추가. `maxRawTurns`(30)는 안전 상한으로 그대로 두고 KDoc만 보강했다. **`overlapTurns`는 없앴다**(아래 판단 참고).
- `ConversationBuilder.rawWindow`를 글자 예산 계산으로 바꿨다. 순수 함수 시그니처가 바뀌었다:
  `rawWindow(views, recordedThroughTurn, budgetChars, minTurns, maxRawTurns)`.
  최근 턴부터 거꾸로 `turn_no`별 글자 수를 더하다가 예산을 넘는 턴에서 멈추고 그 턴을 `afterTurn`으로 삼는다.
  담긴 구간은 항상 연속이다(예산에 맞는 오래된 턴을 건너뛰어 끼워 넣지 않는다).
- `RawWindow`에 `turnCount`, `chars`(실제로 담긴 턴 수·글자 수)를 더했다.
- `prompt-preview`의 `rawWindow`에 `turnCount`, `chars`를 더했다(기존 `recordedThroughTurn`, `afterTurn`, `messageCount`는 그대로).
- 조립 INFO 로그를 `raw(3턴/5418자, 6개, after=5, recorded=0)` 형태로 바꿨다.
- `application.yml.example`, `docs/DESIGN.md` §6.1(제목 포함)과 §6.3 응답 예시를 고쳤다. `RecordedTurnSource` KDoc도 새 역할에 맞게 고쳤다.

**설계 판단과 이유**
- **`overlap-turns`를 없앴다.** 옛 규칙 `afterTurn = max(recorded − overlap, maxTurn − maxRaw)`는 기록 직후 원문을 2턴으로 잘라버려서,
  글자 예산을 넣어도 예산이 거의 바인딩되지 않는다(기록은 10턴마다 돈다 → 매 10턴 중 상당 구간에서 원문이 2턴). 크기를 예산이 잡기로 한 이상
  "이미 기록했으니 원문에서 뺀다"는 축은 예산과 싸우기만 한다. 최소 보장("몇 턴은 무조건 남긴다")이라는 overlap의 남은 의미는
  `raw-min-turns`가 그대로 받는다. 그래서 설정을 남기지 않고 지웠다.
  기록된 구간이 원문에 남는 것은 중복이 아니라 연속성이다 — 기억 문서는 요약이고 원문은 말투와 세부를 지킨다. 크기는 예산이 막는다.
- **`recordedThroughTurn`은 남겼다.** 이제 쓰임은 하나다: 첫 기록 이후 프롤로그(턴 0)를 뺀다(§6.1 기존 규칙). 프롤로그는 보통 길고 이미 연대기에 있다.
- **`minTurns`는 1 미만으로 내려가지 않게 보정한다.** 0으로 두면 한 턴이 예산보다 길 때 원문이 통째로 비어 대화가 끊긴다.
- **글자 수는 저장된 메시지 `content`의 합만 센다.** 지시(`[지시]` 블록)와 가상 입력(preview)은 예산에 넣지 않았다 — 예산이 제어하려는 것은
  "지난 대화 원문"이고, 지시와 이번 입력은 어차피 항상 들어간다.
- `rawWindow`에 예산 값 자체(`budgetChars`)는 넣지 않았다. 설정에서 바로 볼 수 있고 응답 계약을 늘리고 싶지 않았다.

**확인한 방법**
- `./gradlew test` — 599개 전부 통과(기준선 592 + 새 테스트 8 − 지운 테스트 1).
  새 테스트: 예산 경계, 턴 비분할, 최소 턴 보장, `minTurns` 하한 보정, 안전 상한, 빈 대화, 설정값 반영(`ConversationBuilderTest`),
  조립기 통합에서 예산 초과 턴 제외(`PromptAssemblerTest`).
- 옛 `원문 범위 경계` 테스트는 지웠다(overlap 기반 계산식 검증이라 규칙 자체가 없어졌다). `기록 후에는 recorded - overlap …`은
  `첫 기록 이후에는 프롤로그를 뺀다`로 다시 썼다.
- `prompt-preview` 응답의 `turnCount`/`chars`는 `PromptPreviewApiTest`에서 확인한다.

**다음 작업자가 알아야 할 것**
- **`crack.prompt.overlap-turns`가 남아 있는 로컬 `application.yml`은 부팅이 깨지지 않는다**(모르는 키는 무시). 하지만 의미가 없으니 지우는 게 좋다.
  대신 `raw-budget-chars`, `raw-min-turns`를 추가한다(`application.yml.example` 참고).
- 기록 직후에도 원문이 6,000자까지 들어가므로 **전체 프롬프트가 예전보다 커질 수 있다.** 실제 크기는 `prompt-preview`의
  `rawWindow.chars`와 `totalChars`로 본다. 인물 문서 쪽 다이어트는 T46이 따로 한다.
- 예산은 글자 수(UTF-16 코드 유닛)다. 토큰이 아니다. 토큰으로 바꾸려면 프로바이더별 토크나이저가 필요해 범위 밖으로 두었다.
- `ConversationBuilder.rawWindow` 순수 함수의 인자가 바뀌었다(`maxTurn`, `overlapTurns` → `views`, `budgetChars`, `minTurns`). 이 함수를 쓰는 곳은 조립기와 테스트뿐이다.
