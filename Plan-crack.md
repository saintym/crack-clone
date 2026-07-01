# Plan: 크랙(Crack) 클론 - AI 캐릭터 채팅 앱

## 개요

뤼튼의 "크랙" 앱을 개인 취미 프로젝트로 재현한다. 로컬 맥북에서 혼자 사용하는 용도이다.

`sonnet-interactive-novel` 프로젝트처럼 **문서(마크다운 파일) 기반**으로 세계관, 등장인물, 주인공, 필수기억사항을 관리한다. 기억(memory) 시스템은 **10턴 단위로 요약하여 문서로 저장**하고, AI가 이를 읽어 맥락을 유지한다. 대화 히스토리가 방대해지면 DB에 아카이빙한다.

Claude API(Anthropic SDK)를 AI 백본으로 사용한다.

## 크랙 핵심 기능 분석

| 기능 | 설명 | MVP 포함 |
|------|------|----------|
| 캐릭터 생성 | 성격, 외모, 배경, 말투, 감정 패턴 설정 | O |
| 세계관 생성 | 시대, 장소, 규칙, 분위기 설정 | O |
| 시나리오 생성 | 초기 상황 설정 (한 문장으로 세계 생성) | O |
| AI 채팅 | 캐릭터가 인캐릭터로 응답 | O |
| 채팅 히스토리 | 대화 내용 영속 저장 및 이어하기 | O |
| 시스템 프롬프트 관리 | 캐릭터 일관성을 위한 프롬프트 설계 | O |
| 다중 캐릭터 시나리오 | 하나의 시나리오에 여러 캐릭터 참여 | O |
| 감정 표현 | AI가 감정 상태를 표현하며 응답 | O |
| 스토리 분기 | 사용자 선택에 따른 이야기 분기 | 후순위 |
| 캐릭터 이미지 생성 | AI 이미지 생성으로 캐릭터 시각화 | 후순위 |
| 캐릭터 공유/마켓 | 다른 사용자가 만든 캐릭터 사용 | 제외 (개인용) |
| 음성 합성 | 캐릭터 음성 출력 | 제외 |

## 기술 스택

| 구성 요소 | 기술 | 역할 |
|-----------|------|------|
| 프론트엔드 | React (TypeScript / Vite) | 채팅 UI, 캐릭터/세계관 관리 화면 |
| 백엔드 | Spring Boot (Kotlin) | API 서버, DDD 기반 도메인 설계 |
| 쿼리 | QueryDSL | 동적 쿼리, 채팅 히스토리 검색 |
| DB 마이그레이션 | Flyway | 스키마 버전 관리 |
| 데이터베이스 | PostgreSQL (Docker, 5432) | 캐릭터, 세계관, 채팅 데이터 저장 |
| AI 엔진 | Claude API (Anthropic Java/Kotlin SDK) | 캐릭터 AI 응답 생성 |
| 실시간 통신 | SSE (Server-Sent Events) | 스트리밍 AI 응답 전달 |

### 기술 선택 근거

- **SSE vs WebSocket**: AI 응답은 서버 -> 클라이언트 단방향 스트리밍이므로 SSE로 충분. WebSocket보다 구현이 간단하고 Spring Boot에서 기본 지원
- **Anthropic Java SDK**: Spring Boot(Kotlin) 환경에서 공식 Java SDK(`anthropic-sdk-java`)를 사용. 스트리밍 응답 지원
- **JSONB 활용**: 캐릭터 성격 특성, 감정 상태, 말투 패턴 등 유연한 구조의 데이터를 PostgreSQL JSONB로 저장

## 아키텍처

