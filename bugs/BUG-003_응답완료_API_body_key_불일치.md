# BUG-003: 응답 완료 API body key 불일치

## 상태: 수정완료

## 증상
AI 응답 완료 후 `onResponseComplete`가 실행되지 않아 턴 카운트가 증가하지 않고, 자동 요약이 트리거되지 않음

## 원인
- 백엔드 `ChatController.onComplete()`: `body["response"]`를 읽음
- 프론트엔드 `chatApi.complete()`: `{ fullResponse: ... }`로 전송
- key 불일치로 백엔드에서 `null`이 되어 early return

## 수정 내용
- **파일**: `crack-frontend/src/api/chat.ts`
- `{ fullResponse }` → `{ response: fullResponse }`

## 근본 원인
백엔드 컨트롤러의 body key 확인 없이 프론트 API 클라이언트 작성
