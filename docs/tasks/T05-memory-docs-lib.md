# T05 기억 문서 포맷 라이브러리

- **상태**: DONE
- **웨이브**: 1
- **의존**: 없음
- **브랜치**: `task/T05-memory-docs-lib`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §7.1

## 목표
인물 `## 기억`, 주인공 `## 변화 기록`, 연대기, `state.json`을 읽고 쓰는 **순수 라이브러리**를 만든다. LLM 호출은 없다. T13, T14, T19가 이 라이브러리를 쓴다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/memory/docs/**`
- 신규 `src/test/kotlin/com/crack/memory/docs/**`
- `data/_templates/character.md`, `data/_templates/protagonist.md` (`별칭` 줄과 빈 기억 섹션 안내 추가)
- 신규 `data/_templates/chronicle.md`
- **수정 금지:** 기존 `memory/service/**`(T12 정리 대상)

## 구현 내용
1. `MarkdownSections`: `##` 단위 섹션 읽기, 교체, 없으면 끝에 추가. 섹션 밖 원본 텍스트는 **바이트 단위로 보존**한다.
2. `CharacterDoc`: 이름(파일명 기준), `parseAliases()`(`- **별칭**: a, b` 줄), `memorySection`(`## 기억`)과 그 하위 `###` 파싱(관계/사건/소지품·기술·신체 → 구조체)
3. `ProtagonistDoc`: `## 변화 기록`과 하위 `### 관계`, `### 스탯·기술`, `### 소지품`, `### 신체`
4. `Chronicle`: `## 장 요약`과 `## 회차 N (턴 a–b)` 목록을 파싱한다. 제공 기능은 `append(entry)`, `rawLength()`, `oldestEntries(n)`, `replaceOldestWithSummary(n, summary)`.
5. `StoryState`: `state.json` 읽기·쓰기(Jackson). 파일이 없으면 빈 기본값을 쓴다.
6. `MemoryBudgets`: `crack.memory.budget.*` 설정 바인딩(DESIGN.md 기본값)과 `exceeds()`
7. 파일 쓰기는 임시 파일에 쓴 뒤 원자적으로 이동(`ATOMIC_MOVE`)하는 헬퍼 `AtomicFiles`로 한다.

## 완료 조건
- [x] `./gradlew test` 통과 (141개, 신규 32개)
- [x] 테스트 항목
  - 섹션 교체 시 원본 보존(앞뒤 공백·줄바꿈 포함)
  - 섹션이 없을 때 추가
  - 별칭 파싱
  - 연대기 append와 압축 치환
  - state.json 왕복
- [x] 상태 `REVIEW` + 작업 로그 (PR은 오케스트레이터가 머지)

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T05-memory-docs-lib`.

**한 일**
- `com.crack.memory.docs` 패키지 신규: `MarkdownSections`, `AtomicFiles`, `Chronicle`(+`ChronicleEntry`, `ChronicleParts`), `StoryState`, `CharacterDoc`(+`CharacterMemory`), `ProtagonistDoc`(+`ProtagonistChanges`), `MemoryEntries`(`MemoryItem`, `RelationEntry`, `EventEntry`), `MemoryDocs`(진입점·경로 규칙), `MemoryBudgets`(+`MemoryDocsConfig`).
- 템플릿: `character.md`/`protagonist.md`에 `- **별칭**:` 줄 추가, 옛 `## 주요 사건 기록`을 `## 기억`/`## 변화 기록` 안내로 교체. `chronicle.md` 신규(형식 예시는 코드 펜스 안에 둠).
- 테스트 7개 클래스 32개: 섹션 교체 원문 보존(앞뒤 공백·빈 줄·CRLF·파일 끝 줄바꿈 없음), 섹션 없을 때 추가, 코드 펜스 무시, 별칭 파싱, 기억 하위 섹션 구조체 파싱, 연대기 append/압축 치환/rawLength, state.json 왕복·기본값, 예산 바인딩(ApplicationContextRunner)과 `exceeds`, 템플릿 안내 문구가 데이터로 읽히지 않음.

**설계 판단**
- 섹션 = 제목 줄부터 같은 수준 이상 다음 제목 직전까지. `##` 섹션 본문에 `###` 하위 섹션이 포함된다. 교체는 그 구간만 문자열로 잘라 붙이므로 섹션 밖은 바이트 단위로 보존된다. 기존 제목 줄도 원문 그대로 둔다. 원래 섹션 끝의 빈 줄(다음 제목과의 간격)과 문서의 줄바꿈 종류(LF/CRLF)를 유지한다.
- 코드 펜스(``` / ~~~) 안의 `#` 줄은 제목으로 보지 않는다. LLM이 쓴 예시나 템플릿 예시가 섹션으로 잘못 잡히지 않게 하기 위해서다.
- 문서 클래스는 원문 텍스트를 들고 있는 불변 객체이고, 변경 메서드는 새 인스턴스를 돌려준다. T14 파이프라인이 "결과를 모두 메모리에 모은 뒤 한 번에 쓰기"(§7.2 ⑤)를 하기 쉽게 하려는 것이다. 파일 쓰기는 모두 `AtomicFiles`(같은 폴더 임시 파일 → `ATOMIC_MOVE`, 지원 안 되면 `REPLACE_EXISTING`)를 거친다.
- 연대기 API는 작업 파일 이름(`append`, `rawLength`, `oldestEntries`, `replaceOldestWithSummary`)과 DESIGN.md §7.1 이름(`split`, `compactOldest`)을 둘 다 제공한다. `compactOldest`는 `replaceOldestWithSummary`의 별칭이다.
- `replaceOldestWithSummary(n, summary)`의 `summary`는 **새 장 요약 전체**다(기존 요약에 덧붙이지 않는다). 비어 있으면 `## 장 요약`을 없앤다. 없던 요약은 첫 `##` 섹션 앞에 만든다.
- `rawLength()`는 회차 섹션(제목 줄 포함)의 앞뒤 공백을 뺀 길이 합이다. `## 장 요약`은 세지 않는다. 인물/주인공 예산은 섹션 본문을 trim한 길이로 잰다. `exceeds`는 "초과"(`>`)일 때만 참이다.
- 예산 설정 키는 DESIGN.md에 이름이 없어서 `crack.memory.budget.character`(3000), `.protagonist`(4000), `.chronicle`(12000)로 정했다. `AppConfig`(범위 밖)를 고치지 않으려고 패키지 안에 `MemoryDocsConfig`(`@EnableConfigurationProperties`)를 따로 뒀다.
- `state.json`은 전용 ObjectMapper(모르는 필드 무시, `null`은 기본값, `companions`의 `null` 원소 제거, 들여쓰기 출력)를 쓴다. 필드: `companions: List<String>`, `location/time: String?`, `updatedAtTurn: Int?`.
- 별칭 값이 통째로 괄호로 감싸져 있으면(템플릿 안내 문구) 빈 목록으로 본다. 구분자는 `,` `，` `、`, 중복 제거.
- 사건 항목은 `- t18–21: 내용`(대시는 `–`, `-`, `—`, `~` 허용)을 `fromTurn/toTurn`으로, 관계 항목은 첫 콜론 앞을 대상으로 읽는다. 모든 항목의 `(tNN)` 표기를 `turns`로 모은다. 하위 제목은 `소지품·기술·신체`와 `소지품 · 기술 · 신체`, `소지품/기술/신체`를 같게 본다.

**확인 방법**
- `cd crack-backend && ./gradlew test` → 141개 전부 통과(기준선 109 + 신규 32).

**다음 작업자 주의점**
- T14: LLM이 준 `<memory>`/`<changes>`는 제목 줄 없는 본문으로 `withMemorySection`/`withChangesSection`에 넣으면 된다. 본문 앞뒤 빈 줄은 정리된다. LLM이 `## 기억` 제목까지 포함해 돌려주면 제목이 중복되므로 파서에서 떼어야 한다.
- T14: 압축 단계는 `oldestEntries(n)` + 기존 `summary`를 LLM에 주고, 결과를 `replaceOldestWithSummary(n, 새요약전체)`로 넣는다.
- T13: `CharacterDoc.parseAliases()`, `MemoryDocs.readState(storyDir).companions`를 쓰면 된다. 인물 이름은 파일명 기준이다.
- 옛 `MemoryService`(T12 정리 대상, 수정 금지)의 프롬프트는 아직 `주요 사건 기록`을 언급한다. 템플릿에서는 이 섹션을 뺐다.
- `TemplatesTest`는 `../data/_templates/`를 읽는다(gradle 테스트 작업 디렉터리 = `crack-backend`).
