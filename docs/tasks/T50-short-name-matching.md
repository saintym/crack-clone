# T50 짧은 이름 인물이 프롬프트에서 빠지는 문제

- **상태**: DONE
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
- [x] 1글자 이름이 낱말 처음에 오면 매칭된다(`린이`, `토오사카 린은`, `"린."`)
- [x] 낱말 안쪽은 매칭되지 않는다(`그린`, `바이올린`)
- [x] 2글자 이상 키의 동작이 바뀌지 않는다(기존 테스트 전부 통과)
- [x] `settings.json`의 `alwaysActive`가 활성 인물에 들어간다
- [x] 이름이 파일명과 다르고 별칭에만 있어도 찾는다
- [x] 모르는 이름은 버리고 경고를 남긴다
- [x] 값이 없으면 기존 동작 그대로다
- [x] `./gradlew test` 전부 통과 (646개, 실패 0 — 기준선 632 + 새 테스트 14)
- [x] 상태 `REVIEW` + 작업 로그

## 착수 전에 볼 것
`KeywordMatcher`는 **키워드북(§8.3)도 함께 쓴다.** 1글자 키워드 항목이 생길 수 있으므로 같은 규칙이 거기에도 적용된다. 키워드북에 의도치 않은 영향이 없는지 확인하고 판단을 작업 로그에 적어라.

## 작업 로그

### 2026-09-30

**한 일**
- `KeywordMatcher`: 1글자 키를 버리던 `MIN_KEY_LENGTH = 2`를 없애고 `SHORT_KEY_LENGTH = 1`로 바꿨다.
  1글자 키는 `containsAtWordStart`로 **낱말 처음에서만** 맞고(앞 코드 포인트가 글자·숫자면 버린다),
  2글자 이상 키는 예전 `haystack.contains(key)` 그대로다. 빈 키는 여전히 버린다(`contains("")`가 늘 참이므로 꼭 필요하다).
- `StorySettings`: `alwaysActive: List<String> = emptyList()` 필드와 `StorySettings.alwaysActive(storyDir)`(공백 정리·빈 이름 제거·중복 제거)를 넣었다.
- `ActiveCharacterSelector`: 동행 인물 다음, 키워드 매칭 앞에 `alwaysActive`를 넣는다. 이름 해석은 `companions`와 같은
  헬퍼(`resolve`)를 쓰고, 파일명·별칭 어디에도 없으면 버리며 **경고 로그**를 남긴다. `settings.json`을 읽다 터져도 턴을 실패시키지 않는다.
- `data/_templates/settings.json`에 `_alwaysActive` 안내와 `"alwaysActive": []`를 넣었다(기본값은 예전 동작).
- 문서: `docs/DESIGN.md` §6.2(순서·`alwaysActive`·1글자 이름), §6.4(같은 파일의 다른 필드), §8.3(1글자 키워드 주의), §8.4(1글자 규칙 표와 남는 오탐), `Plan-roadmap.md` D48.
- 테스트: `KeywordMatcherTest`(1글자 낱말 경계·코드 포인트·2글자 회귀), 빈 껍데기였던 `ActiveCharacterSelectorTest`(alwaysActive 8개),
  `StorySettingsTest`(alwaysActive 파싱 3개). 총 646개 통과.

**설계 판단과 이유**
- **경계는 "앞 글자가 글자(letter)나 숫자"로 판정한다.** 명세는 "앞 글자가 한글 음절이면 안 맞는다"인데,
  한글 음절은 `Character.isLetterOrDigit`에 포함되므로 명세 표의 한국어 사례는 그대로 만족하고, 영문·숫자 뒤(`bar`의 `a`)까지 더 조인다.
  문장부호·공백·따옴표·이모지는 경계로 본다. 앞을 코드 포인트 단위로(`codePointBefore`) 보므로 이모지 앞에서도 맞다.
- **뒤는 보지 않는다**(명세대로). 조사 때문이다. 그래서 `렌즈`·`린스`처럼 **그 글자로 시작하는 낱말**은 여전히 걸린다.
  낱말 사전 없이는 더 줄일 수 없고, 활성 인물 한 명이 더 드는 비용(약 1,460자)이 문서가 통째로 빠지는 비용보다 작다고 봤다.
- **`MIN_KEY_LENGTH`는 남기지 않고 지웠다.** 저장소 안에 다른 참조가 없었고, 이름을 남기면 "이 미만은 버린다"는 옛 뜻으로 읽힌다.
- `alwaysActive`는 `List<String>` 기본 빈 목록으로 뒀다(널 허용 필드가 아니다). `responseChars`·`maxCharacterImages`는
  전역 기본값으로 폴백해야 해서 널이 의미가 있지만, 이 필드는 "비어 있음"이 곧 기본 동작이다. Jackson `NullIsSameAsDefault`로 `null`도 빈 목록이 된다.
