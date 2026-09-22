# T00 프론트 lint 기준선 복구

- **상태**: DONE
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
- [x] `npm run lint`에서 위 세 파일의 오류와 경고 0 (ChatPage 오류만 남음)
- [x] `npm run build` 통과
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 대상 세 파일의 lint 오류 확인부터 진행.

**한 일**
- `StoriesPage.tsx`: `loadData`를 `useCallback`으로 감싸 effect보다 먼저 선언하고 effect 의존성을 `[loadData]`로 바꿈.
- `ScenariosPage.tsx`: `loadScenarios`를 `useCallback`으로 감싸고 effect 의존성에 넣음.
- `ScenarioDetailPage.tsx`: `loadDocument`/`loadCharacters`를 `useCallback`으로 effect 앞에 선언. 탭·시나리오 변경 시 `editing`/`selectedChar` 초기화는 effect에서 빼고, 렌더 중 이전 키(`scenarioName|tab`)와 비교해 조정하는 방식으로 옮김.

**판단과 이유**
- eslint-plugin-react-hooks 7의 `set-state-in-effect`는 React Compiler 분석을 쓰는데, **async 함수 안에서 `await` 뒤에 호출한 setState도 동기 호출로 판정한다.** `useCallback`으로 감싸기만 하면 오류가 남는다(작은 probe 컴포넌트로 확인). promise `.then/.catch/.finally` 콜백 안의 setState는 비동기로 인식되므로 effect에서 부르는 로드 함수는 then 체인으로 바꿨다. try/catch/finally와 의미가 같다.
- 편집 상태 초기화는 비동기 로드 흐름 안으로 옮기면 로드가 끝날 때까지 이전 편집 UI가 남는다. 그래서 React 문서의 "prop 변경 시 state 조정" 패턴(렌더 중 비교)을 썼다. 초기화가 커밋 이후가 아니라 같은 렌더에서 일어나는 차이만 있고, 결과 화면은 같다. `scenarioName`이 없을 때 초기화를 건너뛰던 원래 조건도 유지했다.
- 이벤트 핸들러에서만 쓰는 async 함수(`loadCharacter`, `handleSave` 등)는 규칙 대상이 아니라서 그대로 뒀다.

**확인 방법**
- `npm run build` 통과.
- `npm run lint`: 오류 1건(ChatPage.tsx 74행 `react-hooks/immutability`, T04 범위)만 남음. 세 파일의 오류·경고 0.

**주의점**
- `npm ci`가 ERESOLVE로 실패한다: `vite-plugin-pwa@1.2.0`의 peer 범위(`vite ^3~^7`)가 `vite@8`을 포함하지 않는다. 이번에는 `npm ci --legacy-peer-deps`로 설치했고 package.json/lock은 범위 밖이라 고치지 않았다. CLAUDE.md의 `npm ci` 명령이 그대로는 실패하므로 별도 처리(`.npmrc`에 `legacy-peer-deps=true` 또는 플러그인 업그레이드)가 필요하다.
- 앞으로 effect에서 호출하는 로드 함수는 async/await 대신 then 체인으로 쓰거나 effect 안의 IIFE로 쓰면 이 규칙을 통과한다.
