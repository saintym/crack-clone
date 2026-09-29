# T48 질문으로 시나리오 만들기

- **상태**: TODO
- **웨이브**: 8
- **의존**: T28(URL 가져오기 — 파이프라인을 재사용한다), T35(인지 범위), T45(주인공 규칙)
- **브랜치**: `task/T48-guided-scenario-creation`
- **마이그레이션**: 없음
- **결정**: D47

## 목표
새 시나리오를 만들 때 **모든 문서를 손으로 마크다운으로 쓰게 하지 않는다.** URL 가져오기(T28)가 하듯 **AI가 질문하고 사용자가 답하면** 문서 한 벌이 만들어지게 한다.

지금은 시나리오를 만들면 빈 템플릿이 생기고, 사용자가 `world.md`·`scenario.md`·`characters/*.md`·`protagonist.md`·`prologue.md`·`keywords.md`를 전부 직접 채워야 한다. URL이 있으면 T28이 해 주는데, **URL이 없으면 방법이 없다.**

## T28과 무엇이 같고 무엇이 다른가

| | T28 (URL 가져오기) | T48 (질문으로 만들기) |
|---|---|---|
| 입력원 | 웹페이지 HTML | **사용자와의 질의응답** |
| 질문 | **1회** (페이지에 없는 것만) | **여러 차례** (밑바닥부터 만들므로) |
| 인물 | 페이지에 있는 전원 | **AI가 제안하고 사용자가 고른다** |
| 이미지 | 페이지의 URL을 `{이름}_기본`으로 등록 | 없다(주석과 태그 목록만) |
| 생성 파이프라인 | world → characters → protagonist·prologue → images | **같다** |
| 문서 포맷 강제 | `ImportDocs` | **같은 것을 쓴다** |
| 원자적 생성 | `.import-tmp/{jobId}` → `ATOMIC_MOVE` | **같다** |

**즉 다른 것은 "맥락을 어디서 얻느냐"뿐이다.** `scenario.imports` 패키지를 URL 전용에서 **생성 파이프라인 공용**으로 정리하고 입력원만 갈아 끼운다.

## 범위
- `crack-backend/src/main/kotlin/com/crack/scenario/imports/**` — 공용 부분 분리 + 새 흐름
  - 재사용: `ImportDocs`(문서 포맷), `ImportOutputParser`, `ImportJobStore`(TTL 캐시), SSE 이벤트(`ImportStepEvent`/`ImportDoneEvent`), 원자적 생성
  - 신규: 질의응답 라운드 관리, 새 프롬프트, 새 컨트롤러
- `crack-frontend/src/api/`, `crack-frontend/src/components/scenario/` — 새 시트
- `docs/DESIGN.md`(§11을 "시나리오 생성"으로 넓히고 §11.5 추가), `Plan-roadmap.md`(D47)
- 테스트

## API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/scenarios/create/start` | body `{seed}` → `jobId` + 1차 질문 |
| POST | `/api/scenarios/create/{jobId}/answer` | body `{answers}` → 다음 질문 **또는** 준비 완료 + 미리보기 |
| POST | `/api/scenarios/create/{jobId}/confirm` | body `{name, title?, characters?}` → **SSE** 생성 |

### start
```json
요청 { "seed": "무협. 주인공은 몰락한 가문의 검객. 분위기는 어둡고 건조하게." }
응답 { "jobId": "…", "round": 1, "done": false,
       "suggestedName": "…", "title": "…",
       "questions": [{"id":"q1","text":"주인공의 이름은?","placeholder":"예: 우혁규"}] }
```
- `seed`는 **한 줄이어도 된다.** 비어 있으면 400
- LLM 1회. 질문은 한 라운드에 **5개까지**

### answer
```json
요청 { "answers": {"q1":"우혁규","q2":""} }
응답(더 물을 것이 있으면) { "round": 2, "done": false, "questions": [...] }
응답(준비되면) { "round": 2, "done": true,
  "preview": { "title":"…", "world":"한 문단 요약", "protagonist":"한 문단",
               "opening":"시작 장면 한 문단",
               "characters":[{"name":"설월","role":"화산파 일대제자","note":"주인공과 악연"}] },
  "estimatedLlmCalls": 7, "estimatedSeconds": 200 }
```
- **빈 답은 "모르겠다/알아서 해"로 받는다.** 되물어 막지 않는다
- 라운드 상한 `crack.create.max-rounds`(기본 3). 상한에 닿으면 남은 것은 AI가 정하고 `done: true`
- 라운드마다 LLM 1회

### confirm
`{name, title?, characters?}` → SSE. `characters`를 주면 **그 목록만** 만든다(미리보기에서 사용자가 지우거나 더한 결과). 없으면 미리보기 목록 그대로.