```
┌──────────────────────────────────────────────────────┐
│                Frontend (React + Vite)                │
│  ┌────────────┐  ┌────────────┐  ┌────────────────┐  │
│  │ 채팅 화면  │  │ 시나리오   │  │ 문서 편집      │  │
│  │ (SSE수신)  │  │ 관리       │  │ (캐릭터/세계관)│  │
│  └─────┬──────┘  └─────┬──────┘  └──────┬─────────┘  │
│        │               │                │            │
│        └───────────────┼────────────────┘            │
│                        │ HTTP + SSE                  │
└────────────────────────┼─────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────┐
│              Spring Boot (Backend)                    │
│                                                      │
│  ┌─────────────────┐  ┌───────────────────────────┐  │
│  │ scenario 도메인 │  │ document 도메인           │  │
│  │ - 시나리오 관리  │  │ - md 파일 읽기/쓰기      │  │
│  │ - 메타데이터 DB  │  │ - 세계관/캐릭터/주인공   │  │
│  └─────────────────┘  └───────────────────────────┘  │
│                                                      │
│  ┌─────────────────┐  ┌───────────────────────────┐  │
│  │ chat 도메인     │  │ memory 도메인             │  │
│  │ - 메시지 관리   │  │ - 10턴 요약 생성/저장     │  │
│  │ - SSE 스트리밍  │  │ - 필수기억사항 관리       │  │
│  │ - 파일 기록     │  │ - 캐릭터/주인공 문서 갱신 │  │
│  └─────────────────┘  └───────────────────────────┘  │
│                                                      │
│  ┌─────────────────┐  ┌───────────────────────────┐  │
│  │ ai 도메인       │  │ prompt 도메인             │  │
│  │ - Claude API    │  │ - 파일 읽어서 프롬프트 조립│  │
│  │ - 스트리밍 응답 │  │ - 컨텍스트 윈도우 관리    │  │
│  └─────────────────┘  └───────────────────────────┘  │
└────────────────────────┬─────────────────────────────┘
                         │
        ┌────────────────┼────────────────┐
        ▼                ▼                ▼
┌───────────────┐ ┌─────────────┐ ┌──────────────────┐
│ 파일 시스템   │ │ PostgreSQL  │ │ Claude API       │
│ data/{시나리오}│ │ (보조)      │ │ (Anthropic)      │
│ - world.md    │ │ - scenarios │ │ - claude-sonnet  │
│ - characters/ │ │   (메타)    │ │ - 스트리밍 응답  │
│ - memory/     │ │ - chat_msgs │ │                  │
│ - chat/       │ │   (아카이브)│ │                  │
└───────────────┘ └─────────────┘ └──────────────────┘
```

## 핵심 설계: 시스템 프롬프트 아키텍처

크랙 클론의 핵심은 **캐릭터가 일관되게 인캐릭터를 유지**하는 것이다. 이를 위해 시스템 프롬프트를 계층적으로 조립한다.

### 프롬프트 조립 구조

```
[System Prompt 최종 조립]
├── 1. 기본 규칙 (Base Rule)
│   └── "너는 캐릭터 롤플레이 AI다. 아래 설정을 절대 벗어나지 마라..."
│
├── 2. 세계관 컨텍스트 (World Context)
│   └── 시대, 장소, 분위기, 세계 규칙
│
├── 3. 캐릭터 정의 (Character Definition)
│   ├── 이름, 나이, 외모
│   ├── 성격 특성 (traits)
│   ├── 말투 패턴 (speech_pattern)
│   ├── 배경 스토리 (backstory)
│   ├── 감정 표현 규칙 (emotion_rules)
│   └── 대사 예시 (example_dialogues)
│
├── 4. 시나리오 컨텍스트 (Scenario Context)
│   └── 현재 상황, 장면 설명, 관계 설정
│
├── 5. 다중 캐릭터 규칙 (Multi-Character Rules) [해당시]
│   └── 각 캐릭터의 구분, 대사 형식
│
└── 6. 출력 형식 (Output Format)
    └── 감정 태그, 행동 묘사, 대사 형식 규칙
```

### 출력 형식 설계

AI 응답에 감정과 행동을 구조화하여 프론트엔드에서 파싱 가능하게 한다.

```
[감정: 부끄러움, 설렘]
*볼이 살짝 붉어지며 시선을 피한다*
"그, 그런 말 갑자기 하면... 당황스럽잖아."
*작은 목소리로 중얼거리며 머리카락을 만지작거린다*
"...근데, 고마워."
```

- `[감정: ...]` - 현재 감정 상태 (프론트엔드에서 감정 아이콘 표시용)
- `*...*` - 행동/상황 묘사 (이탤릭 렌더링)
- `"..."` - 대사 (일반 텍스트)

