# T49 질문으로 시나리오 만들기 — 화면

- **상태**: REVIEW
- **웨이브**: 8
- **의존**: **T48**(API가 main에 머지된 뒤 시작한다)
- **브랜치**: `task/T49-guided-creation-ui`
- **마이그레이션**: 없음

## 목표
[T48](./T48-guided-scenario-creation.md)이 만든 API로 **질문에 답해 시나리오를 만드는 화면**을 붙인다.

## 범위
- `crack-frontend/src/api/scenarioCreate.ts`(신규)
- `crack-frontend/src/components/scenario/ScenarioCreateSheet.tsx`(신규)
- 시나리오 목록 화면(`pages/ScenariosPage.tsx`)에 진입 버튼
- `docs/DESIGN.md`(§10 프론트 구조 표에 한 줄)

`crack-backend/` 아래는 건드리지 않는다.

## 흐름
```
seed 입력  →  질문 카드(라운드)  →  미리보기  →  이름 확인  →  생성 진행(SSE)
```

1. **seed** — 여러 줄 입력. "무협. 주인공은 몰락한 가문의 검객. 어둡고 건조하게." 정도면 된다. 비어 있으면 시작 버튼을 끈다
2. **질문 카드** — 라운드마다 최대 5개. 각 질문에 `placeholder`가 온다
   - **빈 답을 허용한다.** 「모르겠어요, 알아서 정해주세요」 버튼을 두어 그 라운드의 답을 전부 비우고 넘긴다
   - 라운드 진행 상황을 보인다(`round / max`)
3. **미리보기** — 세계관·주인공·시작 장면 요약과 **인물 목록**
   - 인물을 **지우고 더할 수 있다.** 지운 인물은 만들어지지 않는다
   - 예상 LLM 호출 수와 소요 시간을 보인다
4. **이름 확인** — `suggestedName`을 기본값으로. 이미 있는 이름이면 서버가 400을 준다
5. **생성** — SSE `step` 이벤트로 진행률. `done`이면 그 시나리오로 이동

`ScenarioImportSheet.tsx`(T28)가 같은 구조의 선례다. SSE 처리와 진행률 표시를 참고한다.

## 지킬 것
- **플레이 흐름을 가리는 모달을 쓰지 않는다**(D7). 하단 시트로 만든다
- 생성 중에 이탈해도 서버는 계속 만든다. 화면을 닫아도 된다는 것을 알려 준다
- 좁은 화면에서 잘리지 않게 한다(T36에서 겪은 것)
- `npm run build`와 `npm run lint` 통과. lint 오류 0

## 완료 조건
- [x] seed → 질문 → 미리보기 → 생성까지 화면에서 끝난다
- [x] 빈 답으로 넘길 수 있다
- [x] 미리보기에서 인물을 지우면 그 인물이 빠진다
- [x] 이름 중복 400을 사용자에게 알린다
- [x] 생성 진행률이 보이고, 끝나면 그 시나리오로 이동한다
- [x] 좁은 화면에서 잘리지 않는다
- [x] 프론트 build 통과, lint 오류 0

## 작업 로그

### 2026-09-30 (화면 구현)

**한 일**
- `src/api/scenarioCreate.ts` 신규. `start` / `answer`(둘 다 `CreateRoundResponse`) / `confirm`(SSE). SSE 파서와 스트림 읽기는 `scenarioImport.ts`와 같은 방식이고, `step`·`done` 이벤트 타입은 파이프라인이 공용이라 `ImportStep`·`ImportDone`을 `CreateStep`·`CreateDone`으로 재수출해 썼다.
- `src/components/scenario/ScenarioCreateSheet.tsx` 신규. 단계는 `seed → asking → preview → name → running → done`.
- `pages/ScenariosPage.tsx`: 헤더에 「질문」 버튼을 「URL」 옆에 두고, 빈 상태 화면에도 「질문에 답해서 만들기」를 첫 줄로 넣었다(기존 URL·빈 시나리오는 그 아래).
- `docs/DESIGN.md` §10 프론트 구조 표에 `ScenarioCreateSheet.tsx` 한 줄.

