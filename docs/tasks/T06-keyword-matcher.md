# T06 키워드 매칭 엔진

- **상태**: REVIEW
- **웨이브**: 1
- **의존**: 없음
- **브랜치**: `task/T06-keyword-matcher`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §8.4

## 목표
인물 문서 선택(T13)과 키워드북(T17)이 함께 쓰는 매칭 엔진을 만든다. 순수 로직이다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/prompt/keyword/**`
- 신규 `src/test/kotlin/com/crack/prompt/keyword/**`

## 구현 내용
1. `KeywordEntry(id, keys)`, `KeywordMatcher.match(entries, text, limit)`는 DESIGN.md 시그니처를 따른다.
2. **규칙**
   - 부분 문자열 매칭(한국어 조사 대응), 대소문자 무시, 앞뒤 공백 제거
   - 2글자 미만 키는 무시
   - 결과는 입력 순서(=우선순위)를 유지하고 중복을 제거
   - `limit`만큼 자른다
3. **성능:** 엔트리 수백 개와 텍스트 수만 자에서도 빠르게 동작해야 한다. 단순 `contains` 반복으로 충분하면 그대로 두고, 측정값을 테스트 주석에 남긴다.
4. `KeywordBookParser`: DESIGN.md §8.3의 `keywords.md` 형식을 `List<KeywordBookEntry(id=제목, keys, content)>`로 파싱한다. T17이 주입을 구현한다.

## 완료 조건
- [x] `./gradlew test` 통과
- [x] 테스트 항목: 조사 붙은 이름, 짧은 키 무시, 우선순위와 limit, 중복 제거, keywords.md 파싱(키워드 줄 누락과 빈 내용 처리)
- [x] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T06-keyword-matcher`.
- **한 일**
  - `prompt/keyword/KeywordMatcher.kt`: `KeywordEntry(id, keys)`, `KeywordMatcher.match(entries, text, limit = null)` (DESIGN §8.4 시그니처 그대로). `@Component`라 T13/T17에서 주입해 쓰면 된다(`KeywordMatcher()` 직접 생성도 가능).
  - `prompt/keyword/KeywordBookParser.kt`: `KeywordBookEntry(id, keys, content)` + `toKeywordEntry()`, `KeywordBookParser.parse(text)` (object).
  - 테스트 16개(매처 9, 파서 7).
- **설계 판단**
  - 매칭: 텍스트와 키를 `lowercase(Locale.ROOT)`로 맞춘 뒤 `contains`. 키는 `trim` 후 **코드 포인트 기준** 2글자 미만이면 무시(이모지 1개가 UTF-16 2 char라 `length`로 세면 통과해 버림).
  - 중복 id는 결과에 처음 매칭된 위치에서 한 번만 들어가고 limit도 한 번만 센다. `limit <= 0`이면 빈 목록. limit에 닿으면 즉시 멈춘다.
  - 성능: 500엔트리(키 3개씩) × 약 3만 자에서 평균 약 16ms(로컬 측정). 충분해서 Aho-Corasick은 쓰지 않았다. 테스트 단언은 CI 편차를 고려해 200ms 미만으로 넉넉히 잡았다.
  - 파서: 섹션 분리는 T05의 `com.crack.memory.docs.MarkdownSections.sections(text, 2)`를 재사용했다(코드 펜스, CRLF, BOM 처리 포함). 읽기만 하고 수정하지 않았다.
  - 키워드 줄은 **본문의 첫 비어 있지 않은 줄**만 인정한다(`키워드:`, 콜론 앞뒤 공백·전각 `：` 허용). 구분자는 `,` `，` `、`. 빈 키·중복 키 제거.
  - 키워드 줄 누락(또는 `키워드:` 뒤가 빈 경우) → 제목을 유일한 키로 쓴다. 제목 자체가 대표 키워드인 경우가 대부분이라서다. DESIGN에 명시가 없어 여기서 정했다.
  - 내용이 비면 항목을 건너뛴다(주입할 게 없는데 동시 발동 수를 차지하므로). 같은 제목이 여럿이면 첫 항목만(위가 우선, id 유일성).
  - content는 키워드 줄 뒤 본문을 `trim()`하고 줄바꿈을 LF로 통일한다. `###` 하위 제목은 content에 포함된다.
- **확인 방법**: `./gradlew test` 전체 213개 통과(신규 16개 포함).
- **다음 작업자 주의점**
  - T17: `keyword-max-active`는 `match(..., limit = n)`으로 넘기면 된다. 파서가 이미 빈 항목을 걸러 주므로 결과 id로 `KeywordBookEntry`를 찾아 content를 주입하면 된다.
  - T13: 인물 문서는 `KeywordEntry(이름, listOf(이름, 별칭...))`로 만들면 된다. 1글자 이름·별칭은 매칭되지 않는다(규칙상 의도).
  - 부분 문자열 매칭이라 "신교"가 "신교육"에도 걸리는 식의 오탐은 규칙상 감수한다.