- 상한(T44)은 건드리지 않았다. `alwaysActive`도 상한에 걸린다 — 동행 인물 다음 자리이므로 키워드로 걸린 인물보다 먼저 남는다(테스트로 고정).

**키워드북(§8.3)에 미치는 영향 — 확인한 것**
- `KeywordMatcher`는 키워드북(`KeywordBook`)도 같이 쓴다. **1글자 키워드는 예전에 100% 죽은 키였고, 이제 낱말 처음에서 살아난다.**
  즉 **없던 매칭이 생길 수는 있어도, 있던 매칭이 사라지지는 않는다**(2글자 이상 경로를 손대지 않았다).
- 실질 위험은 "1글자 키워드를 적어 둔 시나리오에서 항목이 새로 발동하는 것"뿐이다. 동시 발동 수가 `crack.prompt.keyword-max-active`(기본 3)로
  묶여 있어 폭주는 없지만, **1글자 키워드가 잡히면 위에 있는 항목이 자리를 차지해 아래 항목이 밀릴 수 있다.**
  `keywords.md` 템플릿과 픽스처에는 1글자 키워드가 없고(`흑풍채`, `산적`, `청운객잔`, `객잔`), 실제 시나리오에서도 1글자 키워드는 쓰지 않는 편이 낫다
  — 한 글자는 이름에는 필요하지만 개념·지명 키워드로는 오탐이 커서다. §8.3에 그 주의를 한 줄 적었다.
- `KeywordBookPreviewTest`·`KeywordBookContributorTest`를 포함해 기존 테스트 전부 그대로 통과했다.

**확인 방법**
- `cd crack-backend && ./gradlew test` → 646개 전부 통과, 실패·오류 0(기준선 632 + 추가 14).
- 매칭 규칙은 명세 표의 다섯 사례를 그대로 테스트로 옮겼고(`그린`·`바이올린`·`린이`·`토오사카 린은`·`"린."`),
  2글자 회귀 방지 테스트(`가설월광천마신교`)를 따로 뒀다. `훈련`은 `련`이 `린`과 다른 음절이라 애초에 걸리지 않는다 — 테스트 주석에 적었다.
- 실제 시나리오 데이터가 없는 환경이라 「내애를낳아줘」 재현은 못 했다. 대신 픽스처 스토리에 `characters/렌.md`를 만들어 같은 상황을 재현했다.

**다음 작업자가 알아야 할 것**
- `ActiveCharacterSelectorTest.kt`는 T44가 **빈 파일(0바이트)로 커밋**해 둔 것이었다. 이번에 T50 몫(alwaysActive·한 글자 이름)만 채웠고,
  기본 규칙(동행 인물·별칭·상한) 테스트는 여전히 `PromptContributorsTest`에 있다. 옮길지는 따로 판단할 일이다.
- **프론트는 손대지 않았다**(T49 병렬 작업). 기억 패널의 "이 스토리의 설정"에서 `alwaysActive`를 고르게 하려면 별도 작업이 필요하다.
  지금은 `settings.json`을 문서 API로 직접 고치면 반영된다(화이트리스트는 파일 단위라 그대로 통한다).
- 남는 오탐은 **그 글자로 시작하는 낱말**이다(`렌즈`, `린스`, `린넨`). 실제로 거슬리면 인물 문서 별칭을 2글자 이상으로 두거나
  `alwaysActive`로 넣는 것이 답이다. 낱말 사전을 들이는 방향은 비용이 크다.

### 2026-09-30 머지 후 실검증 (오케스트레이터)
실제 시나리오 「내애를낳아줘」(인물 `렌` 1글자)로 확인했다.

```
settings.json에 "alwaysActive": ["시즈카","렌"] 추가
새 스토리 생성 (state.json.companions는 비어 있는 상태)
→ 활성 인물: ['시즈카', '렌', '겐이치로']
```

**고치기 전에는 `['겐이치로','시즈카']`로 나와 렌의 문서가 통째로 빠졌다.** 이제 `companions`를 손으로 넣지 않아도 잡힌다.

**T44가 `ActiveCharacterSelectorTest.kt`를 0바이트 빈 파일로 커밋한 것**도 이 작업에서 드러났다(머지한 사람이 놓쳤다). T50이 8개 테스트로 채웠다. 다음부터 머지할 때 **새로 추가된 테스트 파일이 비어 있지 않은지** 보는 것이 좋다.