**설계 판단과 이유**
- **빈 답 처리를 두 갈래로 뒀다.** ① 질문별로 비워 둔 채 「다음」을 눌러도 그대로 보낸다(서버가 빈 답을 "네가 정하라"로 받는다) ② 「모르겠어요, 알아서 정해주세요」는 `answer`에 **빈 맵 `{}`**을 보낸다. 입력칸을 비우고 다시 누르게 하지 않고 한 번에 넘기려는 것이다. 서버가 없는 키를 미답으로 보기 때문에 빈 맵으로 충분하다.
- **인물 편집은 지우기 + 더하기만** 뒀다. 고치기(인라인 편집)는 넣지 않았다. 미리보기의 `role`·`note`는 생성 프롬프트의 힌트일 뿐이고, 정확히 쓰고 싶으면 생성 뒤 인물 문서를 고치는 편이 낫다. 지운 목록은 `confirm`의 `characters`로 그대로 올라가고(항상 보낸다), 전부 지우면 빈 배열이 가서 주인공만 만들어진다.
  - 지우기는 `filter((_, idx) => idx !== i)`로 **인덱스 기준**이다. 같은 이름이 둘이어도 하나만 지운다. key도 `이름-인덱스`다.
- **미리보기와 이름 확인을 다른 단계로 나눴다.** 명세의 흐름(`미리보기 → 이름 확인`)을 그대로 따랐고, 한 화면에 요약·인물 목록·인물 추가 폼·이름·제목을 다 넣으면 좁은 화면에서 스크롤이 너무 길어진다.
- **`round / 3` 표시의 `3`은 프론트 상수**(`MAX_ROUNDS`)다. 응답에 상한이 없어서 서버 기본값(`crack.create.max-rounds`)과 맞췄고, 실제 종료는 `done: true`가 정한다. 서버 설정을 늘리면 `Math.max(round, MAX_ROUNDS)`로 표시만 늘어나 어긋나 보이지는 않는다.
- **404(만료된 jobId)는 씨앗 단계로 되돌린다.** 30분 TTL이라 캐시가 날아가면 이어갈 방법이 없다. 씨앗 입력값은 남겨 둬서 바로 다시 시작할 수 있다.
- **이름 중복 400은 `confirm` 전에 JSON으로 온다.** `streamCreate`가 `ChatApiError`로 던지므로 `name` 단계로 되돌리고 서버 문구를 그대로 보여 준다.
- **생성 중에는 배경을 눌러도 닫히지 않게** 했고(실수로 스트림을 끊는 것 방지), 대신 「닫고 나가기」 버튼을 두고 "창을 닫아도 서버는 계속 만든다"를 명시했다.
- **좁은 화면(T36)**: 시트는 `w-full max-w-lg` + `px-5 sm:px-6`, 버튼 행은 `flex-1 min-w-0`, 인물 행은 `min-w-0` + `break-words` + 지우기 버튼 `shrink-0`, 라운드 표시는 막대 `flex-1 min-w-0` + 숫자 `shrink-0`. 헤더 버튼 묶음에 `shrink-0`을 주고 「질문」·「URL」을 `px-2.5 text-[11px]`로 줄여 320px에서도 제목과 부딪히지 않게 했다.

**확인한 방법**
- `npm ci && npm run build && npm run lint` 모두 통과(lint 오류 0, 경고 0).
- 로컬 백엔드(8082)에 `POST /api/scenarios/create/start`를 쳐서 경로가 살아 있는 것만 확인했다(인증 없이 401). **실제 LLM 흐름(질문 라운드·미리보기·SSE 생성)은 확인하지 못했다** — 오케스트레이터가 로그인해서 검증한다.

**다음 작업자가 알아야 할 것**
- 컨트롤러는 `?provider=` 쿼리를 받지만 화면에서는 보내지 않는다(서버 기본 프로바이더). 턴별 모델 선택은 제외 범위이고, 생성 프로바이더를 고르게 하려면 시트에 셀렉트를 하나 붙이면 된다.
- `scenarioCreate.ts`의 `streamCreate`는 `scenarioImport.ts`의 `streamImport`와 거의 같다(범위를 지키려고 `scenarioImport.ts`를 건드리지 않고 복제했다). 셋째 입력원이 생기면 공용 `postSse` 헬퍼로 묶는 게 좋다.
- 미리보기 인물의 `role`·`note`를 화면에서 고칠 수 없다. 필요해지면 인물 행을 인라인 편집으로 바꾸면 된다(`characters` 상태만 고치면 `confirm`은 그대로 동작한다).
