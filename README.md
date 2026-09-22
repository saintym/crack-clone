# crack-clone

뤼튼 **크랙(Crack)** 을 재현한 개인 취미 프로젝트 — **AI 캐릭터 채팅 앱**입니다.
캐릭터·세계관·시나리오를 설정하면 Claude 기반 AI가 인캐릭터로 응답하고, 대화 맥락을 문서 기반 기억 시스템으로 유지합니다.

## 문서

- [docs/HANDOFF.md](docs/HANDOFF.md) — 현재 상태와 인수인계 (먼저 읽기)
- [Plan-roadmap.md](Plan-roadmap.md) — 결정 사항과 로드맵 · [Plan-crack-gap.md](Plan-crack-gap.md) — 원작 기능 격차 분석
- [docs/DESIGN.md](docs/DESIGN.md) — 기술 설계(작업 간 계약) · [docs/tasks/](docs/tasks/README.md) — 작업 명세와 기록
- [docs/PARALLEL.md](docs/PARALLEL.md) — 병렬 작업 운영 · [docs/ORCHESTRATION-LOG.md](docs/ORCHESTRATION-LOG.md) — 구현 중 겪은 오류 기록
- [bugs/](bugs/README.md) — 버그 트래커 · [blog/](blog/README.md) — 개발기

## 핵심 특징

- **문서(마크다운) 기반 관리** — 세계관/등장인물/주인공/필수기억을 파일로 관리
- **기억(memory) 시스템** — 대화를 10턴 단위로 요약해 문서로 저장하고, AI가 이를 읽어 장기 맥락 유지
- **SSE 스트리밍** — AI 응답을 실시간으로 전달
- **다중 캐릭터 시나리오** — 하나의 시나리오에 여러 캐릭터 참여
- **채팅 히스토리 영속화** — 대화 저장 및 이어하기, 방대해지면 DB 아카이빙

## 기술 스택

| 구성 | 기술 |
|---|---|
| 프론트엔드 | React (TypeScript / Vite) |
| 백엔드 | Spring Boot (Kotlin), DDD, QueryDSL |
| DB / 마이그레이션 | PostgreSQL, Flyway |
| AI | Claude API (Anthropic SDK) |
| 실시간 | SSE (Server-Sent Events) |

## 실행

```bash
# 백엔드 설정
cd crack-backend/src/main/resources
cp application.yml.example application.yml   # 값 채우기 (DB/Claude 등)

# 백엔드
cd crack-backend && ./gradlew bootRun        # :8082

# 프론트엔드
cd crack-frontend && npm install && npm run dev
```

환경변수: `DB_PASSWORD`, `CLAUDE_API_KEY`, `CRACK_PASSWORD`, `CRACK_TOKEN_SECRET`

> 개인 학습용 클론 프로젝트입니다. 원작의 저작권은 원 제작사에 있습니다.
