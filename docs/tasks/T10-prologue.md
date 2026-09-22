# T10 첫 메시지(프롤로그)

- **상태**: REVIEW
- **웨이브**: 2
- **의존**: T03, T08
- **브랜치**: `task/T10-prologue`
- **마이그레이션**: 없음
- **결정**: D16

## 목표
시나리오의 `prologue.md`를 스토리를 시작할 때 **첫 AI 메시지**(턴 0)로 넣는다. 새 스토리가 빈 화면으로 시작하지 않게 하려는 것이다.

## 범위
- `story/service/StoryService.kt`의 `create` (프롤로그 삽입 호출만)
- 신규 `story/prologue/**`
- `crack-frontend/src/pages/ScenarioDetailPage.tsx`, `crack-frontend/src/api/documents.ts` (프롤로그 편집 탭)
- `data/_templates/prologue.md` (신규)
- 관련 테스트

## 구현 내용
1. 스토리를 만들면 T08이 복사한 스토리 폴더의 `prologue.md`를 읽는다. 비어 있지 않으면 `appendAssistant(kind = PROLOGUE, turnNo = 0)`로 저장한다.
2. **치환:** `{{user}}` → 주인공 이름(`characters/protagonist.md`의 `- **이름**:` 값, 없으면 "당신")
3. 프롤로그는 일반 메시지처럼 **수정할 수 있다**(T07 PATCH). 재생성 대상에서는 뺀다(재생성 요청이 프롤로그를 가리키면 400).
4. 시나리오 템플릿에 `prologue.md`를 추가하고, `ScenarioDetailPage`에 "첫 메시지" 편집 탭을 둔다.

## 완료 조건
- [ ] `./gradlew test` 통과: 프롤로그 삽입, 치환, 파일이 없을 때 생략, 턴 수 0 유지
- [ ] `npm run build`, `npm run lint` 통과
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T10-prologue` (main `3bf996f` 기준).

**한 일**
- 신규 `story/prologue/Prologue.kt`(순수 파일 라이브러리): `read(storyDir)`가 스토리 폴더의 `prologue.md`를 읽어 HTML 주석을 빼고 `{{user}}`를 치환한 뒤 앞뒤 공백을 자른다. 없거나 비면 null. `userName(storyDir)`는 `MemoryDocs.readProtagonist(storyDir)?.displayName()`를 재사용하고, 없거나 비었거나 `(…)` 안내 문구뿐이면 "당신".
- 신규 `story/prologue/PrologueService.insertIfPresent(storyId, storyDir)`: 본문이 있으면 `MessageService.appendAssistant(kind = PROLOGUE, turnNo = 0)`로 저장한다(seq 0, 후보 0번 생성, turn_count 0 유지).
- `StoryService.create`: `storyRepository.save` 직후 같은 `withCleanupOnFailure` 블록 안에서 `prologueService.insertIfPresent(saved.id, storyDir)` 호출. 생성자 끝에 `prologueService` 추가. `branch`는 건드리지 않았다.
- `data/_templates/prologue.md` 추가. 안내와 예시를 전부 HTML 주석에 넣어, 그대로 복사해도 첫 메시지가 들어가지 않는다.
- 프론트: `ScenarioDetailPage`에 "첫 메시지" 탭(세계관·시나리오 다음). 원본 API `/api/scenarios/{name}/documents/prologue`(T08에서 추가됨)로 읽고 저장한다. 파일이 없으면 GET 404 → 빈 내용으로 보이고, 저장하면 파일이 생긴다. `documents.ts`에 `ScenarioDocumentType` 유니언 타입을 두고 get/update 인자에 적용.

**판단**
- **치환 규칙:** `{{user}}`는 대소문자와 중괄호 안 공백을 가리지 않는다(`{{ User }}`도 치환). 그 밖의 자리표시자(`{{char}}` 등)는 두지 않았다.
- **HTML 주석 제거:** 템플릿 안내 문구가 첫 메시지로 새지 않게 하려고 넣었다. 주석을 빼고 비면 생략한다(작업 파일의 "비어 있지 않으면"을 이렇게 해석).
- **읽기 실패는 건너뛴다:** `prologue.md` 읽기 `IOException`은 경고 로그 후 첫 메시지 없이 생성한다(첫 메시지 때문에 스토리 생성이 막히지 않게). DB 저장 실패는 그대로 던져서 트랜잭션 롤백 + 스토리 폴더 정리.
- **감정 태그는 파싱하지 않는다:** 프롤로그는 사람이 쓴 원문이라 `emotion = null`로 저장한다.
- **원본이 아니라 스토리 폴더에서 읽는다:** T08이 복사한 스토리 폴더의 `prologue.md`를 읽는다(D12 격리). 그래서 원본을 고쳐도 이미 만든 스토리에는 반영되지 않는다(탭 안내 문구에 적음).

**확인**
- `./gradlew test` 전부 통과(292개). 신규 `PrologueTest`(6, 치환·주석·공백·이름 폴백) · `PrologueInsertTest`(7, `@SpringBootTest`: 턴0/seq0/PROLOGUE/후보 1개, 치환, "당신" 폴백, 파일 없으면 생략, 비었거나 주석뿐이면 생략, 다음 유저 메시지는 턴 1, 수정 가능하고 turn_count 0 유지). `StoryServiceTest`에 스토리 폴더 경로로 호출하는지, 삽입 실패 시 폴더 정리 테스트 추가.
- `npm run build`, `npm run lint` 오류 0.

**범위 밖 수정**
- `StoryServiceTest.kt`: `StoryService` 생성자에 인자가 늘어 테스트 생성 코드를 고쳤다(관련 테스트).

**다음 작업자 주의점**
- **T07/T11 — 프롤로그 재생성 400:** `MessageService.requireLatestAssistant`는 "가장 최근 ASSISTANT"만 본다. **메시지가 프롤로그 하나뿐이면 프롤로그가 가장 최근 ASSISTANT라서 `addVariant`/`selectVariant`가 막히지 않는다.** chat·message 패키지는 병렬 작업 충돌을 피하려고 건드리지 않았다. T07이 재생성 진입점에서 `message.kind == MessageKind.PROLOGUE`면 `BadRequestException`(400)을 던지거나, `MessageService.addVariant`에 같은 검사를 넣어야 한다. 프롤로그뿐인 스토리에서 "마지막 메시지 재생성" 요청이 오면 대상이 없으므로 400으로 거부하는 것을 권장한다(유저 메시지가 없어 새 응답을 만들 근거가 없음). T11은 프롤로그(`kind === 'PROLOGUE'`)에 재생성 버튼을 숨기고 수정 버튼은 보여 준다.
- **T09:** `StoryService` 생성자 끝에 `prologueService`가 추가됐다. T09가 생성자에 인자를 넣으면 충돌이 날 수 있으니 머지 때 둘 다 남긴다. 분기(branch)는 원본 스토리의 메시지를 복사하므로 프롤로그를 따로 넣지 않는 것이 맞다(`PrologueService`를 부르지 말 것. 부르면 seq 0이 아니어서 400).
- **T13:** 프롤로그는 turn 0 ASSISTANT 메시지라 첫 기록 전까지 대화 이력에 포함된다(DESIGN §6).
- 프롤로그 삭제: `truncateFrom(프롤로그 id)`는 전체 대화를 지운다. 이후 프롤로그를 다시 넣는 API는 없다.
