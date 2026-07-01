# Crack Clone 개발기

AI 캐릭터 챗 앱 "Crack"을 직접 클론하며 한 설계 고민과 삽질의 기록. 4부작.

| 편 | 제목 | 다루는 내용 |
| --- | --- | --- |
| 1 | [프로젝트 개요 & 아키텍처](./01-architecture.md) | 시나리오/스토리 분리 설계, 파일 vs DB, AI 프로바이더 추상화, 기술 선택 이유 |
| 2 | [Claude를 AI 엔진으로: CLI 프로바이더와 SSE 스트리밍](./02-claude-sse-streaming.md) | Claude Code CLI 프로세스 호출, stream-json, SSE 줄바꿈 버그 |
| 3 | [채팅 UX와 프롬프트 엔지니어링](./03-chat-ux-prompt.md) | react-markdown, 재생성/이어하기/분기/수정, 출력 품질 개선 |
| 4 | [AI가 긴 스토리를 기억하는 법](./04-memory-and-state.md) | 계층적 자동 요약(L1/L2/L3), 모델 티어, 캐릭터 상태 추적 |

## 스택

- **백엔드** Spring Boot 3.4.4 + Kotlin 1.9.25 / PostgreSQL 15 (Flyway)
- **프론트** React 19 + TypeScript + Vite + Tailwind CSS v4
- **AI** Claude (Claude Code CLI / Anthropic API)
