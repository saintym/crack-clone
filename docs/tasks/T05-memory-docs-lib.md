# T05 기억 문서 포맷 라이브러리

- **상태**: IN_PROGRESS
- **웨이브**: 1
- **의존**: 없음
- **브랜치**: `task/T05-memory-docs-lib`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §7.1

## 목표
인물 `## 기억`, 주인공 `## 변화 기록`, 연대기, `state.json`을 읽고 쓰는 **순수 라이브러리**를 만든다. LLM 호출은 없다. T13, T14, T19가 이 라이브러리를 쓴다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/memory/docs/**`
- 신규 `src/test/kotlin/com/crack/memory/docs/**`
- `data/_templates/character.md`, `data/_templates/protagonist.md` (`별칭` 줄과 빈 기억 섹션 안내 추가)
- 신규 `data/_templates/chronicle.md`
- **수정 금지:** 기존 `memory/service/**`(T12 정리 대상)

## 구현 내용
1. `MarkdownSections`: `##` 단위 섹션 읽기, 교체, 없으면 끝에 추가. 섹션 밖 원본 텍스트는 **바이트 단위로 보존**한다.
2. `CharacterDoc`: 이름(파일명 기준), `parseAliases()`(`- **별칭**: a, b` 줄), `memorySection`(`## 기억`)과 그 하위 `###` 파싱(관계/사건/소지품·기술·신체 → 구조체)
3. `ProtagonistDoc`: `## 변화 기록`과 하위 `### 관계`, `### 스탯·기술`, `### 소지품`, `### 신체`
4. `Chronicle`: `## 장 요약`과 `## 회차 N (턴 a–b)` 목록을 파싱한다. 제공 기능은 `append(entry)`, `rawLength()`, `oldestEntries(n)`, `replaceOldestWithSummary(n, summary)`.
5. `StoryState`: `state.json` 읽기·쓰기(Jackson). 파일이 없으면 빈 기본값을 쓴다.
6. `MemoryBudgets`: `crack.memory.budget.*` 설정 바인딩(DESIGN.md 기본값)과 `exceeds()`
7. 파일 쓰기는 임시 파일에 쓴 뒤 원자적으로 이동(`ATOMIC_MOVE`)하는 헬퍼 `AtomicFiles`로 한다.

## 완료 조건
- [ ] `./gradlew test` 통과
- [ ] 테스트 항목
  - 섹션 교체 시 원본 보존(앞뒤 공백·줄바꿈 포함)
  - 섹션이 없을 때 추가
  - 별칭 파싱
  - 연대기 append와 압축 치환
  - state.json 왕복
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23
- 작업 시작. 브랜치 `task/T05-memory-docs-lib`.
