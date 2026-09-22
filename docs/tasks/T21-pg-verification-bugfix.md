# T21 실제 PostgreSQL 통합 검증 + 버그 수정

- **상태**: IN_PROGRESS
- **웨이브**: 6
- **의존**: T14, T15
- **브랜치**: `task/T21-pg-verification-bugfix`
- **마이그레이션**: 없음 (기존 V1~V7 검증만. 스키마 수정이 필요하면 DESIGN.md를 먼저 고치고 V8 사용)

## 목표
테스트는 H2로만 돌아서 PostgreSQL에서만 드러나는 문제가 있었다(BUG-008). 실제 PostgreSQL에서 V1~V7과 주요 흐름 전체를 검증하고, 발견된 버그를 고친다.

## 범위
- `crack-backend/src/main/kotlin/com/crack/message/repository/StoryMessageRepository.kt` (BUG-008)
- `crack-backend/src/main/kotlin/com/crack/memory/record/**` 중 읽음 처리 부분 (BUG-009)
- `crack-frontend/src/index.css` (BUG-010)
- 검증 중 발견한 **PG 전용 문제**의 최소 수정. 발견 목록과 수정 이유는 작업 로그에 적는다
- 관련 테스트, `bugs/`

## 구현 내용
1. **BUG-008:** `findEditedTurns`를 `since` 유무에 따라 나누거나 타입을 명시해 PG에서 동작하게 한다. 회귀 테스트를 추가한다(H2에서 재현이 안 되면 쿼리 분기 자체를 검증).
2. **BUG-009:** 읽음 처리는 DONE과 FAILED만 대상으로 한다. 테스트를 추가한다.
3. **BUG-010:** `index.css`의 전역 리셋을 `@layer base`로 감싼다. 주요 화면의 여백이 정상인지 브라우저로 확인한다.
4. **PG 스모크 테스트** (임시 클러스터, 사용자 DB 금지)
   - 빈 DB에 Flyway V1~V7 적용 → `ddl-auto: validate` 통과
   - Fake 프로바이더로 확인할 흐름:
     - 시나리오·스토리 생성, 프롤로그
     - 전송 21턴 이상(기억 기록 **2회 이상**), 재생성 후보, 수정
     - 기록 범위를 자르는 삭제 → 연쇄 되돌리기
     - 지시·명령(`/기록`, `/ooc`, 사용자 정의)
     - 키워드북·이미지 preview, 분기
     - 옛 스토리 이전(임시 폴더에 만든 옛 구조 픽스처)
   - 결과를 작업 로그에 표로 남긴다(흐름 / 결과 / 발견 버그).

## 완료 조건
- [ ] `./gradlew test` 통과, `npm run build`와 `npm run lint` 통과
- [ ] PG 스모크 결과 표. 발견한 버그는 수정했거나 `bugs/`에 등록
- [ ] BUG-008~010 상태 갱신
- [ ] 상태 `REVIEW` + 작업 로그

## 작업 로그

### 2026-09-23
- 시작. BUG-008~010 수정 후 임시 PG 클러스터로 스모크 테스트 진행 예정.
