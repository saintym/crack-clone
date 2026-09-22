# T02 데이터 경로 계산 방식 변경 + BUG-005 근본 수정

- **상태**: REVIEW
- **웨이브**: 1
- **의존**: 없음
- **브랜치**: `task/T02-data-paths`
- **마이그레이션**: **V4** (이 번호만 사용)
- **설계**: DESIGN.md §1, §3

## 목표
DB에 절대 경로를 저장하지 않는다. 경로는 항상 `crack.data-path` + 시나리오 `name` + 스토리 `dir_name`으로 계산한다. `data-path` 설정을 바꿔도 데이터가 깨지지 않게 하는 것이 목표다([BUG-005](../../bugs/BUG-005_시나리오_데이터경로_불일치.md)).

## 범위
- `scenario/**`, `story/entity/Story.kt`, `story/service/StoryService.kt`(경로 계산 부분만), `story/dto/**`
- `global/config/DataPathConfig.kt` (+ 신규 `global/config/DataPaths.kt`)
- 경로를 쓰는 곳의 경로 계산 부분만: `document/service/DocumentService.kt`, `chat/service/ChatService.kt`, `memory/service/*.kt`
- `src/main/resources/db/migration/V4__relative_paths.sql`
- 관련 테스트, `bugs/BUG-005_*.md`, `bugs/README.md`(BUG-005 상태만 갱신)

## 구현 내용
1. `DataPaths` 컴포넌트: `scenarioDir(name)`, `storyDir(scenarioName, dirName)`, `templatesDir()`. 루트는 `DataPathConfig`에서 `toAbsolutePath().normalize()`로 한 번만 계산한다.
2. `Scenario.dataPath`와 `Story.dataPath`를 제거하고 `Story.dirName`을 추가한다. 서비스들이 `DataPaths`로 경로를 얻게 바꾼다.
3. **V4 마이그레이션**
   - `ALTER TABLE stories ADD COLUMN dir_name VARCHAR(64)`
   - 기존 `data_path`의 마지막 경로 조각으로 채운다. `stories/` 아래가 아닌 옛 기본 스토리(시나리오 폴더 자체를 가리키던 것)는 `'_legacy'`로 채운다.
   - `NOT NULL` 설정 후 두 테이블의 `data_path`를 삭제한다.
4. `'_legacy'` 스토리는 경로를 시나리오 폴더 자체로 계산한다. 폴더 이전은 T09가 처리하므로, 여기서는 그 규칙만 `DataPaths`에 넣는다.
5. `crack-backend/data/` 임시 복사본은 사용자 데이터이므로 **건드리지 않는다.** 문서에 정리 방법만 적는다.

## 완료 조건
- [x] `./gradlew test` 통과. 경로 계산 단위 테스트(상대·절대 data-path, `_legacy`)
- [x] V4 SQL을 PostgreSQL 문법으로 작성. 어떻게 확인했는지 작업 로그에 남긴다(가능하면 로컬 PG, 아니면 SQL 검토)
- [x] BUG-005 문서를 "수정완료"로 갱신
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작 (브랜치 `task/T02-data-paths`, worktree에서 진행)

