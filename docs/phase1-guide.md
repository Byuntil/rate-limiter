# 1차 시나리오: 단일 서버 + 메모리 기반 Fixed Window

## 목표

ConcurrentHashMap을 사용하여 메모리 기반 Fixed Window Rate Limiter를 구현한다.
서버 1대 환경에서 요청 수를 제한하는 기본 동작을 완성한다.

---

## 구현할 것

### 1. FixedWindowRateLimiter

`RateLimiter` 인터페이스를 구현한다.

**위치**: `ratelimit/algorithm/FixedWindowRateLimiter.java`

**핵심 로직**:
- key = 요청자 식별값 + 현재 윈도우 시간 (예: `user:123:202603191430`)
- 현재 분(minute) 단위로 윈도우를 구분한다
- 요청이 오면 해당 key의 카운트를 1 증가시킨다
- 카운트가 limit 이하이면 허용, 초과하면 차단

**자료구조**:
```
ConcurrentHashMap<String, AtomicInteger>
  key: "user:123:202603191430"
  value: 현재 요청 횟수
```

**고려할 점**:
- 윈도우 key 생성 시 시간 포맷: `yyyyMMddHHmm` (분 단위)
- 지난 윈도우의 entry는 메모리 누수를 유발하므로 정리 필요
- `@Scheduled`로 주기적 정리하거나, 조회 시 만료 체크

**반환값 (`RateLimitResult`)**:
- `allowed`: 허용 여부
- `limit`: 최대 허용 횟수
- `remaining`: 남은 횟수
- `resetAt`: 윈도우 리셋 시각 (epoch seconds)

---

### 2. RateLimitInterceptor

HTTP 요청을 가로채서 rate limit을 적용하는 인터셉터.

**위치**: `ratelimit/RateLimitInterceptor.java`

**동작 흐름**:
```
1. ClientKeyResolver로 요청자 key 추출
2. 관리자인지 확인 → 관리자면 통과
3. 로그인/비로그인에 따라 limit 값 결정 (100 / 30)
4. RateLimiter.tryAcquire(key, limit, windowSeconds) 호출
5. 허용 → 응답 헤더에 rate limit 정보 추가, 통과
6. 차단 → 429 응답 반환
```

**응답 헤더** (허용 시):
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1742392800
```

**차단 응답** (429):
```json
{"code": "RATE_LIMIT_EXCEEDED", "message": "요청 한도를 초과했습니다."}
```

---

### 3. WebMvcConfig

인터셉터를 등록하는 설정 클래스.

**위치**: `config/WebMvcConfig.java`

- `WebMvcConfigurer` 구현
- `addInterceptors`에서 `RateLimitInterceptor`를 `/**` 경로에 등록
- H2 콘솔 경로(`/h2-console/**`)는 제외

---

## 구현 순서

```
1. FixedWindowRateLimiter 구현
2. RateLimitInterceptor 구현
3. WebMvcConfig로 인터셉터 등록
4. 수동 테스트로 동작 확인
```

---

## 수동 테스트

```bash
# 게시글 조회 (비로그인 - IP 기준 30회 제한)
curl -v http://localhost:8080/posts/1

# 게시글 조회 (로그인 사용자 - 100회 제한)
curl -v -H "X-User-Id: 123" http://localhost:8080/posts/1

# 관리자 (제한 없음)
curl -v -H "X-User-Id: 1" -H "X-User-Role: ADMIN" http://localhost:8080/posts/1

# 제한 초과 테스트 (비로그인 31회 반복)
for i in $(seq 1 31); do
  echo "--- Request $i ---"
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/posts/1
  echo
done
```

---

## 이 단계의 한계 (2차 시나리오에서 확인할 것)

- 동시 요청 시 `check-then-act` 경쟁 조건이 발생할 수 있다
- AtomicInteger로 카운트는 원자적이지만, "조회 후 판단" 로직 전체가 원자적이지 않을 수 있다
- 서버 재시작 시 카운트가 초기화된다
