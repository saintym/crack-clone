# BUG-008: PostgreSQL에서 첫 기록 이후 기억 기록이 모두 실패

## 상태: 수정완료 (T21, 2026-09-23)
## 심각도: 높음 — 핵심 기능(기억)이 첫 기록 이후 멈춤

## 증상
첫 기억 기록이 DONE이 된 뒤부터 자동 기록은 오류 로그만 남기고 실행되지 않는다. `POST /memory/record`는 500을 돌려준다. 기록할 것이 없는 경우(NOTHING_TO_RECORD)도 500이다. 기록을 되돌려 DONE이 없어지면 다시 동작한다.

## 원인
`StoryMessageRepository.findEditedTurns`의 JPQL `(:since IS NULL OR m.editedAt > :since)`에서, `since`가 null이 아니면 PostgreSQL이 파라미터 타입을 추론하지 못한다(`could not determine data type of parameter $4`, SQLState 42P18). H2 테스트에서는 드러나지 않는다.

## 발견
2026-09-23, T15 수동 확인(임시 PG 클러스터 + 실제 브라우저)에서 발견.

## 수정 방향
`since` 유무에 따라 쿼리를 둘로 나누거나 타입을 명시한다. 실제 PostgreSQL에서 두 번째 기록까지 도는지 확인한다.

## 수정 (T21)
- `findEditedTurns`(since 없음)와 `findEditedTurnsSince`(since 비교)로 쿼리를 나누고, `MessageService.editedTurnsSince`가 `since` 유무로 고른다.
- 회귀 테스트 `EditedTurnsQueryTest`: H2로는 재현되지 않아 ① 분기 호출 ② 모든 저장소 `@Query`에 `:파라미터 IS [NOT] NULL` 패턴이 없는지를 검사한다.
- 실제 PostgreSQL 14(임시 클러스터)에서 자동 기록 3회 연속 DONE(두 번째는 수정한 턴 3을 재반영), 재반영만 있는 기록 DONE, 기록할 것이 없을 때 `NOTHING_TO_RECORD`(200)를 확인했다.
