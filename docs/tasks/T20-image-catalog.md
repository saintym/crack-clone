# T20 이미지 카탈로그

- **상태**: DONE
- **웨이브**: 6
- **의존**: T11, T13
- **브랜치**: `task/T20-image-catalog`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §8.5 / 결정 D14 (외부 URL만, 생성하지 않음)

## 목표
시나리오에 등록한 태그 → 외부 이미지 URL 목록을 AI가 장면에 맞게 골라 응답에 넣고, 프론트가 이미지로 보여준다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/image/**`(파서, `ImagesContributor`, `GET /api/stories/{id}/images`)
- `crack-frontend/src/components/chat/ChatBubble.tsx`(태그 → `<img>` 변환), `src/api/images.ts`, `src/pages/ScenarioDetailPage.tsx`(이미지 탭)
- `data/_templates/images.md`(신규)
- 관련 테스트

## 구현 내용
1. **`images.md` 파서:** `- 태그: URL | 설명`. http/https URL만 허용한다.
2. **`ImagesContributor`**(IMAGES 슬롯): 태그와 설명 목록(최대 개수는 설정값) + "장면에 맞을 때만 `{{img:태그}}`를 한 줄에 단독으로" 지시. 첫 줄 감정 태그와 어울리는 이미지를 고르도록 안내한다(감정 태그는 사용자에게 보이지 않음, D19)
3. **ChatBubble**
   - `{{img:태그}}` 줄을 카탈로그 URL의 `<img loading="lazy">`로 바꾼다
   - 모르는 태그는 숨기고, 스트리밍 중 태그가 덜 들어온 상태는 표시하지 않는다
   - 이미지를 불러오지 못하면 숨긴다
4. **ScenarioDetailPage:** 이미지 카탈로그 편집 탭(목록, 미리보기 썸네일)

## 완료 조건
- [x] `./gradlew test`, `npm run build`, `npm run lint` 통과
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 시작. 브랜치 `task/T20-image-catalog`(worktree)에서 진행.

**한 일**
- 백엔드 `com.crack.image`: `ImageCatalogParser`(`- 태그: URL | 설명`), `ImageCatalogService`(시나리오 원본 `images.md` 참조), `GET /api/stories/{id}/images`, `ImagesContributor`(IMAGES, order 0, name `images`), `ImageProperties`(`crack.image.prompt-max-entries`, 기본 50)
- 프론트: `src/api/images.ts`(API, URL 검사, 편집 미리보기용 파서), `components/chat/imageTags.ts`(태그 분리, `ImageCatalogContext`), `hooks/useImageCatalog.ts`, `ChatBubble` 태그 → `<img loading="lazy">`, 시나리오 상세 `이미지` 탭(`components/scenario/ImageCatalogList.tsx`: 썸네일, 무시되는 줄 표시)
- `data/_templates/images.md`, DESIGN §8.5에 형식 세부·API 응답·표시 규칙 확정

**설계 판단**
- **URL은 프롬프트에 넣지 않는다.** AI는 태그만 고르면 되고, 프론트가 카탈로그로 바꾼다(토큰 절약, 주소 노출 없음).
- 설정은 공용 `PromptProperties`가 아니라 `crack.image.*`의 `ImageProperties`에 뒀다. T17과 공용 prompt 파일 충돌을 피하려고. 조립기와 prompt 패키지는 고치지 않았다.
- 기여자는 `ctx.storyDir`가 아니라 `StoryDirs.locate(storyId)` → `DataPaths.scenarioDir`로 원본을 찾는다(DESIGN §2: `images.md`는 원본 참조). `_legacy` 스토리도 같은 경로가 된다. 매 턴 DB 조회가 한 번 늘지만 가볍다.
- HTML 주석 안은 읽지 않게 했다(템플릿 예시, 항목 임시 끄기). 같은 태그는 위가 우선(키워드북과 같은 규칙).
- 프론트: 한 줄 단독 태그만 이미지로, 문장 속 태그는 지운다(원문 태그가 보이면 몰입을 깬다). 카탈로그를 받기 전(null)에는 이미지 줄을 숨긴다. 텍스트는 조각마다 react-markdown으로, 이미지는 JSX `<img src>`로 넣는다(`dangerouslySetInnerHTML` 없음). URL은 서버와 프론트에서 모두 http/https만 통과.
- 유저 말풍선은 태그를 바꾸지 않는다.
- 카탈로그는 채팅 화면에 들어올 때마다 받는다. 채팅 중 다른 탭에서 고친 내용은 채팅 화면에 다시 들어와야 반영된다.

**확인**
- `./gradlew test` 419개 통과(신규: 파서 7, 기여자 4, API/미리보기 3). `npm run build`, `npm run lint` 오류 0.
- `splitImageTags`, `parseImageCatalog`, `isSafeImageUrl`은 esbuild로 묶어 node에서 사례별로 수동 확인(스트리밍 중 `{`, `{{img:설`, `{{img:설월}`는 숨김, 완성되면 이미지 조각, 문장 속 태그는 제거). 프론트 테스트 러너가 없어 테스트 파일은 남기지 않았다.

**범위 밖 수정**
- `src/pages/ChatPage.tsx` 5줄: `useImageCatalog` 호출과 `MessageList`를 `ImageCatalogContext.Provider`로 감쌈. ChatBubble이 스토리 ID를 모르기 때문. 병렬 작업과 충돌을 줄이려고 MessageList 들여쓰기는 바꾸지 않았다.
- 신규 파일 `src/hooks/useImageCatalog.ts`, `src/components/chat/imageTags.ts`, `src/components/scenario/ImageCatalogList.tsx`.

**다음 작업자 주의점**
- T17이 `AssembledPrompt`/`PromptPreviewResponse`에 필드를 추가해도 이 작업은 그 파일을 건드리지 않았다. `ImageCatalogApiTest`는 preview의 `sections`에서 `images`가 시스템 섹션의 마지막인지 본다(IMAGES가 BOTTOM 앞 마지막 슬롯이라).
- 실제 AI가 태그를 얼마나 잘 쓰는지는 리모트에서 확인하지 못했다(FakeAiProvider). 안내 문구는 `ImagesContributor.GUIDE`.
