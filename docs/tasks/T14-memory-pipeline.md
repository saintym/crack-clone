# T14 기억 기록 파이프라인

- **상태**: TODO
- **웨이브**: 4
- **의존**: T01, T05, T07, T08
- **브랜치**: `task/T14-memory-pipeline`
- **마이그레이션**: **V7**
- **설계**: DESIGN.md §7.2~7.4 / 결정 D3, D6, D7, D8

## 목표
10턴마다 또는 `/기록` 명령으로, 시나리오 관리자 → 캐릭터 관리자(병렬) → 주인공 반영 순서로 기억 문서를 **배경에서** 갱신한다. 이력을 남기고 되돌릴 수 있게 한다. **플레이를 끊지 않는다.**

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/memory/record/**`(서비스, 관리자 프롬프트, 파서, 컨트롤러)
- `story/entity/Story.kt`(`recordedThroughTurn` 필드 추가만)
- `src/main/resources/db/migration/V7__memory_records.sql`
- `ai/fake/**`(기록용 fake 응답 등록만)
- 관련 테스트

## 구현 내용
1. **테이블과 필드:** `memory_records` 엔티티·리포지토리, `stories.recorded_through_turn`(DESIGN.md §3 V7)
2. **`MemoryRecordService.trigger(storyId, reason)`:** 싱글 플라이트, 범위 고정, 재반영 턴 계산(§7.2). 전용 실행기에서 비동기로 돈다.
3. **관리자 프롬프트:** 세 관리자(시나리오, 캐릭터, 주인공)와 압축용 프롬프트. 각 프롬프트에 §7.4 기록 기준을 그대로 넣는다. 출력 형식은 §7.3의 XML 태그다.
4. **실행**
   - 캐릭터 관리자는 병렬로 돌린다(`crack.memory.record.concurrency`, 기본 3)
   - 모든 호출은 `AiGateway`(purpose = RECORD)를 거친다
5. **원자적 반영:** 모든 결과를 모은 뒤 → before 스냅샷 → `MarkdownSections.replaceSection`과 `Chronicle`, `StoryState`(T05)로 쓰기 → DONE. 실패하면 파일을 쓰지 않고 1회 재시도한 뒤 FAILED.
6. **트리거**
   - `AfterTurnHook` 구현: `turnCount - recordedThrough >= crack.memory.record.every-turns`(기본 10)면 AUTO
   - `POST /api/stories/{id}/memory/record`는 MANUAL
7. **되돌리기와 삭제 연동**
   - `POST /memory/records/{id}/revert`: 가장 최근 DONE만 허용
   - `TruncateHook` 구현: 잘린 턴이 기록 범위 안이면 연쇄로 되돌린다(§7.2)
8. **조회**
   - `GET /memory/records`
   - `GET /memory/records/{id}`: 변경 파일별 before와 현재 내용. diff는 프론트가 계산한다
   - 메시지 목록 응답(`GET /messages`)의 `story`에 `memory: {status, lastRecordId, unseen}`을 추가한다. T07 DTO에 필드만 추가한다
9. **분기 연동(T09 메모):** 분기로 만든 스토리의 `recorded_through_turn`은 `min(원본값, 분기 기준 턴)`으로 설정한다(`StoryBranchService`). 기억 문서는 폴더째 복사되지만 `memory/history`는 복사되지 않으므로, 분기 스토리에서는 분기 전 기록을 되돌릴 수 없다.
10. **Fake 응답:** 기록용 fake 응답을 등록해 리모트에서도 파이프라인 전체를 돌릴 수 있게 한다.

## 완료 조건
- [ ] `./gradlew test` 통과. Fake 프로바이더로 통합 테스트
  - 10턴에 자동 실행되고 인물·주인공·연대기·state가 갱신되며 원본 설정 부분은 불변
  - 실패 주입 시 파일 변경 0
  - 되돌리기로 파일과 recorded_through 복원
  - 기록 범위를 자르는 삭제 시 연쇄 되돌리기
  - 수정된 과거 턴 재반영
  - 싱글 플라이트
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그
