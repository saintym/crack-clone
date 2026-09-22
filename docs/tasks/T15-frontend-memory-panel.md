# T15 기억 패널

- **상태**: IN_PROGRESS
- **웨이브**: 4
- **의존**: T11, T14
- **브랜치**: `task/T15-frontend-memory-panel`
- **마이그레이션**: 없음
- **결정**: D7 (몰입 우선: 모달·승인 요청 금지)

## 목표
오른쪽 드로어(T04의 SidePanel)에 "기억" 탭을 추가한다. 스토리 문서를 보고 고치고, 기록 이력을 보고, 되돌릴 수 있게 한다. 채팅 화면에는 **작은 뱃지만** 띄운다.

## 범위
- 신규 `crack-frontend/src/components/panels/memory/**`, `src/api/memory.ts`, `src/api/storyDocuments.ts`
- `src/components/chat/ChatHeader.tsx`(뱃지와 패널 열기 버튼), `src/pages/ChatPage.tsx`(탭 등록만)

## 구현 내용
1. **문서 탭:** 연대기 · 주인공 · 인물(목록 → 문서) · 유저노트. 보기는 마크다운 렌더링이고, 편집은 텍스트 영역을 쓴다(T08 스토리 문서 API).
2. **기록 이력:** 회차 목록(범위, 상태, 시각). 항목을 열면 변경 파일별 diff(before → 현재)를 보여준다. diff 라이브러리는 `diff` npm 패키지 정도의 가벼운 것을 쓴다.
3. **되돌리기:** 가장 최근 DONE에만 버튼을 둔다. 확인은 패널 안의 인라인 확인으로 처리한다(모달 금지).
4. **수동 기록:** 패널의 "지금 기록" 버튼(`POST /memory/record`)
5. **뱃지:** `GET /messages`의 `story.memory` 상태로 표시한다. RUNNING은 작은 스피너, 새 기록은 점 하나. 패널을 열면 읽음 처리한다(`unseen`은 로컬에 저장해도 된다).
6. **RUNNING 중:** 5초 간격으로 폴링한다.

## 완료 조건
- [ ] `npm run build`, `npm run lint` 통과
- [ ] Fake 백엔드로 10턴 진행 → 뱃지 → 패널에서 변경 확인 → 되돌리기. 확인 내용을 작업 로그에 적는다
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23 시작
- `task/T15-frontend-memory-panel` 브랜치(worktree)에서 시작. 의존 작업 T11·T14 머지 확인.
- 병렬 작업 주의: T16(백엔드 지시·명령), T20(ChatBubble·ScenarioDetailPage)이 동시에 진행 중이다. ChatBubble·ScenarioDetailPage는 건드리지 않고, ChatPage는 탭 등록, ChatHeader는 뱃지·패널 버튼만 고친다.
