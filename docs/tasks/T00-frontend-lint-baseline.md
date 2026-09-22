# T00 프론트 lint 기준선 복구

- **상태**: IN_PROGRESS
- **웨이브**: 1
- **의존**: 없음
- **브랜치**: `task/T00-frontend-lint-baseline`
- **마이그레이션**: 없음

## 목표
`npm run lint`가 통과하도록 기존 오류를 고친다. 이후 모든 프론트 작업의 완료 조건이 "lint 통과"이기 때문이다.

## 범위
- `crack-frontend/src/pages/ScenarioDetailPage.tsx`, `ScenariosPage.tsx`, `StoriesPage.tsx`
- **수정 금지:** `ChatPage.tsx`. 이 파일의 lint 오류(74행 `react-hooks/immutability`)는 T04가 분리하면서 해결한다

## 현재 오류 (2026-09-23 기준)
```
ScenarioDetailPage.tsx:24,26  react-hooks/immutability (선언 전 함수 접근)
ScenarioDetailPage.tsx:28     react-hooks/set-state-in-effect
ScenarioDetailPage.tsx:30     warn exhaustive-deps
ScenariosPage.tsx:25          react-hooks/set-state-in-effect
StoriesPage.tsx:18            react-hooks/immutability
StoriesPage.tsx:19            warn exhaustive-deps
```

## 구현 내용
- 로드 함수를 `useCallback`으로 감싸 effect보다 먼저 선언하거나 effect 안으로 옮긴다.
- effect 안에서 동기로 setState하는 부분은 비동기 로드 흐름 안으로 옮긴다. 규칙을 끄는(eslint-disable) 방식은 쓰지 않는다.
- 동작은 바꾸지 않는다.

## 완료 조건
- [ ] `npm run lint`에서 위 세 파일의 오류와 경고 0 (ChatPage 오류만 남음)
- [ ] `npm run build` 통과
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 대상 세 파일의 lint 오류 확인부터 진행.
