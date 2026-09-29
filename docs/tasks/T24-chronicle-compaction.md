# T24 연대기 압축 — 회차 수 기준 트리거

- **상태**: TODO
- **웨이브**: 8
- **의존**: T14 (기억 기록 파이프라인)
- **브랜치**: `task/T24-chronicle-compaction`
- **마이그레이션**: 없음
- **결정**: D22(수정), D45

## 목표
연대기가 프롬프트에서 두 번째로 큰 덩이인데 **압축이 아예 돌지 않고 있다.** 회차 수 기준 트리거를 더해 오래된 회차가 `## 장 요약`으로 접히게 한다.

## 실측 (212턴 스토리, `봄의귀환이후` id=18)
```
시스템 프롬프트 34,448자 중
  characters  12,779
  chronicle    8,363   ← 두 번째로 크다
  protagonist   4,548
  scenario      3,470
```
연대기 안을 뜯어보면:
```
전체 8,347자
  ## 장 요약              2,121   ← t1–101을 압축한 것
  ## 회차 11 ~ 회차 21    6,219   ← t102–211, 11개가 원문 그대로 (평균 565자)
```

**왜 압축이 안 돌았나.** `MemoryRecordPipeline`의 트리거가 `budgets.exceeds(chronicle)` 하나뿐이고, 그 기준은 **회차 원문 글자 수 vs `crack.memory.budget.chronicle`(12,000)** 이다. 지금 6,219자라 한참 밑이다. 그래서 212턴을 돌고도 한 번도 압축되지 않았다.

글자 예산만으로는 부족하다. 회차가 계속 쌓이는데 12,000자에 닿기 전까지는 **전부 원문으로 매 턴 프롬프트에 들어간다.**

## 범위
- `memory/record/MemoryRecordProperties.kt`(새 설정), `MemoryRecordPipeline.kt`(트리거와 압축량)
- `crack-backend/src/main/resources/application.yml.example`
- `docs/DESIGN.md`(§7 파이프라인), `Plan-roadmap.md`(D45)
- 관련 테스트

**이 작업은 `prompt/` 아래를 건드리지 않는다.** T46·T47과 병렬로 진행한다.

## 구현 내용

### 1. 설정 추가
`MemoryRecordProperties`에 `chronicleMaxEntries: Int = 4`를 더한다(글자 수가 아니라 **개수**다).
```yaml
crack:
  memory:
    record:
      chronicle-max-entries: 4   # 원문으로 남길 최근 회차 수. 넘으면 오래된 것부터 장 요약으로 접는다
```
0 이하면 이 트리거를 끈다(글자 예산만 쓴다).

### 2. 트리거
```kotlin
if (budgets.exceeds(newChronicle) || overEntryLimit(newChronicle)) newChronicle = compressChronicle(newChronicle)
```

### 3. 압축량
`compressChronicle`이 지금은 **절반**(`entries.size / 2`)을 접는다. 개수 트리거로 걸렸으면 **목표 개수까지** 접어야 한다.
```kotlin
val target = properties.chronicleMaxEntries.coerceAtLeast(1)
val n = maxOf(entries.size - target, entries.size / 2).coerceIn(1, entries.size - 1)
```
- 개수로 걸리면 `size - target`만큼 접어 한 번에 목표에 닿는다
- 글자 예산으로 걸렸고 회차가 적으면 예전처럼 절반을 접는다
- **최소 1개는 남긴다**(전부 접지 않는다)

### 4. 로그
압축이 돌 때 **무엇 때문에 돌았는지**(글자 예산 / 회차 수)와 몇 개를 접었는지 INFO로 남긴다. 지금은 왜 안 도는지 알 길이 없었다.

## 완료 조건
- [ ] 회차가 `chronicle-max-entries`를 넘으면 글자 예산 밑이어도 압축이 돈다
- [ ] 개수로 걸리면 목표 개수까지 한 번에 접는다
- [ ] 글자 예산으로 걸리면 기존 동작(절반)을 유지한다
- [ ] 설정이 0 이하면 개수 트리거를 쓰지 않는다
- [ ] 회차가 1개면 압축하지 않는다
- [ ] 압축 사유와 접은 개수가 로그에 남는다
- [ ] `./gradlew test` 전부 통과
- [ ] 상태 `REVIEW` + 작업 로그

## 주의
- **`## 장 요약`은 LLM이 다시 쓴다.** 접는 개수가 늘면 한 번에 요약할 양이 늘어난다. 프롬프트 입력이 커지지 않는지 확인한다
- 기존 스토리(회차 11개)는 **다음 기록 때 한 번에 접힌다.** 그때 요약 품질이 떨어지지 않는지 봐야 한다 — 필요하면 한 번에 접는 최대 개수를 두는 것도 방법이다(작업 로그에 판단을 남길 것)
- **`## 기억`·`## 알고 있는 것`은 이 작업의 대상이 아니다.** 인물 지식은 D38의 근거라 줄이면 안 된다

## 작업 로그
