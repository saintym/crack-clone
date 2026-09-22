# T10 첫 메시지(프롤로그)

- **상태**: TODO
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
