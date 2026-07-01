# Crack Clone 개발기 (2) — Claude를 AI 엔진으로: CLI 프로바이더와 SSE 스트리밍

> AI 응답을 한 글자씩 실시간으로 흘려보내는 스트리밍을 구현하며 만난 버그들. 프로세스 파이프, SSE 프로토콜, 그리고 "줄바꿈이 통째로 사라지는" 미스터리.

## 왜 Claude Code CLI를 프로세스로 띄웠나

AI 호출 비용을 0으로 만들고 싶었다. Anthropic API는 토큰당 과금이지만, **Claude Code CLI는 구독(Max) 안에서 동작**한다. 그래서 API 대신 `claude` CLI를 자식 프로세스로 띄워, 표준 출력을 읽어 응답을 받는 방식을 택했다.

```kotlin
private fun startProcess(prompt: String, outputFormat: String, verbose: Boolean = false): Process {
    val command = mutableListOf(
        resolveCliPath(), "-p", prompt,
        "--output-format", outputFormat,
        "--tools", "",                  // ← 도구 로딩 차단 (뒤에서 설명)
        "--no-session-persistence"      // ← 세션 저장 끄기
    )
    if (verbose) command.add("--verbose")
    return ProcessBuilder(command).redirectErrorStream(false).start()
}
```

`-p`는 프롬프트를 넘기는 플래그, `--output-format`은 출력 형식이다. 동기 호출엔 `json`, 스트리밍엔 `stream-json`을 쓴다.

### 삽질 1 — CLI가 26k 토큰짜리 "잡동사니"를 끌고 온다

처음 응답 품질이 이상했다. 롤플레이 프롬프트를 줬는데 자꾸 일반 비서처럼 답했다. 원인은 CLI가 기본적으로 **MCP 서버, 도구 정의, 세션 컨텍스트**를 잔뜩 로딩하고 있던 것. 정작 중요한 롤플레이 지시가 그 잡동사니에 묻혀 희석됐다.

`--tools ""`로 도구 로딩을 끄고, `--no-session-persistence`로 세션 저장을 끄자 프롬프트가 깔끔하게 전달되며 응답 품질이 살아났다. AI를 "엔진"으로만 쓸 거면, CLI의 부가 기능은 오히려 방해가 된다.

## 동기 호출 — 일단 동작부터

스트리밍 전에 동기 버전부터 만들었다. 프로세스를 띄우고, 출력을 통째로 읽고, JSON에서 `result` 필드를 꺼낸다.

```kotlin
override fun chat(request: AiRequest): String {
    val process = startProcess(buildPrompt(request), outputFormat = "json")
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) throw RuntimeException("Claude CLI failed: ...")

    val node = objectMapper.readTree(output)
    return node.get("result")?.asText() ?: output.trim()
}
```

문제는, 응답이 길면 사용자가 **수십 초를 빈 화면 보며 기다린다**는 것. 챗 앱에서 이건 치명적이다. 그래서 스트리밍이 필요했다.

## 스트리밍 — 프로세스 출력을 SSE로 중계

구조는 이렇다.

```
Claude CLI (stream-json, 줄 단위 JSON)
   │  stdout 한 줄씩
   ▼
ClaudeCodeCliProvider (JSON 파싱 → 텍스트 추출)
   │  SSE event:delta
   ▼
브라우저 (fetch + ReadableStream 파싱 → 화면에 누적)
```

`stream-json` 포맷은 출력을 **JSON Lines**로 흘린다. 한 줄에 JSON 하나씩, 이벤트 종류별로 타입이 다르다. 이걸 줄 단위로 읽으며 텍스트만 뽑아 SSE로 다시 흘려보낸다.

```kotlin
private fun processStreamOutput(reader: BufferedReader, fullResponse: StringBuilder, emitter: SseEmitter) {
    reader.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val node = objectMapper.readTree(line)
        when (node.get("type")?.asText()) {
            "assistant" -> {                              // content 블록 배열
                node.get("message")?.get("content")?.forEach { block ->
                    if (block.get("type")?.asText() == "text") {
                        val text = block.get("text")?.asText() ?: return@forEach
                        fullResponse.append(text)
                        emitter.send(SseEmitter.event().name("delta").data(text))
                    }
                }
            }
            "content_block_delta" -> {                    // 증분 델타
                val text = node.get("delta")?.get("text")?.asText() ?: return@forEachLine
                fullResponse.append(text)
                emitter.send(SseEmitter.event().name("delta").data(text))
            }
            "result" -> {                                 // 델타가 비었을 때의 fallback
                val result = node.get("result")?.asText()
                if (result != null && fullResponse.isEmpty()) {
                    fullResponse.append(result)
                    emitter.send(SseEmitter.event().name("delta").data(result))
                }
            }
        }
    }
}
```