이벤트는 T28과 같다(`step`/`done`/`error`).

## 질문 설계 (프롬프트)
라운드마다 **아직 정해지지 않은 것 중 가장 중요한 것**을 묻게 한다. 고정 목록이 아니라 seed에 따라 달라져야 한다.

- 1라운드: 큰 틀 — 시대·장소·분위기, 주인공의 입지, 이야기의 갈등
- 2라운드: 인물 — 주요 인물 몇 명, 각자 주인공과의 관계
- 3라운드: 시작 — 첫 장면이 어디서 어떻게 열리는지

**규칙**
- 페이지(=seed와 지금까지의 답)에서 **이미 알 수 있는 것은 묻지 않는다**
- 한 질문에 하나만 묻는다. "이름과 나이와 소속은?" 같이 묶지 않는다
- `placeholder`에 **예시를 넣는다.** 사용자가 무엇을 적어야 하는지 바로 알게
- 취향을 묻되 **설정 상식은 AI가 채운다.** "무협의 문파 이름을 정하라"고 사용자에게 떠넘기지 않는다

## 생성 파이프라인
T28 §11.3과 같다. 차이는 셋.
1. 시스템 프롬프트의 맥락이 **추출된 페이지 대신 seed + 질의응답 전체**다
2. 인물은 **확정된 목록**만 만든다(페이지 전원이 아니라)
3. `images.md`는 **템플릿 주석 + 인물 태그 목록**만 만든다. 등록할 URL이 없다
   ```
   <!-- 등록할 인물 태그: 설월_기본, 무극_기본, … -->
   ```

**T35·T45를 따르는 문서를 만들어야 한다.**
- 인물 문서에 `## 알고 있는 것` 섹션을 만든다(비어 있어도 된다). 주인공에 대해 아는 것이 있으면 적는다
- 주인공 문서에서 남이 알 수 없는 것은 `## 배경 (비공개)`처럼 **제목에 `(비공개)`를 붙인다**
- 주인공 서술 금지는 BASE에 있으므로(T45) **시나리오 문서에 적지 않는다**

## 설정
```yaml
crack:
  create:
    max-rounds: 3               # 질문 라운드 상한
    max-questions-per-round: 5
    job-ttl-minutes: 30         # 진행 중인 생성 작업 캐시 TTL
```
인물 배치·동시 실행은 `crack.import.*`를 그대로 쓴다(같은 파이프라인이다).

## 화면
`components/scenario/ScenarioCreateSheet.tsx`(신규). `ScenarioImportSheet.tsx`와 나란히 두고, 시나리오 목록 화면에서 **「URL에서 가져오기」와 「질문으로 만들기」 두 버튼**을 준다.

- seed 입력 → 질문 카드(라운드마다) → 미리보기 → 이름 확인 → 생성 진행(SSE 진행률)
- **빈 답을 허용한다.** "모르겠어요, 알아서 정해주세요" 버튼을 둔다
- 미리보기에서 **인물 목록을 지우고 더할 수 있게** 한다
- 진행 중에 이탈해도 서버는 계속 만든다(T28과 같다)

## 완료 조건
- [ ] seed 한 줄로 시작해 질문 → 답 → 미리보기 → 생성까지 끝난다
- [ ] 빈 답을 줘도 막히지 않고 AI가 채운다
- [ ] 라운드 상한에 닿으면 `done: true`가 된다
- [ ] 미리보기에서 인물을 지우면 그 인물이 만들어지지 않는다
- [ ] 만들어진 문서가 우리 파서와 호환된다(`CharacterDoc`·`ProtagonistDoc`·`KeywordBookParser`·`ImageCatalogParser`)
- [ ] 인물 문서에 `## 알고 있는 것`이, 주인공 문서에 `(비공개)` 섹션이 들어간다
- [ ] 중간 실패 시 시나리오가 남지 않는다(원자적 생성)
- [ ] 없는/만료된 `jobId`는 404, 이미 있는 이름은 confirm에서 400
- [ ] `./gradlew test` 전부 통과, 프론트 build·lint 통과
- [ ] Fake 프로바이더로 전 흐름 통합 테스트

## 착수 전에 정할 것
- **`scenario.imports` 패키지를 어떻게 가를 것인가.** 공용(`ImportDocs`, 파이프라인, SSE)과 입력원별(URL 추출 / 질의응답)로 나눈다. 패키지 이름을 바꾸면 T28 코드도 같이 움직이므로 **범위 밖 수정으로 기록**해야 한다
- 질문을 **세션 대화로 볼지 라운드로 볼지.** 라운드로 잡았다. 자유 대화는 언제 끝나는지 모호하고 상한을 걸기 어렵다

## 작업 로그
