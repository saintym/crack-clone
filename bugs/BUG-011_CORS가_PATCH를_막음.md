# BUG-011: CORS 허용 목록에 PATCH가 없어 수정 요청이 403

## 상태: 수정완료 (2026-09-23)
## 심각도: 높음 — 개발 환경에서 메시지 수정과 지시 켜기·끄기·수정이 동작하지 않음

## 증상
Vite 개발 서버(프록시 `changeOrigin: true`)를 거치면 Origin과 Host가 달라 Spring이 모든 요청을 CORS 요청으로 처리한다. 이때 `PATCH /api/stories/{id}/messages/{id}`, `PATCH /api/stories/{id}/directives/{id}`가 403으로 거부된다.

## 원인
`WebConfig.addCorsMappings`의 `allowedMethods`에 `PATCH`가 빠져 있었다. 옛 API에는 PATCH가 거의 없어서 드러나지 않았다.

## 발견
T18 브라우저 수동 확인 중 발견. curl에 Origin 헤더를 붙이면 403, 빼면 정상임을 확인했다.

## 수정
`allowedMethods`에 `PATCH`를 추가했다. 회귀 테스트 `WebConfigCorsTest`(PATCH 프리플라이트 허용)를 추가했고, 수정 전 코드로 돌리면 실패하는 것을 확인했다.
