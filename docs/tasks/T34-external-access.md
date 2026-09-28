# T34 외부 공개 (정적 서빙 + 로그인 대입 제한 + 터널)

- **상태**: DONE
- **웨이브**: 8
- **의존**: T31, T32
- **브랜치**: `task/T34-external-access`
- **마이그레이션**: 없음
- **결정**: D37

## 목표
집 밖에서도 접속할 수 있게 한다. 인증은 지금처럼 **비밀번호 하나**를 유지한다.

## 열기 전에 막아야 했던 것
| 항목 | 열기 전 상태 | 문제 |
|---|---|---|
| 전송 구간 | 평문 HTTP | 비밀번호가 그대로 흘러간다 |
| 로그인 시도 | 횟수 제한 없음 | 무제한 대입이 가능하다 |
| 토큰 서명 키 | 짧고 추측 가능한 값 | **맞히면 비밀번호 없이 토큰을 위조해 들어온다** |
| 프론트 | Vite **개발** 서버 | HMR·소스맵 노출. 개발 서버를 인터넷에 두면 안 된다 |

뚫렸을 때 잃는 것이 데이터만이 아니다. 채팅 엔드포인트가 **로컬에서 Claude CLI를 실행**하므로 침입자가 사용량을 태울 수 있다.

## 범위
- 신규: `global/config/WebAppConfig.kt`(+ `WebAppProperties`), `global/auth/LoginRateLimiter.kt`
- `global/auth/AuthController.kt`
- `crack-backend/src/main/resources/application.yml.example`
- 신규 테스트: `LoginRateLimiterTest.kt`

## 구현 내용

### 1. 백엔드가 프론트 빌드를 서빙한다 (`crack.web.dist-path`)
- 값이 있으면 그 폴더를 `/**`로 서빙하고, 없는 경로는 `index.html`로 폴백한다(React Router 새로고침 대응)
- **`/api`로 시작하는 경로는 폴백하지 않는다.** 없는 API는 404·401이어야 한다
- 루트(`/`)는 정적 리소스 경로가 빈 문자열이라 리졸버까지 오지 않는다. `addViewControllers`로 `forward:/index.html`을 따로 건다
- 값이 비어 있으면(기본) 서빙하지 않는다 — **로컬 개발은 지금처럼 Vite 개발 서버를 쓴다**

공개 포트가 하나(:8082)로 줄고 같은 출처라 CORS가 필요 없어진다.

### 2. 로그인 대입 제한 (`LoginRateLimiter`)
- **IP별이 아니라 전역**으로 센다. 터널 뒤에서는 모든 요청의 원격 주소가 루프백이라 IP별 계산이 의미가 없고, 전달 헤더는 위조할 수 있다
- 5회까지는 봐준다(오타 여유). 그 뒤로는 실패마다 잠금이 2배(30초 → 1분 → 2분 …), 상한 15분
- **잠긴 동안에는 비밀번호가 맞아도 429를 준다.** 맞는지 여부를 알려 주지 않기 위해서다
- 성공하면 초기화. 메모리에만 두고 재시작하면 풀린다

### 3. 비밀값 교체
`crack.auth.password`와 `crack.auth.token-secret`을 긴 랜덤 값으로 바꿨다. **값은 문서·명세·로그 어디에도 적지 않는다**(CLAUDE.md 규칙 8). 로컬 `application.yml`에만 있다.

### 4. 터널
Cloudflare 빠른 터널을 쓴다. 계정이 필요 없고 HTTPS가 붙는다.

```bash
cloudflared tunnel --url http://localhost:8082 --no-autoupdate
```

**끌 때마다 주소가 바뀐다.** 고정 주소가 필요해지면 Cloudflare 계정 + 도메인으로 명명 터널을 만든다.

## 외부 공개로 띄우는 순서
```bash
cd crack-frontend && npm run build
cd ../crack-backend && ./gradlew bootJar
java -jar build/libs/crack-backend-0.0.1-SNAPSHOT.jar \
  --crack.data-path=<저장소>/data \
  --crack.web.dist-path=<저장소>/crack-frontend/dist
cloudflared tunnel --url http://localhost:8082 --no-autoupdate
```

## 완료 조건
- [x] `/`와 `/chat/17` 같은 SPA 경로가 백엔드 포트에서 200
- [x] `/api/**`는 폴백하지 않는다(무인증 401)
- [x] 5회까지 401, 6회째부터 429. 잠긴 동안 올바른 비밀번호도 429
- [x] 옛 비밀번호 401, 새 비밀번호 200
- [x] 터널 주소에서 HTTPS로 화면이 뜨고 API는 무인증 401
- [x] 백엔드 테스트 556개 통과, 프론트 build·lint 통과
- [x] `dist-path`가 비면 기존 동작(Vite 개발 서버) 그대로

## 작업 로그

### 2026-09-28
사용자가 "외부에서도 접근 가능하게, 인증은 지금처럼 비밀번호로"라고 요청했다.

**바로 터널을 붙이지 않고 방어 상태부터 확인했다.** 네 가지가 걸렸고(위 표), 그중 **토큰 서명 키**가 제일 위험했다. `AuthTokens`는 `HMAC-SHA256(payload, tokenSecret)`으로 토큰을 만드는데, 이 키를 맞히면 **비밀번호를 전혀 모르고도 유효한 토큰을 찍어낼 수 있다.** 저장소에 커밋된 기본값(`crack-default-secret-change-me`)도 같은 문제라, 설정을 덮지 않고 띄운 사람은 인증이 없는 것과 같다.

**Vite 개발 서버를 노출하지 않으려고 서빙 주체를 백엔드로 옮겼다.** 처음에는 `vite preview`를 생각했지만, `server.proxy`가 preview에 적용되지 않아 프록시 설정을 따로 둬야 하고 공개 포트도 둘이 된다. 백엔드가 `dist`를 직접 주면 포트가 하나로 줄고 CORS도 사라진다.

**루트 경로에서 한 번 막혔다.** `addResourceHandler("/**")`에 SPA 폴백 리졸버를 달았는데 `GET /`가 404였다. 루트는 정적 리소스 경로가 **빈 문자열**이라 리졸버 체인까지 오지 않는다. `addViewControllers`로 `/` → `forward:/index.html`을 따로 걸어 해결했다.

**대입 제한을 전역으로 한 이유.** 터널 뒤에서는 모든 요청의 `remoteAddr`가 `127.0.0.1`이라 IP별 계산이 무의미하고, `X-Forwarded-For`는 위조할 수 있다. 1인용 앱이므로 전역 카운터가 단순하고 우회 불가능하다. **대신 공격자가 주인을 잠글 수 있다**(가벼운 DoS). 20자 랜덤 비밀번호에 대입은 애초에 무의미하므로, 뚫리는 것보다 잠깐 기다리는 쪽을 택했다.

**확인:** 백엔드 556개 통과(신규 6개), 프론트 build·lint 통과. 터널 주소에서 HTTPS/2로 화면이 뜨고 `/api/scenarios`는 무인증 401.

**다음 작업자에게:**
- 빠른 터널은 **끌 때마다 주소가 바뀐다.** 고정 주소가 필요하면 Cloudflare 계정 + 도메인으로 명명 터널을 만든다
- `dist`는 빌드 결과라 **프론트를 고치면 `npm run build`를 다시 해야 외부에 반영된다.** 로컬 개발(Vite)과 달리 자동 반영이 아니다
- 대입 제한은 메모리다. 재시작하면 잠금이 풀린다
- **비밀값을 문서에 적지 않는다.** 대화로만 전달한다
