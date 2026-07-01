# Crack Clone 개발기 (4) — AI가 긴 스토리를 기억하는 법: 계층적 요약과 상태 추적

> 수백 턴짜리 롤플레이에서 AI는 어떻게 "처음에 무슨 일이 있었는지"를 기억할까. 컨텍스트 윈도우의 한계를 계층적 요약으로 우회하고, 캐릭터 변화를 구조화해 추적한 이야기.

## 문제 — 컨텍스트는 유한한데 스토리는 길어진다

[1편](./01-architecture.md)에서 대화를 `chat_latest.md`에 쌓는다고 했다. 그런데 롤플레이는 수십, 수백 턴을 간다. 매 턴마다 전체 대화를 프롬프트에 넣으면:

- **컨텍스트 윈도우를 초과**한다
- 토큰이 늘수록 **느려지고 비싸진다**
- 오래된 디테일이 최근 대화에 묻혀 **AI가 초반 설정을 잊는다**

그렇다고 오래된 대화를 그냥 버리면, 50턴 전에 맺은 관계나 입은 부상을 AI가 까먹는다. **"무엇을 압축하고 무엇을 남길까"** 가 핵심 문제였다.

## 해결 1 — 10턴마다 자동 요약

가장 기본 전략. 10턴이 쌓이면 그 구간을 요약본으로 압축하고, 원본 대화는 아카이브로 치운 뒤 `chat_latest.md`를 비운다.

응답이 저장될 때마다 턴 수를 세고, 10의 배수면 요약을 트리거한다([2편](./02-claude-sse-streaming.md)에서 본 `onResponseComplete`에 붙어 있다).

```kotlin
fun shouldSummarize(turnCount: Int): Boolean =
    turnCount > 0 && turnCount % TURNS_PER_SUMMARY == 0   // TURNS_PER_SUMMARY = 10
```

요약 과정은 7단계다.

```kotlin
fun summarize(storyId: Long): SummarizeResult {
    val chatContent = chatFileService.readChatLatest(storyPath)  // 1. 현재 대화 읽기
    val summary = requestSummary(chatContent)                    // 2. Claude에게 요약 요청
    Files.writeString(memoryDir.resolve(summaryFileName), ...)   // 3. 요약 파일 저장
    storySummaryService.saveL1Summary(...)                       // 4. DB에 L1 저장(+다단계 트리거)
    updateCharacterDocuments(...)                                // 5. 캐릭터 문서 갱신
    chatFileService.archiveChat(storyPath, fromTurn, toTurn, chatContent)  // 6. 원본 아카이브
    chatFileService.resetChatLatest(storyPath)                   // 7. chat_latest 초기화
}
```

이후 새 대화를 조립할 땐, 비워진 `chat_latest.md`(최근 대화)에 **요약본을 컨텍스트로 얹는다**([1편의 `loadConversationContext`](./01-architecture.md)). 즉 프롬프트는 항상 "요약된 과거 + 생생한 최근 대화" 형태로 일정한 크기를 유지한다.

## 해결 2 — 계층적 요약 (L1 → L2 → L3)

여기서 한 발 더 나갔다. 100턴이 지나면 10개의 요약본이 쌓이는데, 이걸 다 넣으면 또 똑같은 문제가 반복된다. **요약본도 결국 길어진다.**

그래서 **요약을 다시 요약하는 트리(tree)** 를 만들었다.

```
L1 (10턴 단위)  ──┐
L1               ─┤→ 3개 모이면 → L2 (30턴 단위)  ──┐
L1               ─┘                                  ─┤→ 3개 모이면 → L3 (~100턴)
L1 L1 L1 → L2 ───────────────────────────────────── ─┤
L1 L1 L1 → L2 ──────────────────────────────────────┘
```

L1 요약이 3개 쌓이면 L2 하나로 통합하고, L2가 3개 쌓이면 L3로 통합한다.

```kotlin
fun tryConsolidate(storyId: Long, scenarioId: Long) {
    consolidateIfReady(storyId, scenarioId, L1, L2, L2_BATCH_SIZE)  // L1×3 → L2
    consolidateIfReady(storyId, scenarioId, L2, L3, L3_BATCH_SIZE)  // L2×3 → L3
}
```

