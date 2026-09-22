# T19 인물 상태 패널

- **상태**: TODO
- **웨이브**: 6
- **의존**: T14, T15
- **브랜치**: `task/T19-status-panel`
- **마이그레이션**: 없음
- **결정**: D13 (기록 때만 갱신. 엔딩·실시간 수치 없음)

## 목표
기억 문서를 파싱해서 인물의 관계·소지품·기술과 동행·위치를 한눈에 보여준다.

## 범위
- 신규 `crack-backend/src/main/kotlin/com/crack/status/**` (`GET /api/stories/{id}/status`)
- 신규 `crack-frontend/src/components/panels/status/**`, `src/api/status.ts`, `src/pages/ChatPage.tsx`(탭 등록만)
- 관련 테스트

## 구현 내용
1. **백엔드:** `StoryState` + 주인공 `## 변화 기록` + 인물별 `## 기억`의 하위 섹션을 구조화된 JSON으로 만든다(T05 파서 사용). 동행 인물을 앞에 두고, 기억이 있는 인물만 넣는다.
2. **프론트 "상태" 탭**
   - 위쪽: 현재 위치, 시간, 동행
   - 주인공 카드: 관계, 스탯·기술, 소지품, 신체
   - 인물 카드: 관계, 최근 사건 3개, 소지품
3. **갱신 시점:** 기억 기록이 끝났을 때만 갱신한다(T15 뱃지 상태 변화 감지). 매 턴 조회하지 않는다.

## 완료 조건
- [ ] `./gradlew test`(파서 → JSON), `npm run build`, `npm run lint` 통과
- [ ] 상태 `REVIEW` + 작업 로그 + PR

## 작업 로그
