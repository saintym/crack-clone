# BUG-009: 읽음 처리가 진행 중(RUNNING) 기록까지 읽음으로 바꿈

## 상태: 수정완료 (T21, 2026-09-23)
## 심각도: 중간

## 증상
기록이 진행 중일 때 `POST /memory/records/seen`을 호출하면 RUNNING 기록도 `seen = true`가 된다. 그러면 그 기록이 끝난 뒤 새로고침해도 뱃지가 뜨지 않는다. 프론트(T15)는 RUNNING 중에는 호출하지 않는 방식으로 피해 가고 있다.

## 수정 방향
서버에서 DONE과 FAILED 기록만 읽음 처리한다.

## 수정 (T21)
- `MemoryRecordRepository.markSeen(storyId, statuses)`가 `status IN (DONE, FAILED)`인 기록만 바꾼다(기존 `markAllSeen` 대체). DESIGN.md §7.2 API 설명도 고쳤다.
- 회귀 테스트 `MemoryRecordSeenTest`: RUNNING은 읽음이 되지 않고, 끝난 뒤 `unseen = true`가 된다. 다른 스토리 기록은 건드리지 않는다.
- 프론트(T15)가 RUNNING 중 호출을 피하는 동작은 그대로 두어도 무해하다.
