# T18 프론트: `/` 자동완성, 지시 패널, 문서 편집

- **상태**: DONE
- **웨이브**: 5
- **의존**: T15, T16, T17
- **브랜치**: `task/T18-frontend-commands`
- **마이그레이션**: 없음

## 목표
`/` 명령을 입력창에서 편하게 쓰게 하고, 지속 지시를 패널에서 관리하게 한다.

## 범위
- `crack-frontend/src/components/chat/ChatInput.tsx`, 신규 `src/components/chat/CommandPalette.tsx`
- 신규 `src/components/panels/directives/**`, `src/api/commands.ts`, `src/api/directives.ts`
- `src/pages/ScenarioDetailPage.tsx`(키워드북·명령 편집 탭)
- `src/pages/ChatPage.tsx`(탭 등록만)

## 구현 내용
1. **자동완성:** 입력이 `/`로 시작하면 명령 목록을 띄운다(`GET /commands`, 필터링, 방향키와 Enter로 선택).
2. **실행**
   - 시스템 명령(`/기록`, `/ooc 내용`)은 `POST /commands/system`을 호출하고 입력창을 비운다. 결과는 입력창 위 작은 토스트로 알린다(모달 금지)
   - `/ooc`만 입력하면 지시 패널을 연다
   - 사용자 정의 명령은 `POST /messages`에 `command`를 붙여 보낸다
3. **지시 패널 탭:** 목록, 켜기·끄기 토글, 수정, 삭제, 추가. 켜진 지시 개수를 헤더에 작은 숫자로 표시한다.
4. **ScenarioDetailPage:** "키워드북", "명령" 편집 탭(시나리오 원본). 스토리별 편집은 T15 문서 탭에 `keywords.md`, `commands.md`를 추가해서 처리한다.

## 완료 조건
- [ ] `npm run build`, `npm run lint` 통과
- [ ] 수동 확인
  - `/ooc 반말` → 패널에 표시 → 이후 응답에 반영(preview로 확인)
  - `/기록`
  - 사용자 정의 명령 실행

  확인 내용을 작업 로그에 적는다
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그

### 2026-09-23 시작
- `task/T18-frontend-commands` 브랜치(worktree)에서 시작. 의존 작업 T15·T16·T17 머지 확인.
- 병렬 작업 주의: T19(상태 패널, ChatPage 탭 등록), T21(`index.css`, 백엔드 버그 수정)이 동시에 진행 중이다. ChatPage는 탭 등록과 입력창 연결만, `index.css`와 백엔드는 건드리지 않는다.

### 2026-09-23 구현 완료 (REVIEW)

**한 일**
- `src/api/commands.ts`(`GET /commands`, `POST /commands/system`, `/명령 인자` 파싱, 대소문자 무시 조회), `src/api/directives.ts`(지시 CRUD).
- `src/components/chat/`
  - `ChatInput.tsx`: `/`로 시작하고 공백 전(이름 입력 중)이면 자동완성. ↑↓로 이동, Enter·Tab으로 이름 채우기(`/기 ` → `/기록 `), 이름을 다 친 상태의 Enter는 바로 실행, Esc로 닫기, 마우스 선택. 보낼 때 명령을 나눈다.
    - 시스템 명령: `POST /commands/system` → 입력창 위 작은 토스트(3.5초, 모달 없음). `/기록`은 결과(시작/실행 중/기록할 것 없음) 문구, `/ooc 내용`은 "지시를 추가했습니다". 실패하면 입력을 되돌리고 붉은 토스트.
    - `/ooc`만: 요청 없이 지시 탭으로 패널을 연다.
    - 사용자 정의 명령: `send(content, command)` → `POST /messages`에 `command`(서버가 준 이름 그대로).
    - 없는 명령: 보내지 않고 "없는 명령입니다" 토스트 + 입력 복구. 상황서술 모드에서는 명령으로 읽지 않는다.
  - `CommandPalette.tsx`(목록 표시, 시스템 표시, listbox/combobox aria), `useCommandList.ts`(목록 캐시와 필터).