**한 일**
- `global/config/DataPaths.kt` 추가: `root`(=`Path.of(data-path).toAbsolutePath().normalize()`, 생성 시 한 번), `scenarioDir(name)`, `storyDir(scenarioName, dirName)`, `templatesDir()`, `LEGACY_DIR_NAME = "_legacy"`, `isLegacy()`.
- `Scenario.dataPath`, `Story.dataPath` 제거, `Story.dirName`(`dir_name VARCHAR(64) NOT NULL`) 추가. `ScenarioResponse.dataPath` 제거(프론트에서 쓰지 않음을 grep으로 확인).
- `ScenarioService`, `StoryService`, `DocumentService`, `ChatService`, `MemoryService`가 `DataPaths`로 경로를 얻게 변경. `ChatService`/`MemoryService`는 경로 계산 줄만 바꾸고 생성자 맨 끝에 `dataPaths`를 추가했다(T01과의 충돌 최소화).
- `V4__relative_paths.sql`: `dir_name` 추가 → `data_path`의 `.../stories/{dir}`에서 `{dir}` 추출(끝 구분자, `\` 허용), 나머지는 `'_legacy'` → `NOT NULL` → 두 테이블의 `data_path` 삭제.
- 테스트: `DataPathsTest`(6개) 신규, `StoryServiceTest`에 `_legacy` 삭제 보존·분기 폴더 위치 테스트 2개 추가, 기존 테스트를 새 생성자/엔티티에 맞게 수정.
- BUG-005 문서를 수정완료로 갱신하고 `crack-backend/data/` 정리 방법을 적었다(데이터 자체는 건드리지 않음).

**설계 판단**
- `DataPaths`는 문자열(이름)만 받는다. 엔티티를 받는 오버로드를 두면 `global`이 `scenario`/`story`에 의존하게 되어 뺐다. 호출부는 `dataPaths.storyDir(scenario.name, story.dirName)` 한 줄이다.
- 이름은 경로 조각 하나만 허용한다(`/`, `\`, `.`, `..`, 공백만 있는 값 → `BadRequestException`). URL로 받은 시나리오 이름이 데이터 루트 밖을 가리키는 것을 막는다. 기존 실제 이름(`테스트세계`, `마도생존기`, 밀리초 dir_name)은 모두 통과한다.
- `_legacy` 스토리 삭제 시 폴더 = 시나리오 폴더이므로 파일은 지우지 않고 DB 레코드만 지운다(원본 유실 방지). 이전은 T09 몫.
- 새 `dir_name`은 기존대로 밀리초 문자열이되, 같은 밀리초 폴더가 이미 있으면 1씩 올린다.
- V4에서 `stories/` 아래가 아닌 경로는 모두 `_legacy`로 본다. 시나리오 폴더와 문자열 비교를 하지 않은 이유: BUG-005처럼 시나리오와 스토리의 경로 기준이 서로 다를 수 있어서, 마지막 조각 구조만 보는 편이 안전하다.

**확인 방법**
- `cd crack-backend && ./gradlew test`: 117개 전부 통과(기준선 109 + 신규 8).
- 로컬 PostgreSQL 14(Homebrew)로 스크래치 폴더에 **임시 클러스터**(127.0.0.1:55432, 별도 initdb)를 띄워 검증. 사용자의 `crack` DB/5432 서버는 건드리지 않았다.
  1. 임시 DB에 V1~V3 적용 + 샘플 행(상대 `./data/테스트세계`, 끝 `/`가 붙은 절대 경로, `stories/` 절대·상대·끝 `/`·Windows `\` 경로, 이름이 `stories`인 시나리오 폴더, 중첩 경로) → V4 적용 → `dir_name`이 기대값(`_legacy` 또는 밀리초)인지, `data_path`가 두 테이블에서 사라졌는지 `\d`로 확인.
  2. 빈 임시 DB에 bootJar를 `--spring.datasource.url=...55432/...`로 기동: Flyway가 V1~V4를 적용하고 `ddl-auto: validate`로 엔티티-스키마 일치 확인. API로 시나리오/스토리 생성 → `{data-path}/스모크/stories/{millis}` 생성과 DB `dir_name` 확인, `name: "../evil"` → 400 확인.
  3. 끝나고 임시 DB 2개 DROP, 클러스터 중지, 폴더 삭제.

**다음 작업자 주의점**
- 경로는 반드시 `DataPaths`로 계산한다. `Path.of(dataPathConfig.dataPath, ...)`를 새로 쓰지 않는다.
- T09: `_legacy` 스토리를 `stories/{새 dir_name}`으로 옮기고 DB `dir_name`을 바꾸면 된다. 그 전까지 `_legacy` 스토리는 시나리오 원본 폴더를 직접 읽고 쓴다(스토리 격리 미적용 상태).
- `ChatFileService.parseMessages`는 기존 버그가 있다: `split(Regex)`가 캡처 그룹(`USER|ASSISTANT`)을 결과에 넣지 않아 role/content가 한 칸씩 밀린다. 그래서 `branch`의 turnCount가 틀릴 수 있다. 범위 밖이라 고치지 않았고(T03/T07에서 파일 채팅 저장소가 DB로 바뀜), 분기 테스트도 이 파서에 의존하지 않게 썼다.
- `crack-backend/data/테스트세계`는 `data/테스트세계`와 `chat/chat_latest.md`가 다르다(2026-09-23 read-only 비교). 삭제 전 사용자가 확인해야 한다. 정리 절차는 BUG-005 문서에 있다.
- `ScenarioResponse`에서 `dataPath`가 빠졌다. 프론트는 이 필드를 쓰지 않는다.
