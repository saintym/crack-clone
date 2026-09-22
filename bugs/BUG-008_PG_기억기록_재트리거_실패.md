# BUG-008: PostgreSQL에서 첫 기록 이후 기억 기록이 모두 실패

## 상태: 수정 예정 (T21)
## 심각도: 높음 — 핵심 기능(기억)이 첫 기록 이후 멈춤

## 증상
첫 기억 기록이 DONE이 된 뒤부터 자동 기록은 오류 로그만 남기고 실행되지 않는다. `POST /memory/record`는 500을 돌려준다. 기록할 것이 없는 경우(NOTHING_TO_RECORD)도 500이다. 기록을 되돌려 DONE이 없어지면 다시 동작한다.

## 원인
`StoryMessageRepository.findEditedTurns`의 JPQL `(:since IS NULL OR m.editedAt > :since)`에서, `since`가 null이 아니면 PostgreSQL이 파라미터 타입을 추론하지 못한다(`could not determine data type of parameter $4`, SQLState 42P18). H2 테스트에서는 드러나지 않는다.

## 발견
2026-09-23, T15 수동 확인(임시 PG 클러스터 + 실제 브라우저)에서 발견.

## 수정 방향
`since` 유무에 따라 쿼리를 둘로 나누거나 타입을 명시한다. 실제 PostgreSQL에서 두 번째 기록까지 도는지 확인한다.
