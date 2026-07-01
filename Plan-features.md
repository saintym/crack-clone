# 기능 추가 계획

## 구현 순서

Feature 3이 아키텍처 기반이므로 **반드시 먼저** 구현해야 합니다.

```
Phase 0: Feature 3 (시나리오/스토리 분리) ← 기반 아키텍처
  ├── Phase 1: Feature 1 (턴별 모델 선택)
  └── Phase 2: Feature 2 (이미지 출력)
```

---

## Feature 3: 시나리오/스토리 분리 (Phase 0)

### 개념

| | 시나리오 (Class) | 스토리 (Instance) |
|---|---|---|
| **역할** | 템플릿/설정 | 실제 플레이스루 |
| **소유 데이터** | world.md, scenario.md, 기본 캐릭터 정의 | 채팅 히스토리, 메모리, 요약, 캐릭터 상태 변화 |
| **관계** | 1:N | N:1 (하나의 시나리오에 여러 스토리) |
| **변경 가능성** | 기본 설정이므로 수정 가능하지만 플레이와 무관 | 플레이하면서 계속 변화 |

### 파일 시스템 구조 변경

```
data/마도생존기/                    ← 시나리오 (템플릿)
  world.md
  scenario.md
  characters/
    protagonist.md
    설월.md, 소율.md, ...
  stories/
    1/                              ← 스토리 #1 (첫 번째 플레이)
      chat/
        chat_latest.md
        archive/
      memory/
        must_remember.md
        summary_001_010.md
      characters/                   ← 스토리 중 변화된 캐릭터만 (Copy-on-Write)
        설월.md                     ← 기본 캐릭터에서 진화된 버전
    2/                              ← 스토리 #2 (두 번째 플레이)
      chat/
      memory/
```

캐릭터 조회 시: **스토리 폴더 먼저 확인 → 없으면 시나리오 기본 캐릭터 사용** (상속 패턴)

### DB 변경

**신규 테이블: `stories`**

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| scenario_id | BIGINT FK | 시나리오 참조 |
| title | VARCHAR | "1회차", "소율 루트" 등 |
| data_path | VARCHAR | 스토리 데이터 경로 |
| turn_count | INT | 현재 턴 수 (기존 Scenario에서 이동) |
| status | VARCHAR | ACTIVE / ARCHIVED |
| created_at | TIMESTAMP | |

**기존 테이블 변경:**
- `scenarios`: `turn_count` 컬럼 제거
- `story_summaries`, `character_states`, `character_events`, `chat_messages`: `story_id` 컬럼 추가

### API 라우트 변경

```
[시나리오 - 템플릿 관리]
GET    /api/scenarios                          → 시나리오 목록
POST   /api/scenarios                          → 시나리오 생성
GET    /api/scenarios/{name}                   → 시나리오 조회
DELETE /api/scenarios/{name}                   → 시나리오 삭제
PUT    /api/scenarios/{name}/documents/{type}  → 세계관/시나리오 문서 수정
GET    /api/scenarios/{name}/characters        → 기본 캐릭터 목록

[스토리 - 플레이 관리]
GET    /api/scenarios/{name}/stories           → 스토리 목록
POST   /api/scenarios/{name}/stories           → 새 스토리 생성

GET    /api/stories/{id}                       → 스토리 조회
DELETE /api/stories/{id}                       → 스토리 삭제
POST   /api/stories/{id}/chat                  → 채팅 (스트리밍)
POST   /api/stories/{id}/chat/complete         → 응답 완료
GET    /api/stories/{id}/chat/history          → 채팅 히스토리
```

### 프론트엔드 페이지 흐름

```
시나리오 목록 → 스토리 목록 (NEW) → 채팅
     │              │
     └→ 설정        └→ 새 스토리 생성
```

**신규 페이지:** `StoriesPage.tsx` — 세이브 파일 목록처럼 해당 시나리오의 플레이 목록 표시

**변경:**
- `ScenariosPage.tsx`: 카드 클릭 → 스토리 목록으로 이동 (기존: 바로 채팅)
- `ChatPage.tsx`: `scenarioName` 대신 `storyId` 사용
- `App.tsx`: 라우트 추가 (`/scenario/:name/stories`, `/story/:id`)

### 백엔드 수정 파일

| 파일 | 변경 내용 |
|---|---|
| **신규** Story entity/repo/service/controller/dto | 스토리 도메인 전체 |
| **신규** Flyway `V3__story_entity.sql` | DB 마이그레이션 |
| Scenario entity | `turnCount` 제거 |
| ChatService | `storyId` 기반으로 전환 |
| ChatFileService | Story의 `dataPath` 사용 |
| ChatController | `/api/stories/{id}/chat` 라우트 |
| PromptAssembler | 시나리오 기본경로 + 스토리 경로 이중 참조 |
| MemoryService | 스토리 경로에 캐릭터 오버라이드 저장 |
| StorySummaryService | `storyId`로 쿼리 |
| ContextService | `storyId`로 쿼리 |
| StateService | `storyId`로 쿼리 |
| 각 Repository | `findByStoryId*` 메서드 추가 |

