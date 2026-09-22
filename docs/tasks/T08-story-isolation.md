# T08 스토리 격리 + 스토리 문서 API

- **상태**: REVIEW
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
- [x] `./gradlew test` 통과 (278개, 기준선 221 + 신규 57)
- [x] **격리 테스트**
  - 스토리 A에서 인물 문서를 수정해도 원본과 스토리 B는 그대로다
  - 원본을 수정해도 기존 스토리는 그대로이고, 새 스토리에는 반영된다
- [x] 경로 조작(`../`, 절대 경로) 거부 테스트
- [x] 상태 `REVIEW` + 작업 로그 (PR은 오케스트레이터가 머지)

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T08-story-isolation`(worktree).

**한 일**
- `story/files/StoryFiles.kt`(신규): `initFromScenario(scenarioDir, storyDir)`가 `world.md`, `scenario.md`, `prologue.md`, `keywords.md`, `commands.md`, `characters/{이름}.md`(주인공 포함)를 **내용 복사**하고, 없는 파일은 건너뛴다. `images.md`, `stories/`, 옛 `chat/`·`memory/`는 복사하지 않는다. 이어서 `user_note.md`(`# 유저노트`), `chronicle.md`(`# 연대기`), `directives.json`(`[]`), `state.json`(`StoryState.EMPTY`)을 만들고 마지막에 `story.json`을 쓴다. `deleteStoryDir(storiesRoot, storyDir)`는 `stories/` 바로 아래 폴더만 지운다.
- `story/files/StoryMeta.kt`(신규): `story.json` = `{"scenarioName","copiedAt"(ISO-8601 오프셋, 초 단위),"formatVersion":2}`.
- `story/files/StoryDirs.kt`(신규): 스토리 ID → `(story, scenario, dir, isLegacy)`.
- `StoryService`: `create`/`branch`가 옛 `initStoryDirectory`(chat/, memory/must_remember.md) 대신 `initFromScenario`를 쓴다. 폴더 생성이나 DB 저장이 실패하면 만들다 만 폴더를 지운다. `delete`는 `StoryFiles.deleteStoryDir`로 스토리 폴더만 지운다(`_legacy`는 기존대로 파일 보존).
- `PromptAssembler.assembleSystemPrompt`: 시그니처 유지, 본문만 교체. `storyPath`만 읽고 원본 폴백이 없다. 유저노트는 `user_note.md`(`=== 유저노트 ===` 섹션).
- 스토리 문서 API(`document/story/**` 신규): `GET /api/stories/{id}/documents`, `GET·PUT /api/stories/{id}/documents/content?path=`.
- `DocumentService.DocumentType`에 `PROLOGUE`, `KEYWORDS`, `COMMANDS`, `IMAGES` 추가(`/api/scenarios/{name}/documents/{type}`로 바로 쓸 수 있다).
- 픽스처 `src/test/resources/fixtures/sample-scenario/`: world, scenario, prologue, 설월·무극(별칭 포함), protagonist, keywords, commands, images. 테스트에서는 `SampleScenario.copyTo(dir)`로 복사해서 쓴다(픽스처 원본은 건드리지 않음).
- 테스트: `StoryFilesTest`(8), `StoryDocumentPathsTest`(33, 파라미터화 포함), `StoryIsolationTest`(`@SpringBootTest`+MockMvc, 8), `StoryServiceTest`(+2, 생성 테스트 교체), `PromptAssemblerTest`(+5, 유저노트 테스트 교체), `DocumentServiceTest`(+1).