### 데이터 저장 전략: 문서 1차 + DB 보조

`sonnet-interactive-novel` 프로젝트와 동일하게 **마크다운 파일이 진실의 원천(source of truth)**이다. DB는 대화 히스토리가 방대해졌을 때만 아카이빙 용도로 사용한다.

#### 파일 기반 저장 구조

```
crack-clone/data/{시나리오명}/
├── world.md                    # 세계관 설정
├── characters/
│   ├── _TEMPLATE.md            # 캐릭터 템플릿
│   ├── protagonist.md          # 주인공(사용자) 설정
│   ├── 하은.md                 # NPC 캐릭터
│   └── 준호.md
├── scenario.md                 # 시나리오 초기 상황
├── memory/
│   ├── must_remember.md        # 필수 기억사항 (사용자 직접 입력)
│   ├── summary_001_010.md      # 1~10턴 요약
│   ├── summary_011_020.md      # 11~20턴 요약
│   └── summary_021_030.md      # 21~30턴 요약
└── chat/
    ├── chat_latest.md          # 현재 진행중인 최근 대화 (최근 10턴)
    └── archive/                # 오래된 대화 원문 (선택적 보관)
        ├── turn_001_010.md
        └── turn_011_020.md
```

#### 문서별 역할

| 문서 | 역할 | AI 읽기 시점 |
|------|------|------------|
| `world.md` | 세계관 (시대, 장소, 규칙, 분위기) | 매 턴 (시스템 프롬프트에 포함) |
| `characters/*.md` | 등장인물 설정 (성격, 말투, 배경) | 매 턴 (해당 캐릭터가 등장할 때) |
| `protagonist.md` | 주인공(사용자) 설정 | 매 턴 (시스템 프롬프트에 포함) |
| `must_remember.md` | 사용자가 직접 입력한 필수 기억사항 | 매 턴 (시스템 프롬프트에 포함) |
| `summary_*.md` | 10턴 단위 요약 | 매 턴 (최근 3~5개 요약만 로드) |
| `chat_latest.md` | 최근 10턴 대화 원문 | 매 턴 |

#### DB 사용 시점 (보조)

| 상황 | 저장소 |
|------|--------|
| 세계관, 캐릭터, 주인공 | **파일** (항상) |
| 필수 기억사항 | **파일** (항상) |
| 최근 10턴 대화 | **파일** (`chat_latest.md`) |
| 10턴 요약 | **파일** (`memory/summary_*.md`) |
| 100턴 이상 대화 원문 아카이빙 | **DB** (chat_messages 테이블) |
| 시나리오 메타데이터 (목록, 생성일 등) | **DB** (scenarios 테이블) |

### 기억(Memory) 시스템

#### 10턴 단위 요약 플로우

```
10턴 도달
    │
    ▼
1. 최근 10턴 대화를 Claude에게 요약 요청
    │
    ▼
2. 요약 결과를 memory/summary_NNN_NNN.md 로 저장
    │
    ▼
3. 등장인물 문서 갱신 (관계 변화, 주요 사건 추가)
    │
    ▼
4. 주인공 문서 갱신 (상태 변화, 새로운 정보)
    │
    ▼
5. chat_latest.md 초기화 (요약된 턴 제거, 새 턴부터 기록)
    │
    ▼
6. (선택) 원문을 archive/ 또는 DB에 보관
```

#### 필수 기억사항 (`must_remember.md`)

사용자가 직접 입력하는 "절대 잊으면 안 되는 사항". AI는 매 턴 이 파일을 읽어 시스템 프롬프트에 포함한다.

```markdown
# 필수 기억사항

- 하은은 3턴에서 주인공에게 이름을 가르쳐줬다
- 주인공은 왼팔에 화상 흉터가 있다
- 마을 동쪽 숲에 봉인된 탑이 있다 (아직 미탐험)
- 하은은 주인공이 "해바라기" 라고 부르는 것을 싫어한다
```

### 컨텍스트 윈도우 관리

매 턴 Claude API에 보내는 프롬프트 구성:

