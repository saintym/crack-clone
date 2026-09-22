# T18 프론트: `/` 자동완성, 지시 패널, 문서 편집

- **상태**: IN_PROGRESS
- **웨이브**: 5
- **의존**: T15, T16, T17
- **브랜치**: `task/T18-frontend-commands`
- **마이그레이션**: 없음

## 목표
`/` 명령을 입력창에서 편하게 쓰게 하고, 지속 지시를 패널에서 관리하게 한다.

## 범위
- `crack-frontend/src/components/chat/ChatInput.tsx`, 신규 `src/components/chat/CommandPalette.tsx`
- 신규 `src/components/panels/directives/**`, `src/api/commands.ts`, `src/api/directives.ts`
- `src/pages/ScenarioDetailPage.tsx`(키워드북·명령 편집 탭)
- `src/pages/ChatPage.tsx`(탭 등록만)

## 구현 내용
1. **자동완성:** 입력이 `/`로 시작하면 명령 목록을 띄운다(`GET /commands`, 필터링, 방향키와 Enter로 선택).
2. **실행**
   - 시스템 명령(`/기록`, `/ooc 내용`)은 `POST /commands/system`을 호출하고 입력창을 비운다. 결과는 입력창 위 작은 토스트로 알린다(모달 금지)
   - `/ooc`만 입력하면 지시 패널을 연다
   - 사용자 정의 명령은 `POST /messages`에 `command`를 붙여 보낸다
3. **지시 패널 탭:** 목록, 켜기·끄기 토글, 수정, 삭제, 추가. 켜진 지시 개수를 헤더에 작은 숫자로 표시한다.
4. **ScenarioDetailPage:** "키워드북", "명령" 편집 탭(시나리오 원본). 스토리별 편집은 T15 문서 탭에 `keywords.md`, `commands.md`를 추가해서 처리한다.

## 완료 조건
- [ ] `npm run build`, `npm run lint` 통과
- [ ] 수동 확인
  - `/ooc 반말` → 패널에 표시 → 이후 응답에 반영(preview로 확인)
  - `/기록`
  - 사용자 정의 명령 실행

  확인 내용을 작업 로그에 적는다
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23 시작
- `task/T18-frontend-commands` 브랜치(worktree)에서 시작. 의존 작업 T15·T16·T17 머지 확인.
- 병렬 작업 주의: T19(상태 패널, ChatPage 탭 등록), T21(`index.css`, 백엔드 버그 수정)이 동시에 진행 중이다. ChatPage는 탭 등록과 입력창 연결만, `index.css`와 백엔드는 건드리지 않는다.
