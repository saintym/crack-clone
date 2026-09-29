# T47 대화 원문 범위를 턴 수에서 글자 예산으로

- **상태**: TODO
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
- [ ] 예산 안에서 최근 턴부터 담긴다
- [ ] 턴이 쪼개지지 않는다
- [ ] 한 턴이 예산보다 길어도 최소 턴 수는 들어간다
- [ ] `max-raw-turns` 상한이 지켜진다
- [ ] `prompt-preview`에서 담긴 턴 수와 글자 수를 볼 수 있다
- [ ] `./gradlew test` 전부 통과
- [ ] 상태 `REVIEW` + 작업 로그

## 작업 로그
