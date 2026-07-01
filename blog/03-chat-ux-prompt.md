# Crack Clone 개발기 (3) — 채팅 UX와 프롬프트 엔지니어링

> 마크다운 렌더링, 메시지별 액션(재생성·이어하기·분기·수정), 그리고 "감정 태그가 화면에 노출되고 응답이 한 줄로 나오는" 출력 품질 문제를 잡은 과정.

## 출력 렌더링 — 직접 포매팅에서 react-markdown으로

처음엔 `*기울임*`, `"대사"` 같은 걸 직접 정규식으로 파싱해 `<span>`으로 감싸는 함수를 만들었다. 금방 한계가 왔다. 제목, 인용, 목록, 표, 코드 블록… AI 출력은 생각보다 다양한 마크다운을 뱉었고, 직접 만든 포매터로는 감당이 안 됐다.

그래서 **react-markdown**으로 갈아탔다. `remark-gfm`(GitHub Flavored Markdown)과 `remark-breaks`(단일 줄바꿈을 `<br>`로)를 함께 물렸다.

```tsx
<ReactMarkdown
  remarkPlugins={[remarkGfm, remarkBreaks]}
  components={{
    img: ({ src, alt }) => (
      <img src={src} alt={alt || ''} loading="lazy"
           className="max-w-full rounded-xl my-2 border border-border/30" />
    ),
    a: ({ href, children }) => (
      <a href={href} target="_blank" rel="noopener noreferrer"
         className="text-accent underline">{children}</a>
    ),
  }}
>
  {displayContent}
</ReactMarkdown>
```

`img` 컴포넌트를 커스터마이징한 건 의도적이다. 앞으로 채팅 안에 **외부 이미지 URL**을 띄울 계획이라, 마크다운 이미지 문법(`![](url)`)이 그대로 렌더되도록 미리 길을 터놨다.

> 참고: React 19와 react-markdown의 peer dependency가 충돌해서 설치 때 `--legacy-peer-deps`가 필요했다. 그리고 `npx tsc`는 엉뚱한 패키지(tsc@2)를 깔아버리니, 타입 체크는 `./node_modules/.bin/tsc --noEmit`로 돌려야 한다. 사소하지만 시간을 꽤 잡아먹은 함정.

## 메시지별 액션 — 재생성, 이어하기, 분기, 수정

상용 앱처럼 각 AI 메시지에 액션을 붙였다. 그런데 사용자 피드백에서 중요한 구분이 나왔다.

> "재생성 버튼을 눌렀더니 기존 대화에 **이어서** 한 번 더 출력하고 있어. 그건 '이어하기'고, '재생성'은 기존 출력을 **삭제하고 덮어쓰는** 기능이야."

같아 보이지만 정반대다. 둘을 명확히 분리했다.

### 재생성 (Regenerate) — 마지막 응답을 버리고 다시

마지막 AI 메시지를 **지우고**, 같은 사용자 입력으로 다시 생성한다.

```kotlin
@Transactional
fun regenerate(storyId: Long, request: ChatRequest): SseEmitter {
    val story = findStory(storyId)
    chatFileService.removeLastAssistantMessage(Path.of(story.dataPath))  // ← 마지막 응답 제거
    story.turnCount = maxOf(0, story.turnCount - 1)
    storyRepository.save(story)
    // 남아있는 마지막 사용자 메시지로 다시 스트리밍
    // ...
}
```

### 이어하기 (Continue) — 맥락 유지하고 한 번 더

기존 응답은 **그대로 두고**, AI가 이어서 더 쓰게 한다. "계속 이어서 작성해주세요"라는 유도 메시지를 넣고 다시 호출한다.

```kotlin
fun continueChat(storyId: Long, request: ChatRequest): SseEmitter {
    val story = findStory(storyId)
    // 기존 응답은 유지, 이어쓰기 유도 메시지만 추가
    chatFileService.appendUserMessage(Path.of(story.dataPath), "계속 이어서 작성해주세요.")
    // 전체 맥락 그대로 다시 스트리밍
    // ...
}
```

