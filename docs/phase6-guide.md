# 6차 시나리오: Sliding Window Counter로 경계값 문제 개선

## 목표

5차에서 Fixed Window의 경계값 문제를 재현하여, 윈도우 경계에서 limit의 2배가 허용되는 구조적 한계를 확인했다.
이 단계에서는 **Sliding Window Counter** 알고리즘을 구현하여 경계값 문제를 개선한다.

---

## 현재 문제 (5차에서 확인한 것)

```
제한: 1분 100회

12:00:59에 100회 → 윈도우 1 (12:00분대) → 전부 허용
12:01:00에 100회 → 윈도우 2 (12:01분대) → 전부 허용

결과: 1~2초 사이에 200회 통과
```

Fixed Window는 윈도우가 바뀌면 카운터가 0에서 다시 시작하기 때문에, **이전 윈도우의 사용량을 전혀 고려하지 않는다.**

---

## 알고리즘 선택: 왜 Sliding Window Counter인가

### 후보 비교

| 구분 | Sliding Window Log | Sliding Window Counter | Token Bucket |
|------|-------------------|----------------------|-------------|
| 경계 burst | 완전 해결 | 근사치로 크게 완화 | burst 허용 (토큰 소진 후 차단) |
| 구현 복잡도 | 높음 (Sorted Set) | 중간 (key 2개 + 가중 평균) | 중간 (토큰 충전 로직) |
| 저장 비용 | 요청마다 타임스탬프 저장 | key 2개 / 윈도우 | key 1개 |
| Redis 명령 수 | ZADD + ZREMRANGEBYSCORE + ZCARD | GET 2회 + INCR 1회 | GET + SET (or Lua) |
| 정확도 | 가장 높음 | 높음 (가중 평균 근사) | 높음 (다른 모델) |
| 메모리 효율 | 낮음 (요청 수에 비례) | 높음 (Fixed Window와 동일) | 높음 |

### Sliding Window Counter를 선택한 이유

1. **Fixed Window 대비 최소 변경**: key 구조가 동일하고, 기존 INCR 기반 로직을 재사용한다
2. **저장 비용 효율적**: Sliding Window Log는 요청마다 타임스탬프를 저장하므로 limit=10000이면 10000개의 엔트리가 필요하다. Counter는 key 2개면 충분하다
3. **경계 문제를 충분히 완화**: 완벽하지는 않지만, limit x 2 burst를 사실상 불가능하게 만든다
4. **Token Bucket과의 차이**: Token Bucket은 "시간당 요청 수 제한"이 아니라 "토큰 충전/소비" 모델이다. 현재 프로젝트의 정책("1분에 N회")과 Sliding Window Counter가 더 직관적으로 맞는다

> Token Bucket은 burst를 **허용**하는 것이 장점이지만, 이 프로젝트에서는 burst를 **억제**하는 것이 목표다.

---

## Sliding Window Counter 동작 원리

### 핵심 아이디어

현재 윈도우의 카운트만 보는 대신, **이전 윈도우의 카운트를 경과 비율로 감소시켜 함께 반영**한다.

```
가중 평균 = 이전 윈도우 카운트 × (1 - 경과 비율) + 현재 윈도우 카운트
```

### 구체적 예시

```
현재 시점: 12:01:15 (현재 윈도우의 25% 지점)

이전 윈도우 (12:00) 카운트: 84
현재 윈도우 (12:01) 카운트: 36

경과 비율 = 15초 / 60초 = 0.25

가중 평균 = 84 × (1 - 0.25) + 36
         = 84 × 0.75 + 36
         = 63 + 36
         = 99

→ limit=100이므로 1회 더 허용
```

### 경계에서의 동작 (5차 문제 시나리오)

```
시점: 12:01:00 (현재 윈도우의 0% 지점, 막 시작)

이전 윈도우 (12:00) 카운트: 100
현재 윈도우 (12:01) 카운트: 0

경과 비율 = 0초 / 60초 = 0.0

가중 평균 = 100 × (1 - 0.0) + 0
         = 100 × 1.0 + 0
         = 100

→ limit=100이므로 추가 요청 차단!
```

