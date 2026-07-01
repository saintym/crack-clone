# BUG-004: SSE 스트리밍 파싱 오류

## 상태: 수정완료

## 증상
AI 채팅 시 스트리밍 텍스트가 화면에 표시되지 않거나 잘못된 내용 표시

## 원인
SSE 이벤트 형식:
```
event:delta
data:안녕하세요

event:delta
data:반갑습니다
```

기존 파싱 코드에서 `data:` 라인을 만나면 `lines.find(l => l.startsWith('event:'))`로 이벤트 타입을 찾았는데, 이는 해당 data와 쌍을 이루는 event가 아닌 배열 내 첫 번째 event 라인을 항상 반환

## 수정 내용
- **파일**: `crack-frontend/src/pages/ChatPage.tsx`
- `currentEvent` 변수를 두고 `event:` 라인에서 저장, `data:` 라인에서 사용 후 초기화하는 순차 파싱으로 변경

## 근본 원인
SSE 프로토콜의 event-data 쌍 구조를 고려하지 않은 파싱 로직
