# Crack Clone 개발기 (1) — AI 캐릭터 챗 앱을 직접 만들며 한 설계 고민들

> AI 캐릭터와 대화하는 롤플레이 앱 "Crack"을 클론하면서 겪은 설계·기술·삽질의 기록. 1편은 전체 구조와 데이터 모델 설계 이야기.

## 무엇을 만들었나

상용 AI 캐릭터 챗 앱 **Crack**을 참고해, 개인용 AI 롤플레이/소설 챗 앱을 직접 구현했다. 사용자가 캐릭터·세계관이 설정된 시나리오를 고르면, AI가 작가가 되어 몰입형 스토리를 함께 써 나가는 형태다.

핵심 요구사항은 단순했다.

- 캐릭터/세계관이 정의된 **시나리오**를 고르고
- 그 위에서 여러 갈래의 **플레이(스토리)** 를 진행하며
- AI가 한국어로 길고 묘사가 풍부한 응답을 **실시간 스트리밍**으로 출력

기술 스택은 이렇게 잡았다.

| 영역 | 스택 |
| --- | --- |
| 백엔드 | Spring Boot 3.4.4 + Kotlin 1.9.25 |
| DB | PostgreSQL 15 (Flyway 마이그레이션) |
| 프론트 | React 19 + TypeScript + Vite + Tailwind CSS v4 |
| AI | Claude (Claude Code CLI / Anthropic API 둘 다 지원) |

## 기술 선택 이유 (그리고 지금이라면 다시 고민할 것)

개인 프로젝트라 "익숙해서"가 선택의 큰 축이었다. 솔직하게 적되, 회고도 함께 남긴다.

**백엔드 — Kotlin + Spring Boot.** 평소 익숙하고, **타입 안정성**과 성숙한 JPA/생태계가 이유였다. 다만 이 앱은 AI 응답을 실시간으로 흘려보내는 **스트리밍**이 핵심인데, Spring MVC의 `SseEmitter`는 별도 스레드 풀을 직접 굴려야 했다([2편](./02-claude-sse-streaming.md) 참고). 지금이라면 코루틴 네이티브한 **Ktor**(가볍고 Kotlin스러운 비동기)나, 프론트와 언어를 통일하고 AI SDK 생태계가 풍부한 **TypeScript(Hono/NestJS)**, AI 친화적인 **Python(FastAPI)** 도 후보로 진지하게 봤을 것이다. 하지만 "익숙함 + 타입 안정성"은 개인 프로젝트에서 충분히 정당한 이유다 — 빠르게 만드는 게 우선이었으니까.

**프론트 — React + TS + Vite + Tailwind.** 이건 회고해도 바꿀 게 없다. **Vite의 빠른 HMR**과 **Tailwind의 빠른 스타일링**은 1인 개발에서 체감 생산성이 가장 크다. 사실상 현시점 SPA의 모범 조합이라, 굳이 SvelteKit·SolidJS로 갈 이유를 못 느꼈다.