핵심은 **"오래될수록 더 압축"** 이라는 원리다. 사람의 기억과 비슷하다. 어제 일은 상세히, 작년 일은 뭉뚱그려 기억한다.

그럼 프롬프트엔 어떤 레벨을 넣을까? **커버리지 기반으로 고른다.** 가장 압축된 L3를 먼저 깔고, L3가 안 덮은 구간만 L2로, 그것도 안 덮은 최근 구간만 L1로 채운다.

```kotlin
fun getEffectiveSummaries(storyId: Long): List<StorySummary> {
    val coveredTurns = mutableSetOf<Int>()
    val result = mutableListOf<StorySummary>()

    for (summary in l3) {                       // 1순위: 오래된 과거 = 가장 압축
        result.add(summary)
        (summary.fromTurn..summary.toTurn).forEach { coveredTurns.add(it) }
    }
    for (summary in l2) {                        // 2순위: 안 덮인 구간만
        if ((summary.fromTurn..summary.toTurn).none { it in coveredTurns }) {
            result.add(summary); /* mark covered */
        }
    }
    for (summary in l1) { /* 3순위: 최근 = 가장 상세 */ }
    return result.sortedBy { it.fromTurn }
}
```

결과적으로 프롬프트에 들어가는 기억은 **「먼 과거는 한 문단(L3) → 중간은 좀 더(L2) → 최근은 상세히(L1) → 지금은 원문(chat_latest)」** 의 점진적 해상도 구조가 된다.

## 해결 3 — 모델 티어로 비용 나누기

요약은 자주 돌고, 본 출력만큼 고품질이 필요하진 않다. 그래서 작업마다 모델 등급을 달리 했다.

```kotlin
enum class ModelTier {
    OPUS,    // 주요 출력용 (고품질 소설 생성)
    SONNET,  // 유틸리티용 (요약, 캐릭터 업데이트)
    HAIKU    // 경량용 (컨텍스트 판별, 분류)
}
```

본 스토리 생성은 `OPUS`([2편](./02-claude-sse-streaming.md)), 요약·통합은 `SONNET`으로 돌린다. "비싼 모델은 사용자가 읽는 결과물에만, 내부 처리는 싼 모델로"라는 분담이다.

```kotlin
fun requestConsolidation(combinedContent: String): String =
    claudeService.chat(AiRequest(
        systemPrompt = CONSOLIDATION_PROMPT,
        messages = listOf(ChatMessage(MessageRole.USER, combinedContent)),
        modelTier = ModelTier.SONNET          // 요약은 SONNET이면 충분
    ))
```

## 해결 4 — 캐릭터를 "문서"와 "상태" 두 갈래로 추적

요약이 "사건의 흐름"을 기억한다면, **캐릭터의 변화**는 따로 관리해야 했다. 두 방식을 병행했다.

### (a) 캐릭터 문서 자동 갱신 — Copy-on-Write