- `src/components/panels/directives/**`: `useDirectives`(목록·켜진 개수·추가/수정/켜기끄기/삭제, 서버 응답으로 목록 갱신), `DirectivesPanel`(추가 입력, 토글 스위치, 인라인 수정, 인라인 삭제 확인), `directivesPanelTab`(탭 머리에 켜진 개수 뱃지).
- 기억 패널 문서(`DocumentsView`)에 "이 스토리의 설정" 묶음으로 `keywords.md`(키워드북), `commands.md`(명령) 추가. 기존 `DocumentEditor`를 그대로 쓴다(없으면 편집해서 만든다).
- `ScenarioDetailPage`: "키워드북", "명령" 탭(시나리오 원본, 기존 `/api/scenarios/{name}/documents/{keywords|commands}`). 형식 안내와 placeholder 예시. 탭이 8개라 탭 줄을 가로 스크롤로 바꿨다.
- `ChatPage`: 지시 탭 등록, `ChatInput`에 `storyId`·`onOpenDirectives`·`onSystemCommand` 연결(`/기록` 뒤 `memory.refresh()`, `/ooc 내용` 뒤 지시 목록 다시 받기).

**범위 밖 최소 수정**
- `src/api/chat.ts`(`SendBody.command` 한 필드), `src/hooks/useChatStream.ts`(`send(content, command?)`): 사용자 정의 명령을 `POST /messages`의 `command`로 보내려면 필요하다. 기존 호출은 그대로 동작.
- `src/components/panels/SidePanel.tsx`, `src/types/panel.ts`: 탭 `badge?`(켜진 지시 개수)와 선택 탭을 밖에서 정하는 `activeTabId`/`onSelectTab`(없으면 기존처럼 안에서 기억). `/ooc`로 지시 탭을 열려면 필요하다. T19가 탭만 추가한다면 충돌하지 않는다.
- `panels/memory/DocumentsView.tsx`: 작업 파일 지시대로 문서 두 줄 추가.

**설계 판단**
- "켜진 지시 개수를 헤더에"는 패널 탭 머리(탭 이름 옆 숫자)로 했다. 채팅 헤더의 패널 버튼 뱃지 자리는 T15 기억 뱃지(스피너·점)가 쓰고, 숫자까지 겹치면 몰입을 해친다고 봤다. 패널 안 목록 위에도 "지시 N개 · 켜짐 M개"를 적었다.
- 명령 목록은 `/`를 새로 입력할 때마다 다시 받는다. 기억 패널에서 `commands.md`를 고치면 다음 입력부터 반영된다. 보낼 때 목록이 아직 없으면 받고 나서 판단한다.
- 모르는 `/이름`은 일반 메시지로 보내지 않고 막았다. 오타 명령이 대화 원문에 남는 것을 막기 위해서다. 정말 `/`로 시작하는 대사가 필요하면 상황서술 모드나 앞에 다른 글자를 쓰면 된다.
- 명령 실행 결과 토스트는 입력창 위 가운데 한 줄(말줄임)이고, 자동완성이 열려 있을 때는 숨긴다. 실패로 `/명령`을 되돌릴 때는 자동완성을 닫아 토스트가 가려지지 않게 했다.
- 지시 패널은 작아서 지연 로딩하지 않았다(메인 청크 276 → 294kB, 경고 없음).
- 입력창 placeholder는 모바일 420px에서도 한 줄이 되게 줄였다("메시지 · / 명령", "메시지 · 빈 Enter: 이어쓰기", 넘치면 `placeholder:truncate`). 오케스트레이터 요청(T21이 발견).