CLI 버전·모드에 따라 텍스트가 `assistant`의 content 블록으로 오기도, `content_block_delta`로 오기도 한다. 그래서 둘 다 처리하고, 그마저도 비면 `result`를 fallback으로 쓴다. 끝나면 `done` 이벤트로 전체 응답을 한 번 더 보낸다.

```kotlin
emitter.send(SseEmitter.event().name("done").data(result))
emitter.complete()
```

### 삽질 2 — 빈 응답이 그대로 저장된다

가끔 AI가 완전히 빈 응답을 뱉었다. 그런데 그게 `chat_latest.md`에 `## ASSISTANT`만 덩그러니 저장됐다. 다음 턴에 이 빈 메시지가 컨텍스트로 들어가며 대화가 더 이상해졌다.

방어를 백엔드·프론트 양쪽에 걸었다.

```kotlin
// 백엔드 — 빈 응답은 저장하지 않음
fun onResponseComplete(storyId: Long, fullResponse: String) {
    if (fullResponse.isBlank()) {
        log.warn("빈 AI 응답 — 저장하지 않음: storyId=$storyId")
        return
    }
    // ...
}
```

```typescript
// 프론트 — 빈 응답이면 메시지 추가도, complete 호출도 안 함
if (fullResponse.trim()) {
  setMessages(prev => [...prev, { role: 'assistant', content: fullResponse }]);
  await chatApi.complete(storyId, fullResponse);
}
```

## 프론트의 SSE 파서 — 그리고 가장 골치 아팠던 버그

브라우저에선 `EventSource` 대신 `fetch` + `ReadableStream`을 썼다. POST로 body를 실어 보내야 했기 때문이다(`EventSource`는 GET만 됨). 응답 스트림을 직접 파싱한다.

```typescript
const reader = response.body?.getReader();
const decoder = new TextDecoder();
let fullResponse = '';
let buffer = '';

while (reader) {
  const { done, value } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });
  const lines = buffer.split('\n');
  buffer = lines.pop() || '';   // 마지막 불완전한 줄은 다음 청크로 넘김
  // ... 줄별로 event:/data: 파싱
}
```

여기서 두 개의 버그를 잡았다.

### 삽질 3 — `done` 데이터가 누적된 응답을 덮어쓴다

처음엔 `done` 이벤트의 데이터로 `fullResponse`를 통째로 교체했다. 그런데 SSE에서 데이터가 여러 `data:` 줄로 쪼개져 오면, 마지막 조각만 남고 앞이 날아갔다. 그래서 **델타는 델타대로 누적**하고, `done`은 별도 변수에 모아 **델타가 비었을 때만 fallback**으로 쓰게 했다.

```typescript
} else if (currentEvent === 'done') {
  doneData += (doneData ? '\n' : '') + data;
}
// ...
return fullResponse || doneData;   // 델타 우선, 없으면 done
```

### 삽질 4 — "줄바꿈이 통째로 사라지는" 미스터리

가장 오래 헤맨 버그. AI는 분명 문단을 나눠 응답하는데, 화면엔 **모든 게 한 줄로** 붙어 나왔다. 가독성이 최악이었다.

원인은 SSE 프로토콜 자체에 있었다. **SSE는 데이터에 줄바꿈이 있으면, 그 줄바꿈마다 별도의 `data:` 줄로 쪼갠다.** 즉 `"문단1\n문단2"`는 전송 시:

```
event:delta
data:문단1
data:문단2

```

이렇게 두 개의 `data:` 줄이 된다. 그런데 프론트에서 이 둘을 그냥 이어 붙이면 `문단1문단2` — 줄바꿈이 증발한다.

해결: **같은 이벤트 안에서 `data:` 줄이 둘 이상이면, 그 사이에 줄바꿈을 복원**한다.

