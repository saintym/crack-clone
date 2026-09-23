# 작업 보드

> **T00~T23 전부 `DONE`.** 다음은 T24(웨이브 8)부터다. 이어받는 세션은 [docs/HANDOFF.md](../HANDOFF.md)를 먼저 읽고, 새 작업은 §3.2 절차대로 `T22`부터 추가한다.

> 설계: [docs/DESIGN.md](../DESIGN.md) · 병렬 운영(worktree, 일정, 머지): [docs/PARALLEL.md](../PARALLEL.md) · 결정 사항: [Plan-roadmap.md](../../Plan-roadmap.md) · 에이전트 규칙: [CLAUDE.md](../../CLAUDE.md)

## 진행 방식
- 작업 하나 = 브랜치 하나 = PR 하나. 브랜치 이름은 각 작업 파일에 적혀 있다.
- 사용자가 리모트 세션마다 **작업 ID를 지정**한다(예: "T07 진행해"). 에이전트는 그 작업 파일만 보고 일한다.
- **상태는 각 작업 파일의 `상태:` 줄에만 적는다.** 이 README에는 상태를 적지 않는다. 여러 PR이 같은 파일을 고쳐 충돌하는 것을 막기 위해서다.
- 현재 상태 한눈에 보기:
  ```bash
  grep -H '^- \*\*상태\*\*' docs/tasks/T*.md
  ```
- 상태 값: `TODO` → `IN_PROGRESS` → `REVIEW`(PR 올림) → `DONE`(main에 머지됨). 막히면 `BLOCKED`와 이유를 적는다.
- `IN_PROGRESS`, `REVIEW`, `BLOCKED`는 작업 세션이 자기 브랜치에서 바꾼다. **`DONE`은 머지한 사람이 main에서** 바꾼다(docs/PARALLEL.md §7.2).
- 상태만 빠르게 보기: `scripts/task-worktree.sh status`
- **의존 작업이 `DONE`이 아니면 시작하지 않는다.** 의존 작업이 main에 머지된 뒤에 브랜치를 딴다.

## 웨이브와 의존 관계

같은 웨이브 안의 작업은 **병렬로 진행**할 수 있다. 작업마다 "범위" 절에 수정 가능한 파일을 정해 두어 충돌을 줄였다.

| 웨이브 | ID | 작업 | 의존 |
|---|---|---|---|
| 1 | [T00](./T00-frontend-lint-baseline.md) | 프론트 lint 기준선 복구 (ChatPage 제외) | — |
| 1 | [T01](./T01-ai-gateway.md) | AI 계층 리팩터링 (Gateway, 콜백 스트리밍, CLI 개선, Fake 프로바이더) | — |
| 1 | [T02](./T02-data-paths.md) | 데이터 경로 계산 방식 변경 + BUG-005 근본 수정 (V4) | — |
| 1 | [T03](./T03-message-store.md) | 메시지 DB 저장소 (V5) | — |
| 1 | [T04](./T04-frontend-split.md) | ChatPage 컴포넌트/훅 분리 (동작 변화 없음) | — |
| 1 | [T05](./T05-memory-docs-lib.md) | 기억 문서 포맷 라이브러리 | — |
| 1 | [T06](./T06-keyword-matcher.md) | 키워드 매칭 엔진 | — |
| 2 | [T07](./T07-chat-flow.md) | 채팅 흐름 재작성 (서버 저장, 재생성 후보, 수정, 이어쓰기) | T01, T03 |
| 2 | [T08](./T08-story-isolation.md) | 스토리 격리 (원본 통째 복사) + 스토리 문서 API | T02 |
| 2 | [T09](./T09-legacy-migration.md) | 기존 데이터 이전 + 분기 이식 | T03, T08 |
| 2 | [T10](./T10-prologue.md) | 첫 메시지(프롤로그) | T03, T08 |
| 3 | [T11](./T11-frontend-chat.md) | 프론트 채팅을 새 API로 연동 (후보 ‹›, 수정, 프롤로그) | T04, T07, T10 |
| 3 | [T12](./T12-cleanup.md) | 죽은 코드와 옛 경로 정리 (V6) | T07, T08, T09, T11 |
| 4 | [T13](./T13-prompt-v2.md) | 프롬프트 조립 v2 (기여자 구조, 활성 인물, 크기 측정) | T05, T06, T07, T08 |
| 4 | [T14](./T14-memory-pipeline.md) | 기억 기록 파이프라인 (V7) | T01, T05, T07, T08 |
| 4 | [T15](./T15-frontend-memory-panel.md) | 기억 패널 (문서 보기·편집, 기록 이력, 되돌리기, 뱃지) | T11, T14 |
| 5 | [T16](./T16-directives-commands.md) | 지속 OOC 지시 + `/` 명령 백엔드 | T13, T14 |
| 5 | [T17](./T17-keyword-book.md) | 키워드북 | T13 |
| 5 | [T18](./T18-frontend-commands.md) | 프론트: `/` 자동완성, 지시 패널, 유저노트·키워드북·명령 편집 | T15, T16, T17 |
| 6 | [T19](./T19-status-panel.md) | 인물 상태 패널 | T14, T15 |
| 6 | [T20](./T20-image-catalog.md) | 이미지 카탈로그 | T11, T13 |
| 6 | [T21](./T21-pg-verification-bugfix.md) | 실제 PostgreSQL 통합 검증 + 버그 수정(BUG-008~010) — 운영 중 추가 | T14, T15 |
| 7 | [T22](./T22-real-ai-backend-verification.md) | 실제 AI(CLI) 백엔드 기능 실검증 + 기억 프롬프트 품질 튜닝 | T21 |
| 7 | [T23](./T23-browser-ui-verification.md) | 브라우저 UI 실검증 (실제 AI) | T21 |
| 8 | [T24](./T24-world-digest-index.md) | 세계관 다이제스트 + 시나리오 색인, 원문 10턴, 마스터 역할 명시 | T22 |
| 8 | [T25](./T25-deep-query-fallback.md) | 검색이 헛돈 턴의 깊은 질의 1회 | T24 |
| 9 | [T26](./T26-offscreen-characters.md) | 오프스크린 캐릭터 진행 — **보류**(사용자 테스트 후) | T24, T25 |

진행 흐름 요약 (정확한 의존 관계는 위 표가 기준이다):
- **웨이브 1:** 7개 동시 진행. 서로 의존하지 않는다
- **웨이브 2:** T01·T03 → T07 / T02 → T08 → T09·T10
- **웨이브 3:** T04·T07·T10 → T11 → T12(정리)
- **웨이브 4:** 기억 v2. T13(프롬프트)과 T14(파이프라인)는 동시 진행 → T15(패널)
- **웨이브 5:** T16·T17 동시 → T18
- **웨이브 6:** T19·T20 동시

## 마이그레이션 번호 (미리 배정됨)
V4 = T02 · V5 = T03 · V6 = T12 · V7 = T14. 다른 작업은 마이그레이션을 추가하지 않는다. 필요하면 DESIGN.md를 먼저 고친다.

## 로드맵 마일스톤과의 대응
- **M0 기반 정비:** T00, T01, T02, T03, T04, T07, T08, T09, T12
- **M1 기억 시스템 v2:** T05, T06, T13, T14, T15
- **M2 OOC와 `/`:** T16, T17, T18
- **M3 대화 조작:** T07, T10, T11 (수정, 재생성 후보, 프롤로그)
- **M4 상태와 이미지:** T19, T20