**DB — PostgreSQL, 그런데 사실 과했을 수도.** "무난한 관계형 DB + 익숙함"으로 골랐지만, 정작 이 앱은 [뒤에서 설명할](#설계-고민-2--대화-기록을-db에-넣을까-파일에-넣을까) 이유로 **무거운 데이터는 전부 파일에 두고 DB는 가벼운 메타데이터만** 담는다. 즉 풀스펙 Postgres 서버를 띄울 만큼의 부하가 없다.

> 누군가 **TursoDB**(임베디드 SQLite 기반의 libSQL을 호스팅/분산으로 확장한 것)를 추천했는데, 왜인지 이해가 됐다. 이런 "DB는 가벼운데 운영 부담은 줄이고 싶은" 개인 앱에선, 서버를 따로 굴리는 Postgres보다 **파일 하나로 끝나는 SQLite류**가 더 잘 맞는다. Turso는 거기에 무료 호스팅·엣지 복제·멀티 DB(스토리별 DB 분리 같은 것)까지 얹어준다. 다만 Turso의 진짜 강점인 "글로벌 엣지 분산"은 로컬 개인 앱에선 의미가 없으니, 핵심 이점은 **"서버 운영 없이 임베디드로 간단히"** 쪽이다. 순수 로컬용이면 그냥 SQLite 파일로도 충분했을 것이다. Postgres는 "익숙하고 튼튼하지만, 이 앱엔 다소 과한" 선택이었다고 정직하게 본다.

**AI — Claude Code CLI.** 유일하게 "익숙함"이 아닌 명확한 이유가 있는 선택. **Max 구독 안에서 동작해 토큰 비용이 0**이다(코드 주석에도 그렇게 적어뒀다). 대신 프로세스를 직접 띄워 출력을 파싱해야 하는 번거로움이 따라오는데, 이 트레이드오프 이야기는 [2편](./02-claude-sse-streaming.md)에서 자세히 다룬다.

## 설계 고민 1 — "시나리오"와 "스토리"를 어떻게 나눌까

처음엔 둘을 한 덩어리로 봤다. "마도생존기"라는 세계가 있고, 거기서 대화하면 되는 것 아닌가? 그런데 막상 써 보니 문제가 생겼다.

같은 캐릭터·세계관으로 **여러 갈래의 플레이**를 동시에 돌리고 싶었다. 한 번은 전투 중심으로, 한 번은 평화로운 일상으로. 그런데 대화 기록이 세계관에 직접 붙어 있으면 갈래를 나눌 수가 없다.

그래서 둘을 명확히 분리했다.

- **시나리오 (Scenario)** = 템플릿. 세계관(`world.md`), 캐릭터 설정(`characters/*.md`), 초기 상황(`scenario.md`). 변하지 않는 원본.
- **스토리 (Story)** = 플레이 인스턴스. 시나리오를 기반으로 시작된 한 번의 플레이. 대화 기록·기억·캐릭터 상태 변화가 여기 쌓인다.

하나의 시나리오에서 N개의 스토리가 파생되고, 각 스토리는 자기만의 대화 흐름을 갖는다. 핵심은 **캐릭터 오버라이드**다. 스토리가 진행되며 캐릭터가 변하면(부상, 관계 변화 등) 그 변화를 스토리 쪽에 따로 저장하고, 프롬프트를 조립할 때 **스토리 설정이 시나리오 원본보다 우선**하도록 했다.

```kotlin
// PromptAssembler.kt — 스토리 오버라이드 우선, 없으면 시나리오 기본
val content = readFileIfExists(storyCharDir.resolve("$charName.md"))
    ?: readFileIfExists(baseCharDir.resolve("$charName.md"))
```

이 한 줄의 fallback 패턴이 시나리오/스토리 분리 설계의 핵심이다. 원본은 건드리지 않고, 변화분만 스토리에 얹는다.

스토리 엔티티는 의외로 단순하다. 무거운 콘텐츠는 전부 파일에 있고, DB는 메타데이터만 든다.

```kotlin
@Entity
@Table(name = "stories")
class Story(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "scenario_id", nullable = false)
    val scenarioId: Long,
    var title: String,
    @Column(name = "data_path", nullable = false)
    val dataPath: String,          // 파일 데이터 위치
    @Column(name = "turn_count")
    var turnCount: Int = 0,        // 자동 요약 트리거에 사용
    var status: StoryStatus = StoryStatus.ACTIVE,
    // ...
)
```

## 설계 고민 2 — 대화 기록을 DB에 넣을까, 파일에 넣을까

이게 가장 많이 고민한 부분이다. 보통 챗 앱이면 메시지를 테이블에 행 단위로 넣는다. 하지만 이 앱은 성격이 달랐다.

- 메시지가 **길다** (한 응답이 1000자 이상인 경우 다반사)
- AI 프롬프트를 만들 때 **통째로 읽어** 컨텍스트로 넘긴다
- 사람이 직접 열어 **읽고 수정**하고 싶을 때가 있다 (캐릭터 설정처럼)

그래서 대화는 **마크다운 파일**에 저장하기로 했다. `chat_latest.md` 하나에 최근 대화가 쌓인다.

```markdown
# 최근 대화

## USER
"이제... 어떻게 하지?"

## ASSISTANT
*동쪽 언덕의 은신처. 바위틈 사이로 햇살이 스며든다.*
"계획부터 세우자."
```

`## USER` / `## ASSISTANT` 헤더로 역할을 구분하는 단순한 포맷이다. 이 포맷 덕분에 파싱도 쉽고, 사람이 읽기에도 자연스럽고, 그대로 git에 넣어 버전 관리도 된다.

DB(PostgreSQL)는 **구조화가 필요한 것**만 담당한다. 시나리오/스토리 메타데이터, 캐릭터 상태(`CharacterState`), 이벤트 로그, 스토리 요약(`StorySummary`) 같은 것들. "검색·집계가 필요하면 DB, 통째로 읽고 쓰는 긴 텍스트면 파일"이라는 기준으로 갈랐다.

`ChatFileService`가 이 파일 I/O를 전담한다. 메시지 추가는 그냥 append다.

```kotlin
fun appendUserMessage(storyPath: Path, message: String) {
    val chatFile = chatLatestPath(storyPath)
    ensureChatFile(chatFile)
    Files.writeString(chatFile, "\n## USER\n$message\n", StandardOpenOption.APPEND)
}
```

수정·삭제·분기처럼 중간을 건드려야 할 땐, 전체를 파싱해서 리스트로 만들고 → 조작하고 → 다시 통째로 쓴다. 메시지가 수백 개씩 되는 앱이 아니라 이 단순한 방식으로 충분했다.

```kotlin
fun deleteMessagesFrom(storyPath: Path, messageIndex: Int): List<ChatMessage> {
    val messages = parseMessages(storyPath).toMutableList()
    val remaining = messages.subList(0, messageIndex).toList()
    writeMessages(storyPath, remaining)   // 인덱스 이후 전부 잘라내고 다시 쓰기
    return remaining
}
```

## 설계 고민 3 — AI 호출을 어떻게 추상화할까

AI 백엔드를 한 군데에 묶지 않으려 했다. Claude Code CLI(구독 활용, 토큰 비용 0), Anthropic API, 나중엔 다른 모델까지 — 갈아끼울 수 있어야 했다.

그래서 `AiProvider` 인터페이스를 두고, 구현체를 Spring Bean으로 등록한 뒤 레지스트리로 이름 기반 선택을 하게 했다.

```kotlin
interface AiProvider {
    val name: String
    fun chat(request: AiRequest): String                       // 동기
    fun streamChat(request: AiRequest, emitter: SseEmitter)     // 스트리밍
}

@Component
class AiProviderRegistry(
    providers: List<AiProvider>,                  // Spring이 모든 구현체를 주입
    @Value("\${crack.ai.default-provider:claude-code-cli}")
    private val defaultProviderName: String
) {
    private val providerMap = providers.associateBy { it.name }
    fun getByName(name: String) = providerMap[name] ?: getDefault()
}
```

Spring이 `List<AiProvider>`로 모든 구현체를 자동 주입해 주기 때문에, 새 프로바이더는 클래스 하나 추가하고 `@Component`만 붙이면 끝이다. 프론트에서 드롭다운으로 프로바이더를 고르면 요청에 `provider` 필드가 실려 오고, 서비스단에서 레지스트리로 해당 구현체를 꺼내 쓴다.

## 프롬프트 조립 — 흩어진 파일을 하나의 시스템 프롬프트로

AI에게 보낼 시스템 프롬프트는 여러 파일 조각을 정해진 순서로 이어 붙여 만든다. `PromptAssembler`가 이 일을 한다.

```
1. 기본 규칙 (작가 역할, 문체 규칙)
2. 세계관 (world.md)
3. 캐릭터 설정 (스토리 오버라이드 우선)
4. 주인공 설정
5. 시나리오 초기 상황 (scenario.md)
6. 필수 기억사항 (memory/must_remember.md)
7. 출력 형식 (감정 태그, 예시)
```

여기에 대화 컨텍스트(이전 요약 + 최근 대화)를 메시지 배열로 따로 만들어 함께 넘긴다. 긴 플레이에서 컨텍스트가 무한정 커지지 않도록, 10턴마다 자동 요약을 돌려 오래된 대화를 압축한다.

```kotlin
// ChatService.onResponseComplete()
if (memoryService.shouldSummarize(newTurnCount)) {
    val result = memoryService.summarize(storyId)   // 오래된 대화 → 요약본으로
}
```

## 정리

1편의 핵심은 **"무엇을 어디에 둘 것인가"** 였다.

- 변하지 않는 템플릿(시나리오) ↔ 변하는 플레이(스토리)를 분리하고, fallback으로 오버라이드
- 긴 텍스트(대화)는 파일, 구조화 데이터는 DB
- AI 백엔드는 인터페이스로 추상화해 갈아끼우기 가능하게

다음 편에서는 이 중 가장 까다로웠던 **AI 응답 실시간 스트리밍** — Claude Code CLI를 프로세스로 띄워 SSE로 흘려보내며 겪은 버그와 해결 과정을 다룬다.

➡️ [2편 — Claude를 AI 엔진으로: CLI 프로바이더와 SSE 스트리밍](./02-claude-sse-streaming.md)
