# T20 이미지 카탈로그

- **상태**: TODO
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
- [ ] `./gradlew test`, `npm run build`, `npm run lint` 통과
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그