Fixed Window에서는 200회가 허용되던 경계 시나리오가, Sliding Window Counter에서는 **100회에서 정확히 차단**된다.

### 시각화

```
요청 허용량
         Fixed Window                    Sliding Window Counter
  200 ┤        ╭─╮                200 ┤
      │        │ │ burst               │
  100 ┤────────╯ ╰──────          100 ┤──────────────────────
      │                                │  ↑ 경계에서도 limit 유지
    0 ┤                              0 ┤
      └──────────────────→             └──────────────────────→
      12:00   12:01  12:02             12:00   12:01   12:02
```

---

## 구현할 것

### 1. RedisSlidingWindowCounterRateLimiter 구현

**위치**: `ratelimit/algorithm/RedisSlidingWindowCounterRateLimiter.java`

```java
@Slf4j
@Component
@Profile("phase6")
public class RedisSlidingWindowCounterRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisSlidingWindowCounterRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        LocalDateTime now = LocalDateTime.now();
        int currentSecond = now.getSecond();

        // 현재 윈도우와 이전 윈도우의 key
        String currentWindowKey = "rate_limit:" + key + ":" +
                now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String previousWindowKey = "rate_limit:" + key + ":" +
                now.minusMinutes(1).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));

        // 이전 윈도우 카운트 조회
        String prevCountStr = redisTemplate.opsForValue().get(previousWindowKey);
        long previousCount = (prevCountStr != null) ? Long.parseLong(prevCountStr) : 0;

        // 현재 윈도우 카운트 조회
        String currCountStr = redisTemplate.opsForValue().get(currentWindowKey);
        long currentCount = (currCountStr != null) ? Long.parseLong(currCountStr) : 0;

        // 가중 평균 계산
        double elapsedRatio = currentSecond / (double) windowSeconds;
        double weightedCount = previousCount * (1 - elapsedRatio) + currentCount;

        if (weightedCount >= limit) {
            long resetAt = now.withSecond(0).withNano(0)
                    .plusMinutes(1)
                    .toEpochSecond(ZoneOffset.UTC);
            return RateLimitResult.blocked(limit, resetAt);
        }

        // 허용: 현재 윈도우 카운트 증가
        Long newCount = redisTemplate.opsForValue().increment(currentWindowKey);
        if (newCount != null && newCount == 1) {
            redisTemplate.expire(currentWindowKey, Duration.ofSeconds(windowSeconds * 2));
        }

        long resetAt = now.withSecond(0).withNano(0)
                .plusMinutes(1)
                .toEpochSecond(ZoneOffset.UTC);

        int remaining = (int) Math.max(0, limit - (weightedCount + 1));
        return RateLimitResult.allowed(limit, remaining, resetAt);
    }
}
```

**왜 GET → 계산 → INCR 순서인가**:
- 먼저 가중 평균을 계산해서 허용 여부를 판단한 뒤, 허용된 경우에만 INCR을 실행한다
- 차단된 요청은 카운트에 포함되지 않아야 하므로, "확인 후 증가" 순서가 맞다

**왜 TTL을 `windowSeconds * 2`로 설정하는가**:
- 현재 윈도우의 key는 **다음 윈도우에서 이전 윈도우로 참조**된다
- 1분 윈도우라면, 현재 윈도우 key가 다음 분까지 살아있어야 한다
- 2배로 설정하면 충분한 여유가 있다

---

### 2. Lua 스크립트로 원자성 확보 (선택적 개선)

GET → 계산 → INCR 사이에 다른 요청이 끼어들 수 있다. 정확한 동시성 제어를 위해 Lua 스크립트를 사용할 수 있다.

**위치**: `resources/scripts/sliding-window-counter.lua`