요약할 때마다 AI가 캐릭터 문서의 '주요 사건 기록' 섹션에 변화를 덧붙인다. 중요한 건 **원본을 건드리지 않는다**는 것. [1편의 시나리오/스토리 분리](./01-architecture.md#설계-고민-1--시나리오와-스토리를-어떻게-나눌까)가 여기서 그대로 작동한다 — 갱신본은 **스토리 오버라이드 폴더**에 저장한다 (Copy-on-Write).

```kotlin
// 스토리 오버라이드 우선, 없으면 시나리오 원본을 읽어서
val existingDoc = if (Files.exists(storyCharFile)) Files.readString(storyCharFile)
                  else Files.readString(baseCharFile)
val updatedDoc = requestCharacterUpdate(existingDoc, summary, fromTurn, toTurn)
Files.writeString(storyCharFile, updatedDoc)   // ← 항상 스토리 쪽에 저장
```

덕분에 같은 시나리오의 다른 스토리(분기)는 서로의 캐릭터 변화에 영향받지 않는다. 한 스토리에서 죽은 캐릭터가 다른 스토리에선 멀쩡하다.

### (b) 구조화된 상태 — CharacterState / CharacterEvent

자유 텍스트 문서만으로는 "정확한 사실"을 다루기 어렵다. 부상 여부, 소지품, 관계 수치 같은 건 **구조화**가 필요했다. 그래서 DB에 상태와 이벤트를 따로 뒀다.

- **`CharacterState`** — 현재 상태. `(stateType, stateKey, stateValue)` 형태에 `isActive` 플래그. 같은 키가 들어오면 덮어쓰고, 끝난 상태는 비활성화한다.
- **`CharacterEvent`** — 사건 로그. 턴 번호와 함께 타입별로 쌓이는 불변 기록.

```kotlin
// 같은 (캐릭터, 타입, 키)면 갱신, 아니면 새로 생성 — upsert
val existing = characterStateRepository
    .findByStoryIdAndCharacterNameAndStateTypeAndStateKey(storyId, name, type, key)
if (existing != null) {
    existing.stateValue = request.stateValue
    existing.isActive = true
} else {
    characterStateRepository.save(CharacterState(/* ... */))
}
```

문서(자유서술)와 상태(구조화)를 나눈 건, **"AI가 읽기 좋은 형태"와 "코드가 다루기 좋은 형태"가 다르기 때문**이다. 묘사는 문서로, 사실은 테이블로.

## 회고 — 지금이라면 다시 고민할 것

이 기억 시스템은 이 프로젝트에서 가장 공들인 부분이지만, 그만큼 회의도 든다.

- **요약은 손실 압축이다.** 10턴을 3~5문단으로 줄이면, 나중에 중요해질 사소한 디테일이 날아간다. "그때 흘리듯 말한 한마디"가 50턴 뒤 복선이 되는 게 롤플레이인데, 요약이 그걸 지워버릴 수 있다. **RAG(원본 아카이브에서 관련 대목을 검색해 끌어오기)** 를 얹으면 손실 압축과 정밀 회상을 둘 다 가질 수 있었을 것이다. 아카이브는 이미 파일로 남겨뒀으니([6단계](#해결-1--10턴마다-자동-요약)) 토대는 있다.
- **요약이 동기적으로 끼어든다.** 10턴째 응답이 끝난 뒤 요약이 돌면 사용자가 그만큼 기다린다. 백그라운드 작업으로 빼야 했다.
- **캐릭터 상태(b)를 채우는 주체가 모호하다.** `StateService`는 API로 열려 있지만, 정작 대화에서 상태 변화를 **자동 추출**하는 파이프라인이 약하다. 이상적으로는 매 턴 AI가 function calling으로 "위지연이 부상당함" 같은 상태 변경을 구조화해 뱉고, 그걸 그대로 저장해야 한다. 지금은 문서 갱신(a)에 더 의존한다.
- **고정 임계값(10턴, 3배치).** 대화 밀도와 무관하게 턴 수로만 자른다. 한 턴이 짧을 수도 길 수도 있는데, **토큰 양 기준**으로 자르는 게 더 합리적이었을 것이다.

그럼에도 핵심 아이디어 — **"오래될수록 압축하고, 묘사는 문서로 사실은 테이블로 나눈다"** — 는 AI 장기기억 문제에 꽤 잘 맞는 접근이었다고 본다.

## 시리즈를 마치며

4편에 걸쳐, AI 캐릭터 챗 앱 하나를 만들며 한 고민을 정리했다.

1. [무엇을 어디에 둘 것인가](./01-architecture.md) — 시나리오/스토리 분리, 파일 vs DB
2. [AI 응답을 실시간으로 흘려보내기](./02-claude-sse-streaming.md) — CLI 프로세스 + SSE
3. [사람이 만지는 부분](./03-chat-ux-prompt.md) — UX와 프롬프트 엔지니어링
4. AI에게 장기기억 주기 — 계층적 요약과 상태 추적 (이 글)

관통하는 교훈이 있다면, **AI 앱은 "모델을 부르는 것"이 아니라 "모델 주변을 설계하는 것"** 이라는 점이다. 컨텍스트를 어떻게 조립하고, 출력을 어떻게 다듬고, 기억을 어떻게 압축하느냐 — 정작 어려운 건 모델 바깥에 있었다.

⬅️ [3편 — 채팅 UX와 프롬프트 엔지니어링](./03-chat-ux-prompt.md)