```typescript
let deltaLineCount = 0;
for (const line of lines) {
  if (line.startsWith('event:')) {
    currentEvent = line.slice(6).trim();
    deltaLineCount = 0;
  } else if (line.startsWith('data:')) {
    const data = line.slice(5);
    if (currentEvent === 'delta') {
      if (deltaLineCount > 0) fullResponse += '\n';   // ← 핵심: 줄바꿈 복원
      fullResponse += data;
      deltaLineCount++;
      setStreamContent(fullResponse);
    }
  } else if (line.trim() === '') {
    currentEvent = '';        // 빈 줄 = SSE 이벤트 경계
    deltaLineCount = 0;
  }
}
```

추가로 한 가지 함정이 더 있었다. 처음엔 `data:` 줄을 만날 때마다 `currentEvent`를 리셋했는데, 이러면 한 이벤트가 여러 `data:` 줄을 가질 때 두 번째 줄부터 이벤트 종류를 잃어버린다. **이벤트 경계는 `data:`가 아니라 "빈 줄"** 이라는 SSE 규칙을 정확히 지켜야 했다.

## 자원 정리 — 좀비 프로세스 막기

스트리밍은 별도 스레드 풀에서 돌리고, 타임아웃을 걸어 행이 걸린 프로세스를 강제 종료한다.

```kotlin
private val executor = Executors.newCachedThreadPool()
// ...
val completed = process.waitFor(120, TimeUnit.SECONDS)
if (!completed) {
    process.destroyForcibly()
    log.error("Claude Code CLI stream timed out")
}
```

## 회고 — 지금이라면 다시 고민할 것

비용 0이라는 이점은 분명했지만, **CLI를 프로세스로 띄우는 방식엔 숨은 비용**이 있었다.

- **요청마다 프로세스를 새로 띄운다.** 프로세스 시작 지연이 매번 붙고, 커넥션 재사용도 안 된다. Anthropic API의 네이티브 SSE 스트리밍이라면 이 오버헤드가 없다.
- **stream-json 파싱이 깨지기 쉽다.** 텍스트가 `assistant` / `content_block_delta` / `result` 세 군데로 흩어져 와서 전부 방어해야 했다. CLI가 출력 포맷을 바꾸면 또 깨진다 — 비공식 인터페이스에 의존하는 셈.
- **로컬 + 구독에 묶인다.** 이 방식은 내 컴퓨터의 `claude` CLI와 Max 구독에 종속된다. 서버에 올려 여러 명이 쓰게 만들 수가 없다.
- **`SseEmitter` + 수동 스레드풀**도 거칠었다. 코루틴이나 WebFlux였다면 비동기 스트리밍이 훨씬 깔끔했을 것이다([1편의 백엔드 회고](./01-architecture.md#기술-선택-이유-그리고-지금이라면-다시-고민할-것)와 연결되는 지점).

정직한 결론: **개인용·비용 0이라는 제약에선 옳은 선택**이었다. 하지만 배포하거나 멀티유저로 갈 거라면, 토큰 비용을 내더라도 **공식 API의 네이티브 스트리밍**으로 가는 게 맞다. 그래서 처음부터 `AiProvider` 인터페이스로 추상화해 둔 것이고([1편](./01-architecture.md)), 실제로 `ClaudeApiProvider`도 함께 구현해 뒀다 — 언제든 갈아끼울 수 있도록.

## 정리

스트리밍은 "텍스트를 흘려보낸다"는 개념은 단순하지만, 실제론 **세 군데의 경계**(CLI 출력 포맷 → SSE 프로토콜 → 브라우저 파서)를 정확히 다뤄야 했다.

- CLI는 도구 로딩을 끄지 않으면 프롬프트가 희석된다
- 텍스트가 오는 JSON 타입이 여러 개라 전부 처리 + fallback 필요
- **SSE는 줄바꿈을 `data:` 줄로 쪼갠다** — 받는 쪽에서 복원해야 한다
- 빈 응답·덮어쓰기 같은 엣지 케이스는 양쪽에서 방어

다음 편은 사용자가 직접 만지는 부분 — **채팅 UX(재생성/이어하기/분기)와 출력 품질을 끌어올린 프롬프트 엔지니어링** 이야기.

⬅️ [1편 — 프로젝트 개요 & 아키텍처](./01-architecture.md)
➡️ [3편 — 채팅 UX와 프롬프트 엔지니어링](./03-chat-ux-prompt.md)
