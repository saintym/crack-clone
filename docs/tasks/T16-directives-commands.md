# T16 지속 OOC 지시 + `/` 명령 백엔드

- **상태**: TODO
- **웨이브**: 5
- **의존**: T13, T14
- **브랜치**: `task/T16-directives-commands`
- **마이그레이션**: 없음
- **설계**: DESIGN.md §8.1, §8.2 / 결정 D10, D11

## 목표
OOC 지시가 20턴쯤 지나면 잊히는 크랙의 문제를 해결한다. 지시를 파일로 **지속 보관**하고 매 턴 **가장 강한 위치**에 넣는다. `/` 명령 체계도 만든다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/directive/**`, `command/**`
- `chat/flow/**`에서 `command` 필드 처리 부분(T07이 받아 둔 필드)
- 신규 PromptContributor 빈 2개(지속 지시, 명령 프롬프트)
- 관련 테스트

## 구현 내용
1. **`DirectiveService`:** `directives.json` CRUD(DESIGN.md §8.1). 쓰기는 T05의 `AtomicFiles`를 쓴다.
2. **지시 주입:** 켜진 지시를 BOTTOM 슬롯에 넣는 `DirectiveContributor`. 형식은 "다음 지시는 해제될 때까지 항상 지켜라:" + 번호 목록.
3. **`CommandService`**
   - `commands.md` 파서
   - `GET /commands`: 시스템 명령(`기록`, `ooc`)과 사용자 정의 명령
   - `POST /commands/system`: body `{name, args}`. `기록`은 T14 trigger(MANUAL), `ooc`는 args가 있으면 지시 추가
4. **사용자 정의 명령**
   - `POST /messages`에 `command`가 있으면 유저 메시지를 `kind = COMMAND`로 저장한다
   - 명령 프롬프트와 args는 `PromptContext.turnInstruction`으로 넣는다(T13 BOTTOM)
   - 모르는 명령이면 400

## 완료 조건
- [ ] `./gradlew test` 통과
- [ ] 테스트 항목
  - 지시가 30턴 뒤에도 프롬프트 BOTTOM에 존재(preview로 검증)
  - 끈 지시 제외
  - `/기록` 트리거
  - 사용자 정의 명령의 turnInstruction이 그 턴에만 적용
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그