```
시스템 프롬프트 (~2000 토큰)
├── 기본 규칙
├── 세계관 (world.md)
├── 캐릭터 설정 (characters/*.md)
├── 주인공 설정 (protagonist.md)
├── 필수 기억사항 (must_remember.md)
└── 출력 형식 규칙
│
이전 대화 요약 (~1000 토큰)
├── 최근 3~5개 summary_*.md 내용
│
최근 대화 히스토리 (~3000 토큰)
├── chat_latest.md (최근 10턴)
│
현재 사용자 입력
└── user: "..."
```

## DB 스키마 설계

파일(md)은 **기본 설정(성격/말투/세계관)**의 원천이고, DB는 **시간에 따라 변하는 데이터**를 관리한다.

```sql
-- V1__init_schema.sql

-- 시나리오 메타데이터
CREATE TABLE scenarios (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    title           VARCHAR(255) NOT NULL,
    data_path       VARCHAR(2048) NOT NULL,
    turn_count      INT DEFAULT 0,
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- 대화 메시지 아카이빙
CREATE TABLE chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    character_name  VARCHAR(255),
    content         TEXT NOT NULL,
    emotion         VARCHAR(100),
    turn_number     INT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_scenario ON chat_messages(scenario_id);
CREATE INDEX idx_chat_messages_turn ON chat_messages(scenario_id, turn_number);
```

```sql
-- V2__story_and_state.sql

-- 스토리 요약 (다단계: L1=10턴, L2=30턴, L3=100턴)
CREATE TABLE story_summaries (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    level           VARCHAR(5) NOT NULL,           -- L1, L2, L3
    from_turn       INT NOT NULL,
    to_turn         INT NOT NULL,
    content         TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_story_summaries_scenario ON story_summaries(scenario_id, level);

-- 캐릭터 이벤트 (일어난 일)
CREATE TABLE character_events (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    character_name  VARCHAR(255) NOT NULL,
    turn_number     INT NOT NULL,
    event_type      VARCHAR(30) NOT NULL,          -- RELATIONSHIP_CHANGE, KNOWLEDGE_GAIN, PERSONALITY_SHIFT, MAJOR_EVENT
    summary         VARCHAR(500) NOT NULL,
    detail          TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_char_events_scenario ON character_events(scenario_id, character_name);
CREATE INDEX idx_char_events_turn ON character_events(scenario_id, turn_number);

-- 캐릭터 상태 (현재 상태 — 소지품, 위치, 관계, 스케줄 등)
CREATE TABLE character_states (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    character_name  VARCHAR(255) NOT NULL,
    state_type      VARCHAR(30) NOT NULL,          -- INVENTORY, LOCATION, SCHEDULE, RELATIONSHIP, SKILL, STATUS
    state_key       VARCHAR(255) NOT NULL,
    state_value     TEXT NOT NULL,
    context         TEXT,                           -- 부가 조건 ("평일만", "야근 자주함" 등)
    acquired_turn   INT,
    is_active       BOOLEAN DEFAULT TRUE,
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_char_states_scenario ON character_states(scenario_id, character_name);
CREATE INDEX idx_char_states_location ON character_states(scenario_id, state_type, is_active);

-- 시나리오 설정 (출력 형식, 예시 장면, 분량 규칙 등)
CREATE TABLE scenario_settings (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    setting_key     VARCHAR(50) NOT NULL,          -- OUTPUT_TEMPLATE, EXAMPLE_SCENE, VOLUME_RULE, DIALOGUE_FORMAT, CUSTOM_RULE
    setting_value   TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(scenario_id, setting_key)
);
```

### 서술 전 컨텍스트 자동 로딩 (3단계)

