# T17 키워드북

- **상태**: IN_PROGRESS
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
3. `prompt-preview`에 발동한 키워드 목록을 표시한다(디버깅, T19/T15에서 활용 가능). 현재 `AssembledPrompt`와 `PromptPreviewResponse`에는 이 필드가 없으므로 추가한다(T13 메모). 기여자는 `@Component` 빈 하나로 붙인다(DESIGN §6 기본 기여자 표 참고).

## 완료 조건
- [ ] `./gradlew test` 통과: 발동, 우선순위, 최대 개수, 미언급 제외, 파일이 없을 때
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 시작. T13 기여자 구조 위에 `KEYWORDS` 슬롯 기여자와 preview 발동 키워드 필드를 붙인다.
