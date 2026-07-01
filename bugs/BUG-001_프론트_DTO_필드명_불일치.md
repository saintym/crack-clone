# BUG-001: 프론트엔드 DTO 필드명 불일치

## 상태: 수정완료

## 증상
캐릭터 목록이 화면에 표시되지 않음

## 원인
프론트엔드 `DocumentResponse` 인터페이스에 `fileName` 필드가 정의되어 있었으나, 백엔드 API 응답은 `name` 필드를 사용

- 프론트: `{ type, content, fileName }`
- 백엔드: `{ type, name, content }`

`CharacterInfo` 타입도 동일한 문제

## 수정 내용
- **파일**: `crack-frontend/src/api/documents.ts`
- `DocumentResponse.fileName` → `DocumentResponse.name`
- `CharacterInfo` 타입을 백엔드 응답과 일치하도록 수정

## 근본 원인
백엔드 DTO 확인 없이 프론트엔드 타입을 추정으로 작성