**확인**
- `npm run build`, `npm run lint` 오류 0, 번들 경고 없음.
- **브라우저 확인(Fake 백엔드):** 스크래치에 `initdb` 임시 클러스터(127.0.0.1:55443, 소켓 끔, DB `crack_t18`), `sample-scenario` 픽스처 복사 데이터, bootJar를 명령줄 인자로 datasource·`crack.data-path`·fake·`--crack.auth.password= --server.port=18218` 덮어쓰기, Vite `CRACK_WEB_PORT=15218`. Playwright(로컬 캐시 Chromium 1243, headless)로 화면 조작.
  - `/` → 목록 3개(기록·ooc 시스템, 일기 사용자 정의). `/o` → ooc만. ↓↓ Enter → `/일기 `. Esc → 닫힘.
  - `/ooc 말투는 반말` → 토스트 "지시를 추가했습니다: 말투는 반말", `directives.json`에 추가, `prompt-preview`에 "다음 지시는 해제될 때까지…" + "말투는 반말". 이후 한 턴 보낸 뒤 preview에도 남아 있음.
  - `/ooc` 단독 → 입력창 비움, 패널이 지시 탭으로 열림, 탭 머리 숫자 1.
  - 패널: 추가(숫자 2) → 첫 지시 끄기(숫자 1, preview에서 빠지고 다른 지시는 남음) → 다시 켜기 → 두 번째 인라인 수정 → 인라인 확인 후 삭제. 서버 목록이 화면과 같음.
  - `/기록`(새 스토리, 2턴) → 토스트 "기억 기록을 시작했습니다", `MANUAL DONE 1–2`, 메시지는 남지 않음. 같은 스토리에서 다시 `/기록` → BUG-008로 500 → 붉은 토스트 "/기록 명령을 실행하지 못했습니다" + 입력 복구(알려진 버그, 무시).
  - `/일` Enter → `/일기 ` → "오늘은 산책을 했다" 전송 → 유저 메시지 `kind=COMMAND`, content `/일기 오늘은 산책을 했다`, 응답 생성.
  - `/없는명령 abc` → "없는 명령입니다" 토스트, 입력 복구.
  - 기억 패널 → 문서 → 키워드북: 스토리의 `keywords.md` 마크다운 보기. 시나리오 편집 화면 명령 탭에 `/일기` 표시, 키워드북 탭 표시.
  - 모바일(420px): placeholder 한 줄(텍스트 영역 높이 한 줄), 자동완성 목록, 하단 시트 지시 패널 표시.
  - 끝난 뒤 백엔드·Vite·프록시 중지, 클러스터 중지, 스크래치 삭제.

**발견한 문제 (범위 밖, 고치지 않음)**
- **[백엔드, 중요] CORS 설정이 PATCH를 허용하지 않는다.** `global/config/WebConfig.kt`의 `allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")`에 `PATCH`가 없다. Vite 프록시(`changeOrigin: true`)를 거치면 `Origin`(웹 포트)과 `Host`(API 포트)가 달라 Spring이 CORS 요청으로 보고 **모든 PATCH를 403**으로 막는다(`curl -XPATCH -H 'Origin: http://localhost:15218'` → 403, Origin 없이 → 정상). 지시 켜기·끄기·수정(`PATCH /directives/{id}`)과 **기존 메시지 수정(`PATCH /messages/{id}`)도 개발 환경에서 안 된다.** 확인 때는 Origin 헤더를 떼는 작은 프록시를 Vite와 백엔드 사이에 두고 돌렸다. `"PATCH"`를 추가하면 된다.
- 백엔드 500 응답에는 `message`가 없어 프론트는 기본 문구("…실행하지 못했습니다")를 보인다(BUG-008 경로에서 확인).

**다음 작업자 주의점**
- 다른 패널에서 지시 탭을 열려면 ChatPage의 `setPanelTabId(DIRECTIVES_TAB_ID)` + `setPanelOpen(true)` 방식을 쓴다.
- 이 브랜치는 BUG-010 수정 전 기준이라 스크린샷의 여백이 0으로 보였다. T21 머지 뒤에는 Tailwind 여백이 적용된다.
