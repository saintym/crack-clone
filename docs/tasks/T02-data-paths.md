# T02 데이터 경로 계산 방식 변경 + BUG-005 근본 수정

- **상태**: IN_PROGRESS
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
- [ ] `./gradlew test` 통과. 경로 계산 단위 테스트(상대·절대 data-path, `_legacy`)
- [ ] V4 SQL을 PostgreSQL 문법으로 작성. 어떻게 확인했는지 작업 로그에 남긴다(가능하면 로컬 PG, 아니면 SQL 검토)
- [ ] BUG-005 문서를 "수정완료"로 갱신
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작 (브랜치 `task/T02-data-paths`, worktree에서 진행)