**설계 판단**
- **복사는 링크 없이 내용만.** `Files.copy`는 심볼릭 링크를 따라가 내용을 복사하므로 원본이 링크여도 스토리는 독립 파일을 갖는다. `.`으로 시작하는 파일과 `characters/` 하위 폴더는 복사하지 않는다.
- **`story.json`은 마지막에 쓴다.** 이 파일이 있으면 끝까지 만들어진 v2 스토리다. T09는 `story.json`이 없는 폴더를 이전 대상으로 보면 된다.
- **스토리 폴더가 이미 있으면 `Files.createDirectory`의 `FileAlreadyExistsException`으로 거부**하고, 이 경우에는 정리(삭제)도 하지 않는다. 남의 폴더를 지우지 않기 위해서다. 그 밖의 실패는 만든 폴더를 지운다.
- 시나리오 폴더가 없으면(DB에만 있는 시나리오) 경고 로그를 남기고 원본 문서 없는 스토리를 만든다. 기존 동작(폴더가 없어도 생성 성공)을 깨지 않기 위해서다.
- **유저노트 폴백:** `user_note.md`가 **없을 때만** 같은 스토리 폴더의 `memory/must_remember.md`를 읽는다. `_legacy`(폴더 = 시나리오 폴더)와 T08 이전 스토리가 T09 이전 전까지 유저노트를 잃지 않게 하려는 것이다. 스토리 폴더 안이므로 격리는 유지된다.
- `activeCharacters`로 들어온 이름에 `/`, `\`, `..` 등이 있으면 버린다(스토리 `characters/` 밖을 읽지 못하게). 인물 목록은 이름순이다(옛 코드는 `Files.list` 순서였다).
- **문서 API 세부(DESIGN §9에 없던 부분을 정함):**
  - 목록 항목 `{path, kind, size}`. `kind`는 §9에 적힌 이름 그대로(`world`, `scenario`, `prologue`, `protagonist`, `characters`, `chronicle`, `user_note`, `keywords`, `commands`). 인물 파일의 `kind`는 `characters`다. `size`는 **바이트 수**. 순서는 위 종류 순, 인물은 경로 이름순. 실제로 있는 파일만 나온다.
  - `GET·PUT content` 응답은 `{path, kind, content}`. 없는 문서 GET은 404. PUT은 없던 문서도 만든다(새 인물 추가 가능). 쓰기는 `AtomicFiles`.
  - 경로 검사: 역슬래시, `:`, NUL, `/`·`~` 시작, 빈 조각·`.`·`..`·`.`으로 시작하는 조각을 거부한 뒤 화이트리스트 정확 일치 또는 `characters/{이름}.md`(한 단계)만 허용. 해석한 경로가 스토리 폴더 밖이거나, 심볼릭 링크(끊어진 링크 포함)를 풀어낸 실제 경로가 밖이면 거부. 모두 400(`BadRequestException`).
  - **`_legacy` 스토리는 PUT을 400으로 막는다.** 폴더가 시나리오 원본이라 쓰면 원본이 바뀌기 때문이다. 읽기는 허용한다.
- `StoryDirs`를 `story/files`에 둔 이유: T13 기여자, T14 파이프라인, T16 지시 등 스토리 폴더가 필요한 곳에서 같은 방식으로 찾게 하려는 것이다.

**확인 방법**
- `cd crack-backend && ./gradlew test`: 278개 전부 통과(기준선 221 + 신규 57).
- `StoryIsolationTest`가 완료 조건의 격리 시나리오를 API 수준으로 검증한다: 스토리 A 인물 문서 PUT → B와 원본 불변, A/B 프롬프트 차이 확인 / 원본 편집 API로 world 수정 → 기존 스토리 불변, 새 스토리 반영 / `../`, 다른 스토리 폴더, 절대 경로, `images.md`, `story.json` → 400이고 어떤 파일도 바뀌지 않음 / DELETE 스토리 → 해당 폴더만 삭제, 원본 전 파일이 픽스처와 동일.

**겪은 문제**
- KDoc에 `characters/*.md`를 쓰면 Kotlin이 `/*`를 **중첩 주석 시작**으로 읽어 `Unclosed comment` 컴파일 오류가 난다. 주석에서는 `characters/{이름}.md`로 쓴다.
- 로컬 worktree에는 gitignore된 `src/main/resources/application.yml`이 있어 `crack.auth.password`가 잡히고, MockMvc 요청이 401이 됐다(리모트에는 이 파일이 없어 재현되지 않음). `StoryIsolationTest`는 `@SpringBootTest(properties = ["crack.auth.password="])`로 비밀번호를 비워 두 환경에서 같게 돈다(컨텍스트가 하나 더 뜬다).
- `PromptAssemblerTest`에서 `주인공(사용자)`가 없는지 검사했다가 BASE_RULE 문구("주인공(사용자)의 행동…")에 걸렸다. 섹션 제목 `=== 주인공(사용자) ===`로 검사한다.

**범위 밖 수정**
- 없음. (`StoryDirs`는 범위 안의 `story/files/**`에 뒀다.)

**다음 작업자 주의점**
- **T07:** `assembleSystemPrompt(scenarioPath, storyPath, activeCharacters)` 시그니처는 그대로다. `scenarioPath`는 무시된다. 새 스토리 폴더에는 `chat/`, `memory/`가 없다(옛 `ChatFileService`는 첫 쓰기 때 알아서 만든다).
- **T09:** `story.json` 없는 폴더 = 이전 대상. 이전 후 `StoryFiles.initFromScenario`와 같은 결과(특히 `user_note.md`, `chronicle.md`, `directives.json`, `state.json`, 마지막 `story.json`)를 맞춰야 한다. `StoryMeta.create(scenarioName).write(...)`를 재사용할 수 있다. `branch`는 지금 원본에서 새로 복사한 뒤 옛 chat 파일을 쓴다(임시). T09가 스토리 폴더 복사로 바꾼다. `user_note.md`가 생기면 `memory/must_remember.md` 폴백은 자동으로 꺼진다. `_legacy` 이전이 끝나면 문서 API의 `_legacy` 쓰기 차단 분기는 쓰이지 않는다.
- **T10:** 첫 메시지 원본은 스토리 폴더의 `prologue.md`(복사됨)를 읽는다. 원본은 `/api/scenarios/{name}/documents/prologue`로 편집한다.
- **T13:** 스토리 폴더 찾기는 `StoryDirs.locate(storyId).dir`. 유저노트 파일은 `user_note.md`(`StoryFiles.USER_NOTE_FILE`), 지시는 `directives.json`. 기여자도 스토리 폴더만 읽어야 한다(원본 폴백 금지). `images.md`만 예외로 시나리오 원본을 참조한다(§8.5).
- **T12/T14/T15:** 옛 `MemoryService`는 여전히 `memory/must_remember.md`를 읽고 쓰고, 인물 갱신 때 **시나리오 원본 `characters/`를 기준으로 읽는다**(쓰기는 스토리). 새 스토리에서는 옛 필수 기억 API로 쓴 내용이 프롬프트에 들어가지 않는다(`user_note.md`가 있으므로). 유저노트 편집은 스토리 문서 API(`path=user_note.md`)를 쓰면 된다.
- 기존 `DocumentService.characterPath(charName)`는 이름을 검사하지 않는다(경로 변수라 `/`는 Spring/Tomcat이 막지만 `..` 단독 등은 검사 없음). 범위 밖이라 두었다. 필요하면 `DataPaths.requireSegment` 같은 검사를 붙인다.