```lua
local current_key = KEYS[1]
local previous_key = KEYS[2]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local current_second = tonumber(ARGV[3])

local previous_count = tonumber(redis.call('GET', previous_key) or "0")
local current_count = tonumber(redis.call('GET', current_key) or "0")

local elapsed_ratio = current_second / window
local weighted_count = previous_count * (1 - elapsed_ratio) + current_count

if weighted_count >= limit then
    return -1
end

local new_count = redis.call('INCR', current_key)
if new_count == 1 then
    redis.call('EXPIRE', current_key, window * 2)
end

return new_count
```

Lua 스크립트는 Redis에서 원자적으로 실행되므로, GET과 INCR 사이에 다른 요청이 끼어들 수 없다.

---

### 3. 프로필 설정

**위치**: `application-phase6.yml`

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

Phase 4와 동일한 Redis 설정을 사용하되, 프로필명만 `phase6`으로 분리한다.

---

### 4. 테스트

#### 기본 동작 테스트

**위치**: `test/.../algorithm/RedisSlidingWindowCounterRateLimiterTest.java`

```java
@Test
@DisplayName("제한 이하 요청은 허용된다")
void allow_under_limit() {
    RateLimitResult result = rateLimiter.tryAcquire("user:1", 100, 60);
    assertThat(result.allowed()).isTrue();
}

@Test
@DisplayName("제한 초과 요청은 차단된다")
void block_over_limit() {
    int limit = 5;
    for (int i = 0; i < limit; i++) {
        rateLimiter.tryAcquire("user:2", limit, 60);
    }
    RateLimitResult result = rateLimiter.tryAcquire("user:2", limit, 60);
    assertThat(result.allowed()).isFalse();
}
```

#### 경계값 개선 검증 테스트 (핵심)

5차에서 재현한 경계 문제가 **개선되었는지** 검증한다.

```java
@Test
@DisplayName("경계에서 이전 윈도우 사용량을 반영하여 burst를 억제한다")
void boundary_burst_suppressed() {
    int limit = 100;

    // 12:30:59 (윈도우 1 끝) — Clock 주입으로 시간 고정
    Clock clock1 = Clock.fixed(
        LocalDateTime.of(2026, 3, 22, 12, 30, 59).toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC
    );

    // 윈도우 1에서 100회 사용 (TestableRedisSlidingWindowCounter 사용)
    for (int i = 0; i < limit; i++) {
        limiter1.tryAcquire("user:boundary", limit, 60);
    }

    // 12:31:00 (윈도우 2 시작, 경과 비율 = 0/60 = 0.0)
    Clock clock2 = Clock.fixed(
        LocalDateTime.of(2026, 3, 22, 12, 31, 0).toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC
    );

    // 가중 평균 = 100 × (1 - 0.0) + 0 = 100 → 이미 limit에 도달
    RateLimitResult result = limiter2.tryAcquire("user:boundary", limit, 60);
    assertThat(result.allowed()).isFalse();  // 차단!
}
```

**5차와의 핵심 차이**: 같은 시나리오에서 Fixed Window는 200회를 허용했지만, Sliding Window Counter는 100회에서 차단한다.

#### Fixed Window vs Sliding Window Counter 비교 테스트

```java
@Test
@DisplayName("같은 경계 시나리오에서 Fixed Window와 Sliding Window Counter 결과 비교")
void compare_fixed_vs_sliding_at_boundary() {
    int limit = 100;

    // Fixed Window: 경계에서 200회 허용 (5차에서 확인)
    // Sliding Window Counter: 경계에서 100회만 허용

    // ... 두 알고리즘의 결과를 나란히 출력

    System.out.println("[Fixed Window] 경계 1초간 허용: 200");
    System.out.println("[Sliding Window Counter] 경계 1초간 허용: " + slidingAllowed);

    assertThat(slidingAllowed).isLessThanOrEqualTo(limit);
}
```

#### 동시성 테스트