프론트에선 마지막 AI 메시지 아래에만 두 버튼을 나란히 보여준다. 재생성은 UI에서 메시지를 빼고 새 응답을 받고, 이어하기는 유도 메시지 + 새 응답을 둘 다 덧붙인다.

### 분기 (Branch) — 특정 지점에서 평행우주 만들기

[1편](./01-architecture.md)의 시나리오/스토리 분리 설계가 여기서 빛을 본다. 특정 메시지 시점까지의 대화를 복사해 **새 스토리**로 만든다. 원래 스토리는 그대로 두고, 그 지점부터 다른 선택을 할 수 있다.

### 수정 (Edit) — AI 응답 직접 손보기

AI 응답이 마음에 안 들면 인라인 텍스트영역에서 직접 고친다. 파일을 파싱→해당 인덱스 교체→다시 쓰기.

```kotlin
fun editMessage(storyPath: Path, messageIndex: Int, newContent: String): List<ChatMessage> {
    val messages = parseMessages(storyPath).toMutableList()
    messages[messageIndex] = messages[messageIndex].copy(content = newContent)
    writeMessages(storyPath, messages)
    return messages
}
```

## 출력 품질 — 프롬프트 엔지니어링으로 잡은 3가지 문제

실제 테스트에서 사용자가 정확히 세 가지를 지적했다.

> "출력 양식을 틀리는 것 같아. ① 최소 글자수를 안 지키고, ② 감정 태그까지 화면에 나오고, ③ 줄바꿈이 아예 없이 출력돼서 가독성이 최악이야."

### ① 너무 짧은 응답 → 시스템 프롬프트에 최소 분량 명시

`PromptAssembler`의 기본 규칙에 분량과 문체 규칙을 못 박았다.

```kotlin
private const val BASE_RULE = """당신은 몰입형 소설/롤플레이 AI 작가입니다. ...

## 핵심 규칙
4. 응답은 최소 200자 이상, 풍부한 묘사와 감정 표현을 포함하세요.
5. 장면 전환, 시간 경과, 분위기 묘사를 세밀하게 작성하세요.

## 문체 규칙
- 행동/상황 묘사: *기울임*으로 감싸세요. ...
- 대사: "큰따옴표"로 감싸세요. ...
- 문단을 적절히 나누어 가독성을 높이세요. 묘사와 대사 사이에 빈 줄을 넣으세요."""
```

추상적인 "길게 써줘"가 아니라 **"최소 200자", "문단 사이 빈 줄"** 처럼 검증 가능한 지시를 넣고, 출력 형식에 **여러 문단으로 나뉜 실제 예시**를 통째로 박아 넣었다. 예시가 곧 스펙이다.

### ② 감정 태그가 화면에 노출 → 렌더 단계에서 완전 제거

AI는 응답 첫 줄에 `[감정: 경계, 차분함]` 형식의 태그를 단다(내부적으로 캐릭터 상태 추적에 쓰려던 것). 그런데 이게 사용자 화면에 그대로 보였다. 사용자는 "이거 보여주지 말라"고 명확히 요구했다.

렌더 직전에 정규식으로 싹 제거한다.

```tsx
// 감정 태그는 화면에 절대 표시하지 않음 — 본문만 렌더
let displayContent = content.replace(/\[감정:\s*[^\]]+\]\s*/g, '');

// 스트리밍 중엔 아직 안 닫힌 부분 태그('[감정:'...)도 숨김
if (isStreaming) {
  displayContent = displayContent.replace(/^\[감정:[^\]]*$/, '');
}
```

스트리밍 처리가 포인트다. 글자가 하나씩 도착하므로 `[감`, `[감정:` 같은 **미완성 태그**가 잠깐 깜빡일 수 있다. 그래서 닫히지 않은 선행 태그도 함께 숨겼다.

### ③ 줄바꿈이 사라짐 → SSE 파서 버그였다

