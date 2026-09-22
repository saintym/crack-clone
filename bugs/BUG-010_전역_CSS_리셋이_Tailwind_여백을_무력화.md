# BUG-010: 전역 CSS 리셋이 Tailwind 여백 유틸리티를 무력화

## 상태: 수정완료 (T21, 2026-09-23)
## 심각도: 중간 — 앱 전체 UI

## 증상
말풍선, 버튼, 패널 등 모든 화면에서 `p-*`, `m-*` 여백 클래스가 적용되지 않는다(여백 0).

## 원인
`crack-frontend/src/index.css`에서 레이어 밖에 있는 `* { margin: 0; padding: 0 }`가 Tailwind v4 유틸리티(`@layer utilities`)보다 우선한다. CSS cascade layers에서는 레이어 밖 규칙이 레이어 안 규칙을 이긴다.

## 발견
2026-09-23, T15 브라우저 스크린샷 확인 중 발견.

## 수정 방향
리셋 규칙을 `@layer base { … }`로 감싼다. 주요 화면(채팅, 시나리오 목록·상세, 스토리 목록, 로그인)을 브라우저로 확인한다.

## 수정 (T21)
- 전역 리셋(`*`)과 `html, body, #root`, `body` 기본 스타일을 `@layer base`로 옮겼다.
- 브라우저 확인 중 같은 원인의 문제를 하나 더 찾았다: 레이어 밖의 `.safe-top`/`.safe-bottom`이 같은 요소의 `py-*`, `pb-8`을 덮어 헤더·입력창·시트의 위아래 여백이 0이었다. `@layer components`로 옮겼다. `index.html`에 `viewport-fit=cover`가 없어 inset은 항상 0이므로 동작 변화는 없다. cover를 쓰게 되면 요소별 여백과 inset을 더하는 방식으로 다시 설계해야 한다.
- Playwright(Chromium headless, 폭 420px)로 로그인·시나리오 목록·시나리오 상세·스토리 목록·채팅 화면을 찍고, `p-*`/`px-*`/`py-*` 요소의 계산된 padding이 클래스 값과 같은지 확인했다.
