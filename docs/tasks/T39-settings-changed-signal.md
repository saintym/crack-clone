# T39 설정 변경 신호 — 문서를 고치면 AI가 알아차리게

- **상태**: DONE
- **웨이브**: 8
- **의존**: T15(문서 편집 UI), T35
- **브랜치**: `task/T39-settings-changed-signal`
- **마이그레이션**: 없음 (`state.json` 필드 추가)
- **결정**: D41

## 목표
사용자가 주인공·인물 문서 등을 직접 고쳤을 때, **AI가 다음 응답에서 그것을 알아차리고 새 설정을 따르게** 한다.

## 이미 있던 것 / 없던 것
- **편집은 이미 됐다.** 기억 패널 → 문서 탭에서 연대기·주인공·인물·유저노트·키워드북·명령·설정을 열어 고칠 수 있다(`PUT /api/stories/{id}/documents/content`, T08·T15). 스토리 폴더의 복사본을 고치므로 다른 스토리에 영향이 없다(D12)
- **없던 것은 "방금 바뀌었다"는 신호다.** 프롬프트는 매 턴 문서에서 새로 만들어지므로 고친 내용은 다음 턴에 이미 들어간다. **그런데 AI가 그 사실을 모른다.** 최근 대화 원문에 옛 말투·옛 설정이 잔뜩 남아 있으면 그쪽에 끌려간다

## 범위
- `memory/docs/StoryState.kt`(`changedDocs` 필드)
- 신규: `document/story/SettingsChangedMarker.kt`(마커 + AfterTurnHook), `prompt/contributor/SettingsChangedContributor.kt`
- `document/story/StoryDocumentService.kt`(저장 시 마킹)
- `crack-frontend/src/components/panels/memory/DocumentEditor.tsx`(저장 후 안내)
- 신규 테스트: `SettingsChangedContributorTest.kt`
- `docs/DESIGN.md`(§6.4-1), `Plan-roadmap.md`(D41)

## 구현 내용
DESIGN.md §6.4-1이 기준이다.

1. **켜기** — `StoryDocumentService.write`가 저장 직후 `SettingsChangedMarker.mark(storyId, path)`를 부른다. `state.json.changedDocs`에 경로가 쌓인다(중복 제거, 최대 32개)
2. **넣기** — `SettingsChangedContributor`(BOTTOM, order 50)가 목록이 있으면 `[설정 변경]` 블록을 넣는다
3. **끄기** — `SettingsChangedHook`(AfterTurnHook, `@Order(200)`)이 응답 저장 후 비운다
4. **화면** — 저장하면 편집기에 한 줄 안내가 뜬다("다음 응답부터 새 설정을 따릅니다"). 채팅 흐름은 건드리지 않는다(D7)

## 완료 조건
- [x] 문서를 저장하면 `state.json.changedDocs`에 경로가 들어간다
- [x] 프롬프트 BOTTOM에 `[설정 변경]` 신호가 들어간다
- [x] 응답이 저장되면 목록이 비워진다
- [x] 목록이 비어 있으면 아무것도 넣지 않는다
- [x] `state.json`이 없거나 깨져도 터지지 않는다
- [x] 경로가 8개를 넘으면 개수로 줄인다
- [x] 백엔드 테스트 568개 통과, 프론트 build·lint 통과

## 작업 로그

### 2026-09-28
사용자 요청: "사용자가 주인공 문서나 캐릭터 문서를 직접 수정할 수 있으면 좋겠어. 수정이 되면 '설정수정됨' 스위치를 on으로 하고, AI는 스위치가 on이면 각 정보를 갱신한 후 출력하고 스위치를 off로 하는 것."

**편집 기능은 이미 있어서 신호만 만들었다.** 확인해 보니 T08(문서 API)과 T15(기억 패널)에서 주인공·인물 문서 편집이 이미 된다. 새로 만들 것은 마커뿐이었다.

**스위치를 AI가 끄게 하지 않았다.** 사용자 제안은 "AI가 off로 한다"였지만, 그러면 첫 줄 태그를 하나 더 늘려야 하고(`[설정: 반영됨]` 같은 것) AI가 빼먹으면 신호가 계속 남는다. **서버가 턴 종료 훅에서 끄는 쪽이 확실하고 태그도 안 늘어난다.** 결과는 같다 — 신호는 다음 응답 한 번에만 쓰인다.

**BOTTOM 슬롯에 뒀다.** 시스템 프롬프트 쪽(BASE~IMAGES)에 넣으면 캐시 접두사가 매번 깨진다. BOTTOM은 이미 지시가 들어가는 자리이고 캐시 밖이다.

**"이야기 안에서 언급하지 않는다"를 문구에 넣었다.** 이게 없으면 인물이 "내가 어제와 다르게 느껴진다" 같은 메타 발언을 할 위험이 있다. 그리고 **"지난 일을 없던 일로 만들지 않는다"**도 넣었다 — 설정이 바뀌었다고 과거 장면을 부정하면 이야기가 무너진다.

**기록 파이프라인이 쓴 문서는 담기지 않는다.** 파이프라인은 `MemoryDocs`로 직접 쓰고 이 API를 지나지 않는다. 의도한 대로다 — AI가 스스로 쓴 것에 "설정이 바뀌었다"를 알릴 이유가 없다.

**확인:** 백엔드 568개 통과(신규 6개). 실제 서버로 전 과정을 확인했다.
```
1. 인물 문서 PUT            → 200
2. state.json               → "changedDocs": [ "characters/린.md" ]
3. prompt-preview BOTTOM    → [settings_changed] 266자, "[설정 변경] …" 포함
4. 한 턴 진행 후 state.json → "changedDocs": [ ]
```

**다음 작업자에게:**
- **앱 밖에서 파일을 직접 고친 것은 감지하지 못한다.** 파일 감시를 붙이지 않았다. 에디터로 고쳤으면 아무 문서나 한 번 저장해 신호를 켜거나, 그냥 다음 턴에 문서가 반영되는 것으로 충분한 경우가 많다
- 재생성으로는 신호가 다시 켜지지 않는다. 첫 응답이 이미 소비하고 껐기 때문이다. 필요하면 `SettingsChangedHook`을 `mode == SEND`일 때만 끄게 바꾼다