### 데이터 마이그레이션

기존 시나리오당 자동으로 "기본 스토리" 1개 생성. 기존 chat/memory 파일을 `stories/1/`로 이동.

---

## Feature 1: 턴별 모델 선택 (Phase 1)

### 설계

Provider-agnostic 인터페이스로 Claude와 Gemini를 동시에 지원할 수 있게 합니다.

```kotlin
interface AiProvider {
    val providerName: String           // "claude", "gemini"
    fun supportedModels(): List<AiModelInfo>
    fun streamChat(request: AiRequest, emitter: SseEmitter)
    fun chat(request: AiRequest): String
}
```

**`AiProviderRegistry`**: 모든 Provider를 관리, modelId로 적절한 Provider 라우팅

### 모델 선택 범위

- **사용자 선택**: 주 응답 모델만 (매 턴 선택 가능)
- **자동 결정**: 요약(Sonnet/Flash), 컨텍스트 분석(Haiku/Lite)은 시스템이 자동 결정

### 백엔드 변경

| 파일 | 변경 |
|---|---|
| **신규** `AiProvider` 인터페이스 | Provider 추상화 |
| **신규** `AiProviderRegistry` | Provider 라우팅 |
| **신규** `ModelController` | `GET /api/models` |
| ClaudeService | `AiProvider` 구현 |
| **신규** GeminiService (향후) | `AiProvider` 구현 |
| ChatRequest DTO | `modelId: String?` 필드 추가 |
| ChatService | Registry를 통해 Provider 선택 |

### 프론트엔드 변경

- **신규** `ModelSelector.tsx` 컴포넌트: 채팅 입력 옆 모델 선택 드롭다운
- `ChatPage.tsx`: 모델 선택 UI 추가, 요청에 `modelId` 포함
- 비용 티어 표시: $, $$, $$$ 아이콘

---

## Feature 2: 이미지 출력 (Phase 2)

### 설계

시나리오별 **이미지 카탈로그** (태그 → URL 매핑)를 만들고, AI가 응답에 태그를 삽입하면 실제 URL로 치환합니다.

```
AI 응답: *설월이 미소를 짓는다.* ![이미지:설월_미소] "고마워."
          ↓ 후처리
표시: [텍스트] [이미지] [대사]
```

### DB

**신규 테이블: `scenario_images`**

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| scenario_id | BIGINT FK | |
| tag | VARCHAR UNIQUE | "설월_미소", "전투_장면" |
| image_url | VARCHAR | 외부 이미지 URL |
| description | VARCHAR | AI 컨텍스트용 설명 |
| category | VARCHAR | "character" / "scene" / "location" |

### 처리 흐름

```
1. PromptAssembler: 사용 가능한 이미지 태그 목록을 시스템 프롬프트에 주입
2. AI 응답: ![이미지:태그명] 형식으로 이미지 삽입
3. MessageParser: IMAGE 세그먼트로 파싱
4. ImageResolverService: 태그 → URL 변환
5. 프론트엔드: /api/images/proxy?url=... 로 이미지 렌더링 (CORS 회피)
```

### 백엔드 변경

| 파일 | 변경 |
|---|---|
| **신규** `scenario_images` 테이블 (V4 마이그레이션) | |
| **신규** ScenarioImage entity/repo | |
| **신규** ImageService | 카탈로그 CRUD |
| **신규** ImageController | 카탈로그 API + 이미지 프록시 |
| **신규** ImageResolverService | 태그 → URL 변환 |
| MessageParser | `IMAGE` 세그먼트 타입 추가 |
| PromptAssembler | 이미지 태그 목록 시스템 프롬프트에 주입 |

### 프론트엔드 변경

- `ChatBubble`: 이미지 세그먼트 렌더링 (`<img>` 태그)
- `ScenarioDetailPage`: "이미지" 탭 추가 (카탈로그 관리)
- **신규** `src/api/images.ts`

---

## 전체 예상 작업량

| Phase | Feature | 신규 파일 | 수정 파일 | 테스트 |
|---|---|---:|---:|---:|
| 0 | 시나리오/스토리 분리 | ~8 | ~15 | ~10 |
| 1 | 턴별 모델 선택 | ~5 | ~4 | ~5 |
| 2 | 이미지 출력 | ~7 | ~3 | ~5 |
| **합계** | | **~20** | **~22** | **~20** |