```
[1단계] 경량 AI (Haiku) — 컨텍스트 판별
  입력: 사용자 메시지 + 전체 캐릭터의 LOCATION/SCHEDULE/RELATIONSHIP 상태
  출력: 이 장면에 등장할 캐릭터 목록 + 이유

[2단계] 컨텍스트 조립
  시스템 프롬프트:
    ├── 기본 규칙 + 설정(DB: scenario_settings)
    ├── 세계관 (world.md)
    ├── 주인공 기본설정 (protagonist.md) + 최근 이벤트(DB)
    ├── 활성 캐릭터별:
    │   ├── 기본설정 (md 파일)
    │   ├── 최근 이벤트 (DB, 최근 20턴)
    │   ├── 핵심 이벤트 (DB, 관계/성격 변화만)
    │   └── 현재 상태 (DB, INVENTORY/LOCATION/STATUS)
    └── 출력 형식 + 예시 (DB: scenario_settings)
  대화 컨텍스트:
    ├── 메타 요약 L3 (있으면)
    ├── 전체 요약 L2 (최근 1~2개)
    ├── 10턴 요약 L1 (최근 2~3개)
    ├── 필수 기억사항
    └── 최근 대화 (chat_latest.md)

[3단계] 본 응답 (Opus 4.7) — 서술 생성
```

## DDD 도메인 구분

| 도메인 | 핵심 책임 | 저장소 |
|--------|-----------|--------|
| **scenario** | 시나리오 관리 (생성, 목록, 파일 경로 매핑) | DB + 파일 |
| **document** | 문서(md) 읽기/쓰기 (세계관, 캐릭터, 주인공, 기억) | 파일 |
| **chat** | 채팅 관리, 메시지 저장, SSE 스트리밍 | 파일 + DB(아카이빙) |
| **memory** | 다단계 요약(L1~L3), 필수기억사항 | 파일 + DB |
| **state** | 캐릭터 이벤트, 캐릭터 상태(소지품/위치/스케줄/관계) | DB |
| **context** | 서술 전 컨텍스트 판별 (Haiku), 활성 캐릭터 결정 | DB |
| **setting** | 시나리오별 설정 (출력 형식, 예시, 분량, 대화 형식) | DB |
| **ai** | Claude API 호출 (멀티 모델: Opus/Sonnet/Haiku) | - |
| **prompt** | 시스템 프롬프트 조립 (파일 + DB 조합) | 파일 + DB |
| **global** | 공통 설정, 예외 처리, WebConfig | - |

### 패키지 규칙
- **controller** — REST API 엔드포인트, 요청/응답 처리
- **service** — 비즈니스 로직, 트랜잭션 관리
- **repository** — 데이터 접근 계층 (JPA Repository + QueryDSL)
- **entity** — JPA 엔티티 클래스
- **dto** — 요청/응답 데이터 전송 객체

