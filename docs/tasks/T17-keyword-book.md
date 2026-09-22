# T17 키워드북

- **상태**: TODO
- **웨이브**: 5
- **의존**: T13
- **브랜치**: `task/T17-keyword-book`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §8.3 / 결정 D11

## 목표
대화에 키워드가 나오면 `keywords.md`의 해당 설정을 자동으로 넣는다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/prompt/keyword/KeywordBookContributor.kt` 등(`prompt/keyword/**`에 추가)
- `data/_templates/keywords.md`(신규)
- 관련 테스트

## 구현 내용
1. 스토리의 `keywords.md`를 `KeywordBookParser`(T06)로 읽는다. 매번 파일을 읽어도 되고, 수정 시각 기준으로 캐시해도 된다.
2. `KeywordMatcher`로 `PromptContext.recentText`를 매칭하고, 상위 `keyword-max-active`개를 KEYWORDS 슬롯에 넣는다. 형식은 `### {제목}\n{내용}`.
3. `prompt-preview`에 발동한 키워드 목록을 표시한다(디버깅, T19/T15에서 활용 가능).

## 완료 조건
- [ ] `./gradlew test` 통과: 발동, 우선순위, 최대 개수, 미언급 제외, 파일이 없을 때
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그