```java
@Test
@DisplayName("200개 동시 요청에서 limit을 지킨다")
void concurrent_requests_respect_limit() {
    int limit = 100;
    int totalRequests = 200;
    ExecutorService executor = Executors.newFixedThreadPool(20);

    AtomicInteger allowed = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(totalRequests);

    for (int i = 0; i < totalRequests; i++) {
        executor.submit(() -> {
            try {
                if (rateLimiter.tryAcquire("user:concurrent", limit, 60).allowed()) {
                    allowed.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await();
    // 가중 평균 방식이므로 정확히 100이 아닐 수 있지만, limit 근처여야 한다
    assertThat(allowed.get()).isBetween(limit - 5, limit + 5);
}
```

**왜 정확히 limit이 아닌가**: GET → 계산 → INCR이 원자적이지 않으면, 동시 요청에서 약간의 오차가 발생할 수 있다. Lua 스크립트를 사용하면 정확해진다.

---

## 구현 순서

```
1. application-phase6.yml 생성
2. RedisSlidingWindowCounterRateLimiter 구현 (@Profile("phase6"))
3. 기본 동작 테스트 작성 (허용/차단)
4. 경계값 개선 검증 테스트 작성 (5차와 동일 시나리오)
5. Fixed Window vs Sliding Window Counter 비교 테스트
6. 동시성 테스트
7. (선택) Lua 스크립트로 원자성 개선
```

---

## 가중 평균의 한계와 정확도

### 근사치인 이유

Sliding Window Counter는 이전 윈도우의 요청이 **균등하게 분포되었다고 가정**한다.

```
실제 분포: 이전 윈도우의 마지막 10초에 100회 몰림
가중 평균 가정: 이전 윈도우의 60초에 걸쳐 균등하게 분포

→ 실제로는 최근 10초에 100회가 있지만,
  가중 평균은 경과 비율에 따라 이전 카운트를 감소시키므로
  실제보다 적게 카운트할 수 있다
```

### 그럼에도 충분한 이유

| 시나리오 | Fixed Window | Sliding Window Counter | 오차 |
|---------|-------------|----------------------|------|
| 경계 burst (최악) | 200회 허용 | 100~110회 허용 | 0~10% |
| 일반적 사용 | 정확 | 정확 | 0% |
| 이전 윈도우 50% 사용 | 다음 윈도우에서 100회 허용 | 다음 윈도우 시작 시 약 50회 허용 | 근사 |

최악의 경우에도 오차가 10% 이내이며, Fixed Window의 100% 오차(2배 허용)에 비하면 크게 개선된다.

---

## Phase 4 (Fixed Window) vs Phase 6 (Sliding Window Counter) 비교

| 구분 | Fixed Window (Phase 4) | Sliding Window Counter (Phase 6) |
|------|----------------------|--------------------------------|
| Redis key 수 | 1개 / 윈도우 | 2개 참조 (현재 + 이전) |
| Redis 명령 수 | INCR 1회 | GET 2회 + INCR 1회 (또는 Lua 1회) |
| 경계 burst | limit × 2 허용 | limit 근처에서 차단 |
| 구현 복잡도 | 낮음 | 중간 |
| 정확도 | 경계에서 낮음 | 높음 (근사치) |
| 네트워크 비용 | Redis 왕복 1회 | Redis 왕복 2~3회 (또는 Lua 1회) |

---

## 이 단계의 한계

| 한계 | 설명 | 개선 방안 |
|------|------|----------|
| **근사치** | 이전 윈도우 요청의 균등 분포를 가정 | Sliding Window Log로 전환 (저장 비용 증가) |
| **GET→INCR 비원자성** | 동시 요청에서 약간의 오차 가능 | Lua 스크립트로 원자화 |
| **Redis 명령 증가** | Fixed Window보다 Redis 호출이 많다 | Lua 스크립트로 1회 호출로 줄임 |
| **Redis 장애 대응 없음** | Redis 다운 시 rate limiting 불가 | fallback 정책 (로컬 메모리 limiter 등) |