### 프론트엔드 뷰/로직 분리 원칙
- **components/** — 순수 UI. props로 데이터를 받아 렌더링만 담당
- **hooks/** — 비즈니스 로직. API 호출, 상태 관리, 데이터 가공을 커스텀 훅으로 분리
- **api/** — 서버 통신 함수. hooks에서 호출
- **pages/** — components + hooks 조합으로 페이지 구성

## 프로젝트 구조

```
crack-clone/
├── Plan-crack.md
│
├── data/                                  # 시나리오 데이터 (파일 기반)
│   └── {시나리오명}/                       # 예: 마도생존기/
│       ├── world.md                       # 세계관 설정
│       ├── scenario.md                    # 시나리오 초기 상황
│       ├── characters/
│       │   ├── _TEMPLATE.md               # 캐릭터 템플릿
│       │   ├── protagonist.md             # 주인공(사용자)
│       │   ├── 하은.md                    # NPC
│       │   └── ...
│       ├── memory/
│       │   ├── must_remember.md           # 필수 기억사항 (사용자 입력)
│       │   ├── summary_001_010.md         # 1~10턴 요약
│       │   ├── summary_011_020.md
│       │   └── ...
│       └── chat/
│           ├── chat_latest.md             # 최근 10턴 대화
│           └── archive/                   # 원문 보관
│               └── turn_001_010.md
│
├── crack-backend/                         # Spring Boot (Kotlin)
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/main/
│       ├── kotlin/com/crack/
│       │   ├── CrackApplication.kt
│       │   ├── scenario/
│       │   │   ├── controller/
│       │   │   ├── service/
│       │   │   ├── repository/
│       │   │   ├── entity/
│       │   │   └── dto/
│       │   ├── document/                  # 파일(md) 읽기/쓰기
│       │   │   ├── controller/
│       │   │   ├── service/
│       │   │   │   └── DocumentService.kt # md 파일 CRUD
│       │   │   └── dto/
│       │   ├── chat/
│       │   │   ├── controller/            # SSE 엔드포인트 포함
│       │   │   ├── service/
│       │   │   ├── repository/            # DB 아카이빙용
│       │   │   ├── entity/
│       │   │   └── dto/
│       │   ├── memory/                    # 기억 시스템
│       │   │   ├── service/
│       │   │   │   └── MemoryService.kt   # 10턴 요약, 문서 갱신
│       │   │   └── dto/
│       │   ├── ai/
│       │   │   ├── service/               # ClaudeService
│       │   │   ├── config/                # ClaudeConfig
│       │   │   └── dto/
│       │   ├── prompt/
│       │   │   ├── service/
│       │   │   │   └── PromptAssembler.kt # 파일 읽어서 프롬프트 조립
│       │   │   └── dto/
│       │   └── global/
│       │       ├── config/
│       │       └── exception/
│       └── resources/
│           ├── application.yml
│           └── db/migration/
│               └── V1__init_schema.sql
│
├── crack-frontend/                        # React (TypeScript / Vite)
│   └── src/
│       ├── api/
│       │   ├── client.ts
│       │   ├── characterApi.ts
│       │   ├── worldApi.ts
│       │   ├── scenarioApi.ts
│       │   └── chatApi.ts
│       ├── components/
│       │   ├── common/
│       │   ├── character/
│       │   ├── world/
│       │   ├── scenario/
│       │   └── chat/
│       │       ├── ChatBubble.tsx
│       │       ├── ChatInput.tsx
│       │       ├── ChatHeader.tsx
│       │       ├── EmotionTag.tsx
│       │       └── MessageList.tsx
│       ├── hooks/
│       │   ├── useCharacters.ts
│       │   ├── useWorlds.ts
│       │   ├── useScenarios.ts
│       │   ├── useChat.ts                 # SSE 연결, 메시지 송수신
│       │   └── useChatRooms.ts
│       ├── pages/
│       │   ├── HomePage.tsx
│       │   ├── CharactersPage.tsx
│       │   ├── WorldsPage.tsx
│       │   ├── ScenarioCreatePage.tsx
│       │   └── ChatPage.tsx
│       ├── types/
│       │   └── index.ts
│       └── utils/
│           ├── messageParser.ts           # AI 응답 파싱
│           └── formatters.ts
│
└── .gitignore
```

## AI 채팅 플로우

```
사용자 메시지 입력
    │
    ▼
ChatController (POST /api/scenarios/{name}/chat)
    │
    ▼
ChatService
    ├── 1. 사용자 메시지를 chat_latest.md에 기록
    ├── 2. PromptAssembler 호출
    │       ├── 파일에서 시스템 프롬프트 조립
    │       │   ├── world.md
    │       │   ├── characters/*.md
    │       │   ├── protagonist.md
    │       │   └── must_remember.md
    │       ├── memory/summary_*.md 에서 이전 요약 로드
    │       └── chat_latest.md 에서 최근 대화 로드
    ├── 3. ClaudeService 호출 (스트리밍)
    │       ├── Claude API 요청 (SSE 스트림)
    │       └── 토큰 단위로 프론트에 전달
    ├── 4. 응답 완료 후 chat_latest.md에 AI 응답 기록
    │       └── 감정 태그 파싱
    └── 5. MemoryService — 컨텍스트 관리
            ├── 턴 카운트 증가 (scenarios 테이블)
            ├── 10턴 도달 시: 요약 생성 → summary_*.md 저장
            ├── 캐릭터/주인공 문서 갱신
            └── chat_latest.md 초기화 (요약된 턴 제거)
```

## 캐릭터 생성 필드

| 필드 | 필수 | 설명 | 예시 |
|------|------|------|------|
| 이름 | O | 캐릭터 이름 | "하은" |
| 나이 | X | 자유 형식 | "22살", "수백 년" |
| 성별 | X | 자유 형식 | "여성", "무성" |
| 외모 | X | 상세 외모 묘사 | "은발에 붉은 눈..." |
| 성격 | O | 자유 형식 성격 설명 | "겉으로는 차갑지만 속으로는 다정한..." |
| 성격 태그 | X | 태그 배열 | ["츤데레", "다정함"] |
| 배경 스토리 | X | 캐릭터의 과거 | "왕국의 몰락한 귀족 출신으로..." |
| 말투 | O | 말투 패턴 설명 | "~요 체, 가끔 사투리" |
| 대사 예시 | X | 3~5개 예시 대사 | "흥, 그런 거 아니라고..." |
| 감정 규칙 | X | 감정 표현 방식 | "부끄러울 때 말을 더듬음" |
| 첫 인사 | X | 채팅 시작 시 인사 | "...뭐야, 왜 쳐다봐." |

## Anthropic SDK 연동

### build.gradle.kts 의존성
```kotlin
implementation("com.anthropic:anthropic-java:1.+")
```

### application.yml
```yaml
claude:
  api-key: ${CLAUDE_API_KEY}
  models:
    primary: claude-opus-4-7          # 본 응답 (캐릭터 대화/서술)
    utility: claude-sonnet-4-6-latest # 요약, 문서 갱신
    light: claude-haiku-4-5-latest    # 컨텍스트 판별 (경량)
  max-tokens: 2048
```

## 주요 API 엔드포인트

### 시나리오 관리
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/scenarios` | 시나리오 목록 (DB 메타데이터) |
| POST | `/api/scenarios` | 시나리오 생성 (폴더 + 템플릿 파일 + DB 메타) |
| GET | `/api/scenarios/{name}` | 시나리오 상세 (메타 + 문서 요약) |
| DELETE | `/api/scenarios/{name}` | 시나리오 삭제 (폴더 + DB) |

### 문서 관리 (파일 기반)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/scenarios/{name}/documents` | 시나리오 내 모든 문서 목록 |
| GET | `/api/scenarios/{name}/documents/{type}` | 문서 읽기 (type: world, scenario, protagonist) |
| PUT | `/api/scenarios/{name}/documents/{type}` | 문서 수정 |
| GET | `/api/scenarios/{name}/characters` | 캐릭터 목록 |
| GET | `/api/scenarios/{name}/characters/{charName}` | 캐릭터 문서 읽기 |
| POST | `/api/scenarios/{name}/characters` | 캐릭터 추가 (md 파일 생성) |
| PUT | `/api/scenarios/{name}/characters/{charName}` | 캐릭터 수정 |
| DELETE | `/api/scenarios/{name}/characters/{charName}` | 캐릭터 삭제 |

### 채팅
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/scenarios/{name}/chat` | 메시지 전송 + SSE 스트리밍 응답 |
| GET | `/api/scenarios/{name}/chat/history` | 최근 대화 히스토리 (chat_latest.md) |
| GET | `/api/scenarios/{name}/chat/summaries` | 요약 목록 (memory/summary_*.md) |

### 기억 관리
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/scenarios/{name}/memory/must-remember` | 필수 기억사항 읽기 |
| PUT | `/api/scenarios/{name}/memory/must-remember` | 필수 기억사항 수정 |
| POST | `/api/scenarios/{name}/memory/summarize` | 수동 요약 트리거 |

## 구현 단계 (Phase)

### Phase 1 — 기반 구축 + 문서 관리
- [ ] Spring Boot 프로젝트 초기화 (`crack-backend`)
- [ ] React 프로젝트 초기화 (`crack-frontend`)
- [ ] DB 마이그레이션 (V1__init_schema.sql) — scenarios, chat_messages 테이블만
- [ ] `data/` 폴더 구조 및 템플릿 파일 생성
- [ ] 시나리오 생성 API (폴더 생성 + 템플릿 복사 + DB 메타 저장)
- [ ] document 도메인: md 파일 읽기/쓰기 API
- [ ] 캐릭터/세계관/주인공 문서 편집 UI

### Phase 2 — 채팅 핵심
- [ ] Claude API 연동 (`ClaudeService`)
- [ ] 프롬프트 조립 엔진 (`PromptAssembler`) — 파일 기반 조립
- [ ] SSE 스트리밍 채팅 (메시지 → chat_latest.md 기록)
- [ ] AI 응답 파싱 (감정 태그, 행동 묘사, 대사 분리)
- [ ] 채팅 UI (ChatPage + SSE 수신)

### Phase 3 — 기억 시스템
- [ ] 10턴 단위 자동 요약 (`MemoryService`)
- [ ] 요약 결과를 memory/summary_*.md 에 저장
- [ ] 캐릭터/주인공 문서 자동 갱신 (관계 변화, 주요 사건)
- [ ] 필수 기억사항 관리 UI
- [ ] chat_latest.md 자동 초기화 (요약 후)
- [ ] 컨텍스트 윈도우 관리 (토큰 추적)

### Phase 4 — 지능형 컨텍스트 시스템 ✨ (신규)
- [ ] DB 마이그레이션 V2 (story_summaries, character_events, character_states, scenario_settings)
- [ ] **state 도메인**: 캐릭터 이벤트/상태 CRUD (소지품, 위치, 스케줄, 관계, 능력, 상태이상)
- [ ] **setting 도메인**: 시나리오별 설정 CRUD (출력 형식, 예시 장면, 분량 규칙, 대화 형식)
- [ ] **다단계 요약 시스템**: L1(10턴) → L2(30턴) → L3(100턴) DB 저장
- [ ] **context 도메인**: Haiku 경량 AI로 활성 캐릭터 판별 (장소+시간+관계 기반)
- [ ] **AI 멀티 모델**: ClaudeService에 Opus/Sonnet/Haiku 3종 지원
- [ ] **PromptAssembler 고도화**: 파일 + DB(이벤트/상태/설정) 통합 조립
- [ ] **응답 후 자동 상태 추출**: AI 응답에서 이벤트/상태 변화 감지 → DB 자동 갱신
- [ ] 테스트: state/setting/context 각 도메인 + 통합 테스트

### Phase 5 — 프론트엔드 UI
- [ ] 시나리오 관리 페이지 (생성/목록/삭제)
- [ ] 문서 편집 페이지 (세계관/캐릭터/주인공)
- [ ] 채팅 페이지 (SSE 수신, 감정 아이콘, 이탤릭 행동 묘사)
- [ ] 설정 페이지 (출력 형식, 예시 장면, 분량, 대화 형식 편집)
- [ ] 캐릭터 상태 대시보드 (소지품, 위치, 관계 현황)
- [ ] 필수 기억사항 편집 UI
- [ ] 스토리 요약 뷰어 (L1/L2/L3)

### Phase 6 — 확장 (선택)
- [ ] 캐릭터 프로필 이미지
- [ ] 스토리 분기 시스템
- [ ] 대화 내보내기 (마크다운, TXT)
- [ ] 캐릭터/시나리오 프리셋
- [ ] "한 문장으로 생성" 기능 (AI가 세계관+캐릭터 자동 생성)
- [ ] 다크 모드 / 테마
- [ ] 모바일 반응형 UI

## 현재 진행 상황

### 완료
- [x] Phase 1 — 기반 구축 + 문서 관리 (62 테스트 통과)
- [x] Phase 2 — 채팅 핵심 (Claude API, SSE, 파싱)
- [x] Phase 3 — 기억 시스템 (10턴 요약, 필수기억)

### 다음 단계
1. V2 DB 마이그레이션 (story_summaries, character_events, character_states, scenario_settings)
2. state 도메인 구현 (캐릭터 이벤트/상태 CRUD + 테스트)
3. setting 도메인 구현 (시나리오 설정 CRUD + 테스트)
4. AI 멀티 모델 지원 (Opus/Sonnet/Haiku)
5. context 도메인 구현 (Haiku 기반 활성 캐릭터 판별 + 테스트)
6. 다단계 요약 (L1→L2→L3) + DB 저장
7. PromptAssembler 고도화 (파일+DB 통합)
8. 응답 후 자동 상태 추출
9. 프론트엔드 UI
