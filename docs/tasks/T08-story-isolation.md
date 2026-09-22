# T08 스토리 격리 + 스토리 문서 API

- **상태**: IN_PROGRESS
- **웨이브**: 2
- **의존**: T02
- **브랜치**: `task/T08-story-isolation`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §2, §9 / 결정 D12 (critical)

## 목표
스토리를 만들 때 시나리오 원본을 **통째로 복사**한다. 플레이 중에는 스토리 폴더만 읽는다. 같은 시나리오의 스토리끼리 절대 서로 영향을 주지 않게 하는 것이 목표다.

## 범위
- `story/service/StoryService.kt`(create, delete), 신규 `story/files/**`
- `prompt/service/PromptAssembler.kt`의 `assembleSystemPrompt` (시그니처는 유지하고 본문만 스토리 폴더를 읽도록 변경)
- 신규 `document/story/**`(스토리 문서 API), `document/service/DocumentService.kt`(시나리오 문서 타입 추가: prologue, keywords, commands, images)
- `src/test/resources/fixtures/sample-scenario/**` (신규 테스트 픽스처. 실제 사용자 데이터는 git에 없음)
- 관련 테스트

## 구현 내용
1. `StoryFiles.initFromScenario(scenarioDir, storyDir)`
   - DESIGN.md §2의 목록을 복사한다. `images.md`와 `stories/`는 제외한다.
   - 없는 선택 파일은 건너뛴다.
   - `user_note.md`, `chronicle.md`, `directives.json`(`[]`), `state.json`(빈 상태)을 만든다.
   - 마지막에 `story.json`을 쓴다.
2. `PromptAssembler.assembleSystemPrompt`: `scenarioPath` 인자는 무시하고 **storyPath만** 읽는다. 스토리 폴더에 파일이 없어도 시나리오로 폴백하지 않는다(격리). `must_remember.md` 대신 `user_note.md`를 읽는다.
3. **스토리 문서 API**(DESIGN.md §9): 경로 화이트리스트(`world.md`, `scenario.md`, `prologue.md`, `characters/*.md`, `chronicle.md`, `user_note.md`, `keywords.md`, `commands.md`)를 적용하고 경로 조작을 막는다.
4. 스토리를 삭제하면 스토리 폴더만 지운다(원본 불변 확인).
5. 픽스처 `sample-scenario`: world, scenario, prologue, 인물 2명(별칭 포함), protagonist, keywords, commands, images. 리모트 에이전트의 수동 확인에도 쓴다.

## 완료 조건
- [ ] `./gradlew test` 통과
- [ ] **격리 테스트**
  - 스토리 A에서 인물 문서를 수정해도 원본과 스토리 B는 그대로다
  - 원본을 수정해도 기존 스토리는 그대로이고, 새 스토리에는 반영된다
- [ ] 경로 조작(`../`, 절대 경로) 거부 테스트
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T08-story-isolation`(worktree).
