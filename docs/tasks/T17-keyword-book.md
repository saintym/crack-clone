# T17 키워드북

- **상태**: DONE
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
- [x] `./gradlew test` 통과: 발동, 우선순위, 최대 개수, 미언급 제외, 파일이 없을 때
- [x] 상태 `REVIEW` + 작업 로그 (PR/머지는 오케스트레이터)

## 작업 로그

### 2026-09-23
- 시작. T13 기여자 구조 위에 `KEYWORDS` 슬롯 기여자와 preview 발동 키워드 필드를 붙인다.
- **한 일**
  - `prompt/keyword/KeywordBook`(@Component): 스토리 `keywords.md`를 `KeywordBookParser`로 읽고 `KeywordMatcher`로 `recentText`에 매칭해 파일 순서(위가 우선)로 최대 `crack.prompt.keyword-max-active`개를 고른다.
  - `prompt/keyword/KeywordBookContributor`(KEYWORDS, order 0, name `keyword_book`): `=== 키워드 설정 ===` 머리 + 항목마다 `### {제목}\n{내용}`, 항목 사이 빈 줄. 발동 항목이 없으면 생략.
  - `AssembledPrompt.activeKeywords`, `PromptPreviewResponse.activeKeywords` 추가(우선순위 순 제목 목록). 조립 INFO 로그에 `keywords=[…]`.
  - `data/_templates/keywords.md` 신규(안내는 첫 `##` 앞의 HTML 주석이라 파서가 무시한다).
  - DESIGN.md §6(AssembledPrompt 필드), §6.3(preview 응답 `activeKeywords`), §8.3(섹션 형식, 구현 위치) 갱신.
- **판단**
  - `keyword-max-active`는 `PromptProperties`(`crack.prompt`)에 한 줄 추가했다. 같은 prefix의 설정 클래스를 따로 두는 것보다 명확하다(기본 3, 0 이하면 끔).
  - 발동 목록을 조립 결과에 싣기 위해 `PromptAssembler` 생성자 끝에 `keywordBook: KeywordBook? = null`을 **선택 인자**로 추가했다. Spring은 빈을 넣고, 수동 생성하는 기존 테스트(`PromptAssemblerSlotOrderTest`, `PromptSizeComparisonTest`)는 그대로 컴파일된다. `AssembledPrompt.activeKeywords`도 기본값 `emptyList()`로 맨 끝에 두어 병렬 작업과의 충돌을 줄였다. 활성 인물과 같은 방식(기여자와 조립기가 각각 선택)이다.
  - 한 턴에 기여자와 조립기가 두 번 고르므로 파싱 결과를 **파일 절대 경로별로 수정 시각+크기** 기준 캐시한다. 스토리마다 경로가 달라 격리가 유지된다. 파일이 없어지면 캐시를 지운다.
  - `keywords.md`가 없거나 읽기 실패(디렉터리 등)면 경고 로그 후 빈 목록. 조립은 계속된다.
  - `StoryDocs.titled`는 contributor 패키지의 내부 도우미라 의존을 만들지 않고 같은 형식(`=== … ===`)을 직접 만들었다.
- **확인**
  - `KeywordBookContributorTest`(12개): 발동, 조사·별칭 키, 미언급 제외, 파일 순서 우선, 최대 개수(2/3/10/0), 기본값 3, 파일 없음·빈 파일·머리말만·디렉터리, 파일 수정 반영(캐시 무효화), 스토리별 격리, 템플릿 파싱.
  - `KeywordBookPreviewTest`(3개, SpringBootTest+MockMvc): preview의 `activeKeywords`와 `keyword_book` 섹션 위치(PROTAGONIST 뒤), 최근 대화 언급으로 발동, 파일이 없을 때 조립.
  - `./gradlew test` 전체 통과. 로컬 `application.yml`을 스크래치로 치운 상태에서도 통과 후 원복. 첫 기능 커밋 단독 `compileTestKotlin` 확인.
- **주의점**
  - 픽스처 `sample-scenario/keywords.md`에 흑풍채(키: 흑풍채, 산적)·청운객잔(키: 청운객잔, 객잔)이 있다. 기존 테스트의 `sections` `containsExactly` 목록은 이 단어를 입력에 쓰지 않아 영향이 없지만, 새 테스트 입력에 "객잔", "산적"을 쓰면 `keyword_book` 섹션이 추가된다.
  - 캐시는 수정 시각과 크기가 모두 같은 덮어쓰기(같은 초·같은 바이트 수)를 놓칠 수 있다. 문서 편집 API 경유라면 실사용에서 문제 될 가능성은 낮다.
  - 템플릿은 시나리오 생성 시 자동 복사되지 않는다(`ScenarioService`는 world/scenario/protagonist/must_remember만 복사). 필요하면 T12 등에서 추가.
