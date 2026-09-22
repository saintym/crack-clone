# T06 키워드 매칭 엔진

- **상태**: IN_PROGRESS
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
- [ ] `./gradlew test` 통과
- [ ] 테스트 항목: 조사 붙은 이름, 짧은 키 무시, 우선순위와 limit, 중복 제거, keywords.md 파싱(키워드 줄 누락과 빈 내용 처리)
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T06-keyword-matcher`.