이건 프롬프트 문제가 아니라 [2편](./02-claude-sse-streaming.md)에서 다룬 **SSE 줄바꿈 분할 버그**가 원인이었다. SSE가 줄바꿈마다 `data:`를 쪼개는데, 프론트에서 그걸 그냥 이어 붙여 모든 줄바꿈이 증발했던 것. 파서에서 줄바꿈을 복원하자 한 방에 해결됐다.

`②`가 프롬프트로 못 잡는 문제(AI는 의도대로 태그를 다는 게 맞음)였고, `③`은 애초에 백엔드 전송 버그였다는 점이 흥미롭다. **"출력이 이상하다"는 한 가지 증상도, 원인은 프롬프트·렌더·전송 세 층위에 흩어져 있었다.**

## 회고 — 지금이라면 다시 고민할 것

세 가지 출력 문제를 잡긴 했지만, **해결 방식 자체가 임시방편**인 곳이 있다.

- **감정 태그를 AI 본문에 섞고 클라이언트에서 정규식으로 떼는 건 해킹에 가깝다.** AI가 `[감정: ...]`을 프로즈 안에 넣으면, 스트리밍 중 미완성 태그가 깜빡이고, 정규식이 본문 속 대괄호와 충돌할 위험도 있다. 더 깔끔한 길은 **구조화된 출력**(별도 필드)이나 **function calling**으로 감정을 본문과 분리해서 받는 것. 그러면 떼낼 일 자체가 없다.
- **"계속 이어서 작성해주세요"를 가짜 사용자 메시지로 끼워 넣는** 이어하기/재생성 방식도 대화 기록을 오염시킨다. 합성 턴이 `chat_latest.md`에 남아 다음 요약에 섞인다. 메타 신호로 처리하는 게 더 맞다.
- **이미지 URL 렌더링은 보안 점검이 필요하다.** 외부 이미지 URL을 띄우려고 `img`를 열어뒀는데([상단 참고](#출력-렌더링--직접-포매팅에서-react-markdown으로)), AI가 만든 마크다운을 그대로 렌더하면 추적 픽셀·SSRF 같은 우려가 있다. `rel="noopener"`는 걸어뒀지만, 이미지 소스 화이트리스트나 마크다운 새니타이즈를 붙여야 안전하다.
- **프롬프트 튜닝이 두더지 잡기였다.** "최소 200자", "문단 나눔" 같은 규칙을 넣고 직접 써보며 고쳤는데, **평가셋(eval)이 없어서** 매번 감으로 판단했다. 작은 평가 하네스가 있었다면 출력 품질 개선이 훨씬 덜 주먹구구였을 것이다.

이건 실패담이라기보다, **"동작하게 만든 다음에 보이는 것들"** 이다. 일단 돌아가게 한 뒤에야 더 나은 구조가 눈에 들어왔다.

## 정리 — 사용자 피드백이 곧 스펙

3편을 관통하는 건, 거의 모든 개선이 **실사용 피드백에서 나왔다**는 점이다.

- "재생성과 이어하기는 다른 기능이다" → 두 엔드포인트로 분리
- "감정 태그 보여주지 마라" → 렌더 단계 제거 + 스트리밍 부분 태그 처리
- "최소 글자수, 줄바꿈" → 프롬프트 규칙 강화 + SSE 파서 수정

AI 앱은 특히 그렇다. 모델 출력은 결정적이지 않아서, **무엇이 문제인지조차 직접 써봐야 드러난다.** 프롬프트로 잡을 문제와 코드로 잡을 문제를 가르는 안목이, 결국 이 프로젝트에서 가장 많이 키운 근육이었다.

다음 편은 지금까지 미뤄둔 가장 어려운 문제 — **AI가 수백 턴짜리 긴 스토리를 어떻게 "기억"하는가.** 계층적 자동 요약과 캐릭터 상태 추적 이야기.

⬅️ [2편 — Claude를 AI 엔진으로: CLI 프로바이더와 SSE 스트리밍](./02-claude-sse-streaming.md)
➡️ [4편 — AI가 긴 스토리를 기억하는 법: 계층적 요약과 상태 추적](./04-memory-and-state.md)
