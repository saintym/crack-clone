# 오케스트레이션 로그

> [docs/PARALLEL.md](./PARALLEL.md) 방식으로 작업을 병렬 진행하면서 생긴 **오류, 충돌, 판단**을 기록한다.
> - 운영: 로컬 worktree(`scripts/task-worktree.sh`) + 오케스트레이터 세션 1개 + 작업 에이전트 최대 4개 동시
> - 머지: 오케스트레이터가 리뷰한 뒤 main에 `--no-ff` 머지 → DONE 처리 → push
> - 각 작업의 상세 기록은 해당 작업 파일(`docs/tasks/T??-*.md`)의 작업 로그에 있다. 여기에는 **운영 관점의 문제와 해결**만 모은다.

## 요약

| 라운드 | 시작 | 작업 | 결과 |
|---|---|---|---|
| 1 | 2026-09-23 | T03, T01, T02, T05 (+T04) | T03 머지 |

## 오류·문제 기록

기록 형식: `[작업] 증상 → 원인 → 해결 → 재발 방지`

### 사전 준비 단계 (2026-09-23)
- **[기준선] 백엔드 테스트 1건 실패** → `PromptAssemblerTest`가 BASE_RULE 문구 변경을 따라가지 못함 → 테스트 기대 문자열을 현재 문구로 갱신(`dd7fce2`) → 에이전트가 첫 테스트 실행에서 무관한 실패를 만나지 않도록 작업 시작 전에 기준선을 녹색으로 맞춘다.
- **[기준선] 프론트 빌드 실패** → `verbatimModuleSyntax`인데 `ReactNode`를 값 import함(TS1484) → `import type`으로 수정(`992b570`).
- **[기준선] 프론트 lint 오류 6건** → React Hooks 새 규칙(immutability, set-state-in-effect) 위반 → 범위가 커서 T00(ChatPage 외 페이지)과 T04(ChatPage)로 분리. 두 작업이 머지되기 전까지 기준은 "새 lint 오류를 만들지 않는다".
- **[스크립트] 새 작업 브랜치가 `origin/main`을 추적** → `git worktree add -b`가 원격 브랜치를 시작점으로 받으면서 자동으로 추적을 설정함 → `--no-track` 추가. 추적이 있으면 `git push` 한 번에 main으로 올라갈 위험이 있다.

### 라운드 1
- **[T03] 빌드·테스트 오류 없음.** 에이전트가 스스로 발견하고 처리한 함정:
  - JPA 1차 캐시 때문에 락을 잡은 뒤 다시 읽어도 옛 엔티티가 돌아옴 → 락을 건 뒤 JPQL로 다시 조회
  - `@Modifying(clearAutomatically)`가 락 걸린 Story 엔티티를 분리함 → 삭제 뒤 다시 읽어 갱신
  - H2(`ddl-auto`) 스키마에는 `ON DELETE CASCADE`가 없음 → 후보, 메시지 순으로 명시적 삭제
  - 테스트 설정 차이로 Spring 컨텍스트가 2개 떠서 같은 H2 메모리 DB를 `create-drop`으로 공유 → `@Import`를 통일해 컨텍스트 1개로
- **[T03] 계약 보정:** 턴 번호를 `stories.turn_count + 1`이 아닌 `MAX(turn_no) + 1`로 계산하도록 DESIGN.md §3을 고쳤다. 기존 스토리는 파일 기반 turn_count가 이미 커서, 새 테이블과 어긋나기 때문이다.
- **[T03→T02] 머지 순서 영향:** T03이 먼저 머지되어, T02(Story 생성자 변경)가 rebase할 때 `MessageTestSupport.createTestStory`를 함께 고쳐야 한다.
