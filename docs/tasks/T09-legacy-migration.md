# T09 기존 데이터 이전 + 분기 이식

- **상태**: IN_PROGRESS
- **웨이브**: 2
- **의존**: T03, T08
- **브랜치**: `task/T09-legacy-migration`
- **마이그레이션**: 없음 (코드 기반 1회성 이전)

## 목표
사용자 로컬에 있는 기존 스토리를 v2 구조로 옮긴다. 사용자가 로컬에서 직접 실행할 수 있게, 재실행해도 안전한(멱등) 방식으로 만든다. 분기 기능도 새 저장소 위로 옮긴다(확장은 하지 않음, D4).

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/migration/**`
- `story/service/StoryService.kt`의 `branch`, `story/controller/StoryController.kt`의 branch 요청 형식(`messageIndex` → `messageId`)
- 관련 테스트(픽스처는 테스트 안에서 생성)

## 구현 내용
1. `LegacyStoryMigrator`: `story.json`이 없는 스토리 폴더를 대상으로 한다.
   - ① 원본 문서 중 스토리에 없는 것을 복사한다. 이미 있는 스토리별 `characters/*.md`는 **유지**한다.
   - ② `memory/must_remember.md` → `user_note.md`
   - ③ `chat/archive/turn_*.md`를 번호 순으로, 그다음 `chat/chat_latest.md`를 DB 메시지로 가져온다.
     - 형식은 `## USER` / `## ASSISTANT`이다. 파서는 **이 패키지 안에 자체 구현**한다. `ChatFileService`는 T12에서 삭제되기 때문이다.
     - 턴 번호는 이어서 매긴다.
   - ④ `memory/summary_*.md`를 합쳐 `chronicle.md`의 `## 장 요약` 초안으로 만든다.
   - ⑤ `story.json`을 쓴다.
   - `_legacy` 스토리(T02)는 시나리오 폴더에 있던 chat과 memory를 새 `stories/{dir}`로 옮기고 `dir_name`을 갱신한다.
2. **실행 방식:** `crack.migration.legacy.enabled=true`일 때 기동 시 실행하는 `ApplicationRunner`, 또는 `POST /api/admin/migrate-legacy`. 결과 보고서(스토리별 가져온 메시지 수, 경고)를 로그와 응답으로 남긴다.
3. **원본 보존:** 옮긴 옛 파일은 지우지 않고 `legacy/` 하위로 옮긴다.
4. **분기:** 스토리 폴더를 통째로 복사한다(memory/history 제외). 메시지는 기준 `messageId`의 seq까지 복사한다. `recorded_through_turn`은 T14 이전이면 0이다. T14가 머지된 뒤라면 `min(원본값, 분기 턴)`으로 두고 작업 로그에 적는다.

## 완료 조건
- [ ] `./gradlew test` 통과
- [ ] 임시 디렉터리에 옛 구조 픽스처를 만들어 이전 → 메시지 수, 턴 번호, user_note, 멱등성(두 번 실행) 검증
- [ ] 분기 테스트: 메시지 절단 지점, 문서 복사, 원본 불변
- [ ] 사용자용 실행 안내를 이 파일의 작업 로그에 작성
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T09-legacy-migration`(worktree).
