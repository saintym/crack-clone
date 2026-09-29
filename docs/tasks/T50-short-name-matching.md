# T50 짧은 이름 인물이 프롬프트에서 빠지는 문제

- **상태**: TODO
- **웨이브**: 8
- **의존**: T06(키워드 매칭), T29(스토리 설정), T44(활성 인물 상한)
- **브랜치**: `task/T50-short-name-matching`
- **마이그레이션**: 없음
- **결정**: D48

## 증상
**이름이 한 글자인 인물이 활성 인물로 잡히지 않아 문서가 통째로 프롬프트에서 빠진다.**

실제 사례 — 시나리오 「내애를낳아줘」에서 프롤로그에 `렌`이 분명히 나오는데:
```
활성 인물: ['겐이치로', '시즈카']     ← 렌 없음
```
페이트 시나리오의 `린`도 같은 문제를 겪었다. T44 작업 중 테스트가 NPE로 죽은 원인도 이것이었다(테스트 인물 이름을 `가`,`나`,`다`로 지었다).

## 원인
`KeywordMatcher.matches`가 **1글자 키를 아예 버린다.**
```kotlin
if (key.codePointCount(0, key.length) < MIN_KEY_LENGTH) return false   // MIN_KEY_LENGTH = 2
return haystack.contains(key)
```
매칭이 단순 `contains`라서, 1글자를 허용하면 `그린`의 `린`, `훈련`의 `련`처럼 **낱말 안쪽에 우연히 든 글자**가 전부 걸린다. 그래서 막아 둔 것이다. 규칙 자체는 타당했는데 **짧은 이름을 쓸 수 없다는 부작용**이 드러났다.

## 범위
- `crack-backend/src/main/kotlin/com/crack/prompt/keyword/KeywordMatcher.kt`
- `crack-backend/src/main/kotlin/com/crack/story/settings/StorySettings.kt`
- `crack-backend/src/main/kotlin/com/crack/prompt/contributor/ActiveCharacterSelector.kt`
- `data/_templates/settings.json`
- `docs/DESIGN.md`(§6.2 활성 인물 선택, §6.4 스토리 설정), `Plan-roadmap.md`(D48)
- 테스트

**`prompt/contributor/BaseContributor.kt`·`CharactersContributor.kt`는 건드리지 않는다.**
**`crack-frontend/` 는 건드리지 않는다**(T49가 병렬로 작업 중이다).

## 구현 내용

### 1. 1글자 키는 낱말 경계로 맞춘다
길이 2 이상인 키는 **지금 동작을 그대로 둔다**(회귀 금지). 1글자 키만 규칙을 바꾼다.

**앞 글자가 한글 음절이면 맞지 않는 것으로 본다.**

| 본문 | 키 `린` | 이유 |
|---|---|---|
| `그린` | ✗ | 앞이 `그` |
| `훈련은` | ✗ | 앞이 `훈` |
| `린이 말했다` | ✓ | 문장 처음 |
| `토오사카 린은` | ✓ | 앞이 공백 |
| `"린."` | ✓ | 앞이 따옴표 |

**뒤 글자는 보지 않는다.** 한국어는 조사가 붙으므로(`린이`, `렌은`, `렌을`) 뒤를 막으면 대부분 놓친다.

남는 오탐: **그 글자로 시작하는 낱말**(`렌즈`, `린스`). 완전히 막을 수 없다. 아래 2번이 탈출구다.

`MIN_KEY_LENGTH` 상수는 의미가 바뀌므로 이름과 KDoc을 정리한다.

### 2. 시나리오가 "항상 곁에 있는 인물"을 선언한다
`settings.json`에 목록을 둔다.
```json
{ "alwaysActive": ["시즈카", "렌"] }
```
- `ActiveCharacterSelector`가 **동행 인물(`state.json.companions`) 다음, 키워드 매칭 앞**에 넣는다. 순서가 곧 우선순위이므로(T44 상한 6명) 늘 붙어 있는 인물이 먼저 남는다
- 이름은 **파일명과 먼저 비교하고, 없으면 별칭**과 대조한다(`companions`와 같은 규칙). 둘 다 없으면 버리고 경고 로그를 남긴다
- **`settings.json`은 스토리를 만들 때 복사된다**(`StoryFiles.COPIED_FILES`). 그래서 새 스토리마다 손댈 필요가 없다 — 지금은 `state.json.companions`를 매번 손으로 넣어야 한다
- 값이 없으면 지금과 똑같이 동작한다

전속 호위·동료처럼 **장면에 늘 있는 인물**을 키워드 운에 맡기지 않는 것이 목적이다. 짧은 이름 문제와 무관하게 유용하다.

### 3. 템플릿
`data/_templates/settings.json`에 주석과 함께 `alwaysActive` 예시를 넣는다(지금 `responseChars`·`maxCharacterImages`가 들어 있는 방식 그대로).

## 완료 조건
- [ ] 1글자 이름이 낱말 처음에 오면 매칭된다(`린이`, `토오사카 린은`, `"린."`)
- [ ] 낱말 안쪽은 매칭되지 않는다(`그린`, `훈련`)
- [ ] 2글자 이상 키의 동작이 바뀌지 않는다(기존 테스트 전부 통과)
- [ ] `settings.json`의 `alwaysActive`가 활성 인물에 들어간다
- [ ] 이름이 파일명과 다르고 별칭에만 있어도 찾는다
- [ ] 모르는 이름은 버리고 경고를 남긴다
- [ ] 값이 없으면 기존 동작 그대로다
- [ ] `./gradlew test` 전부 통과 (기준선 632개)
- [ ] 상태 `REVIEW` + 작업 로그

## 착수 전에 볼 것
`KeywordMatcher`는 **키워드북(§8.3)도 함께 쓴다.** 1글자 키워드 항목이 생길 수 있으므로 같은 규칙이 거기에도 적용된다. 키워드북에 의도치 않은 영향이 없는지 확인하고 판단을 작업 로그에 적어라.

## 작업 로그
