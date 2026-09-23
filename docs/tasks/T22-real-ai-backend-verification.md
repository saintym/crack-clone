# T22 실제 AI(CLI) 백엔드 기능 실검증

- **상태**: TODO
- **웨이브**: 7
- **의존**: T21
- **브랜치**: `task/T22-real-ai-backend-verification`
- **마이그레이션**: 없음 (검증 작업)

## 목표
지금까지 검증은 Fake 프로바이더로만 했다. **실제 Claude Code CLI**로 모든 대화·기억 기능을 끝까지 돌려, 동작과 **출력 품질**을 확인한다. 발견한 문제는 버그로 등록하고, 작은 것은 고친다.

## 실행 환경 (오케스트레이터가 준비해 둠)
- PostgreSQL: Docker `crack-postgres`(postgres:15-alpine, localhost:5432, DB `crack`, user `postgres` / `artl0108`). Flyway V1~V7 적용됨
- 백엔드: `crack-backend/build/libs/crack-backend-0.0.1-SNAPSHOT.jar`가 :8082에서 실행 중. 인자는 `--crack.data-path=/Users/mayfly/work/crack-clone/data --crack.memory.record.every-turns=3`
- 프로바이더: `claude-code-cli`(유일). 한 턴 약 15~30초, 토큰 단위 스트리밍 확인됨
- 시나리오: `마도생존기`(id 1, 인물 24명, 실데이터), `테스트세계`(id 2). 스토리 1은 오케스트레이터의 CLI 확인용

## 확인할 항목
1. **대화 기본**: 전송·SSE 스트리밍(delta 순서, done 저장), 프롤로그, 이어쓰기, 재생성 후보 누적과 선택, 유저·AI 수정 후 다음 응답에 반영, 삭제, 내보내기
2. **감정 태그(D19)**: 실제 응답 첫 줄 태그가 delta와 저장 본문에 없고 `emotion` 칼럼에만 있는지
3. **기억 기록(핵심)**: 3턴마다 자동 기록이 돌고, 인물 `## 기억`·주인공 `## 변화 기록`·`chronicle.md`·`state.json`이 **의미 있게** 갱신되는지. 원본 설정 부분 불변. 되돌리기, 수정 턴 재반영, 연쇄 되돌리기
4. **기록 품질 평가(가장 중요)**: 실제 기록 결과를 읽고 §7.4 기준(큰 변화만, 영구 변화, 중복 금지)을 지키는지 평가한다. 문제가 있으면 `memory/record/RecordPrompts.kt`를 고쳐 다시 돌려 비교한다(태그를 바꾸면 파서와 Fake 응답도 함께)
5. **프롬프트 v2 실측**: 마도생존기 새 스토리에서 `prompt-preview`로 섹션 크기와 활성 인물 선택이 맞는지. 응답 지연도 기록
6. **지시·명령**: `/ooc` 지시가 여러 턴 뒤에도 실제 응답에 반영되는지(문체·말투 변화로 확인), `/기록`, 사용자 정의 명령
7. **키워드북·이미지**: 발동 여부와 AI가 `{{img:태그}}`를 실제로 쓰는지
8. **레거시 이전(T09)**: 기존 스토리 폴더(`data/마도생존기/stories/1778143520141`, `1778198020225`)는 DB 행이 없다. V4가 만들었을 행을 SQL로 넣어(`stories(scenario_id, title, dir_name, ...)`) 이전을 실행하고, 메시지·턴·`user_note`·`chronicle`·`legacy/` 결과를 확인한다
9. **스토리 격리**: 새 스토리 진행 후 시나리오 원본 문서가 변하지 않았는지(백업 `../crack-clone-data-backup-*`과 비교)

## 규칙
- **사용자 데이터:** 시나리오 원본 문서(`data/<시나리오>/*.md`, `characters/`)를 직접 고치지 마라. 스토리 폴더 생성·수정은 괜찮다. 백업은 `/Users/mayfly/work/crack-clone-data-backup-202609240013`에 있다
- 앱 재시작이 필요하면 해도 된다. 명령: `cd crack-backend && java -jar build/libs/crack-backend-0.0.1-SNAPSHOT.jar --crack.data-path=/Users/mayfly/work/crack-clone/data --crack.memory.record.every-turns=3` (필요한 설정을 인자로 덮어쓴다)
- CLI 호출은 사용자 구독을 쓴다. 같은 것을 반복해 돌리지 말고, 실패하면 원인을 보고하라
- 발견한 버그는 `bugs/BUG-0NN_*.md`로 등록하고 `bugs/README.md`에 추가한다. 프롬프트 튜닝과 한 파일 수준의 작은 수정은 직접 고쳐라(테스트 추가). 큰 수정은 등록만 하고 보고하라

## 완료 조건
- [ ] 항목 1~9 결과를 작업 로그에 표로 정리(항목 / 결과 / 근거 / 발견 문제)
- [ ] 기록 품질 평가와 프롬프트 수정 내역(수정 전후 비교 포함)
- [ ] `cd crack-backend && ./gradlew test` 통과(코드를 고친 경우)
- [ ] 상태 `REVIEW` + 작업 로그

## 작업 로그
