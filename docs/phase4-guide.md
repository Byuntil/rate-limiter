# 4차 시나리오: Redis 기반 Fixed Window

## 목표

3차에서 확인한 다중 서버 환경의 한계를 **Redis를 도입하여 해결**한다.
모든 서버가 하나의 Redis에 카운터를 공유함으로써, 서버가 몇 대든 동일한 rate limit 정책이 적용되도록 한다.

---

## 현재 문제 (3차에서 확인한 것)

```
Server A (Map: 40) + Server B (Map: 40) + Server C (Map: 40) = 120 허용
→ limit=100인데 120회 통과
```

원인: 각 서버가 독립된 메모리에 카운터를 저장하므로 전체 제한을 보장하지 못한다.

---

## 해결 구조

```
Server A ──┐
Server B ──┼──→ Redis (INCR rate_limit:user:123:202603211430)
Server C ──┘
                 ↓
           count = 101 → 차단
```

모든 서버가 Redis의 **같은 key**에 대해 `INCR`을 수행하므로, 어디서 요청하든 전체 카운트가 정확하다.

---

## 구현할 것

### 1. Redis 설정 활성화

현재 `application.yml`에서 Redis 자동 설정을 비활성화하고 있다. Phase 4에서는 이를 제거하고 Redis를 활성화한다.

**변경 사항** (`application.yml`):
```yaml
spring:
  profiles:
    active: phase4
  data:
    redis:
      host: localhost
      port: 6379
  # autoconfigure.exclude 제거 (Redis 활성화)
```

Phase 1~3 프로필에서는 Redis 없이 동작해야 하므로, 프로필별로 설정을 분리한다.

```yaml
# application-phase1.yml / application-phase2.yml
spring:
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration

# application-phase4.yml
# Redis 자동 설정 활성화 (exclude 없음)
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

---

### 2. RedisFixedWindowRateLimiter 구현

**위치**: `ratelimit/algorithm/RedisFixedWindowRateLimiter.java`

**핵심 로직**:

```java
@Slf4j
@Component
@Profile("phase4")
public class RedisFixedWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String redisKey = "rate_limit:" + key + ":" + now;

        Long count = redisTemplate.opsForValue().increment(redisKey);

        // 첫 요청이면 TTL 설정
        if (count != null && count == 1) {
            redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
        }

        long resetAt = LocalDateTime.now()
                .withSecond(0).withNano(0)
                .plusMinutes(1)
                .toEpochSecond(ZoneOffset.UTC);

        if (count != null && count <= limit) {
            return RateLimitResult.allowed(limit, (int) (limit - count), resetAt);
        } else {
            return RateLimitResult.blocked(limit, resetAt);
        }
    }
}
```

**왜 Redis INCR인가**:
- `INCR`은 Redis에서 원자적(atomic) 연산이다
- 별도의 락 없이 여러 서버에서 동시에 호출해도 정확한 카운트를 보장한다
- key가 없으면 0에서 시작하여 1을 반환한다 (별도 초기화 불필요)

**왜 `increment` + `expire` 분리인가**:

`INCR`과 `EXPIRE`를 한 번에 실행하려면 Lua 스크립트가 필요하다. 하지만 이 구현에서는 분리해도 문제가 없다.

```
INCR rate_limit:user:123:202603211430   → count = 1
EXPIRE rate_limit:user:123:202603211430 60
```

- `count == 1`일 때만 `EXPIRE`를 설정하므로, TTL이 중복 설정되지 않는다
- 만약 `INCR` 후 `EXPIRE` 전에 서버가 죽으면? → key에 TTL이 없는 상태로 남는다
- 이 edge case는 Lua 스크립트로 개선할 수 있지만, 1차 Redis 구현에서는 단순함을 우선한다

---

### 3. Lua 스크립트 방식 (선택적 개선)

`INCR` + `EXPIRE`의 원자성을 보장하려면 Lua 스크립트를 사용할 수 있다.

**위치**: `resources/scripts/rate-limit.lua`

```lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local count = redis.call('INCR', key)

if count == 1 then
    redis.call('EXPIRE', key, window)
end

return count
```

**Java에서 호출**:
```java
private final RedisScript<Long> rateLimitScript = new DefaultRedisScript<>(luaScript, Long.class);

Long count = redisTemplate.execute(rateLimitScript, List.of(redisKey),
    String.valueOf(limit), String.valueOf(windowSeconds));
```

**Lua 스크립트의 장점**:
- `INCR`과 `EXPIRE`가 Redis 서버에서 하나의 명령으로 실행된다
- 네트워크 왕복이 1회로 줄어든다
- 서버 장애 시에도 TTL 누락이 발생하지 않는다

---

### 4. 테스트

#### 단위 테스트 (Embedded Redis 또는 TestContainers)

**위치**: `test/.../algorithm/RedisFixedWindowRateLimiterTest.java`

```java
@Test
@DisplayName("Redis 기반: 제한 횟수 이하의 요청은 허용된다")
void allow_when_under_limit() {
    RateLimitResult result = rateLimiter.tryAcquire("user:1", 5, 60);
    assertThat(result.allowed()).isTrue();
}

@Test
@DisplayName("Redis 기반: 제한 횟수를 초과하면 차단된다")
void block_when_over_limit() {
    int limit = 3;
    for (int i = 0; i < limit; i++) {
        rateLimiter.tryAcquire("user:3", limit, 60);
    }
    RateLimitResult result = rateLimiter.tryAcquire("user:3", limit, 60);
    assertThat(result.allowed()).isFalse();
}
```

#### 다중 서버 시뮬레이션 테스트

**위치**: `test/.../algorithm/RedisFixedWindowRateLimiterMultiServerTest.java`

3차에서 실패했던 것과 같은 시나리오를 Redis 기반으로 재실행한다.

```java
@Test
@DisplayName("Redis 기반: 서버 3대에서 요청해도 전체 limit을 지킨다")
void multi_server_respects_limit_with_redis() {
    int limit = 100;
    int totalRequests = 200;

    // 같은 RedisTemplate을 공유하는 limiter 3개
    // = 서버 3대가 같은 Redis를 바라보는 것과 동일
    RedisFixedWindowRateLimiter[] servers = new RedisFixedWindowRateLimiter[3];
    for (int i = 0; i < 3; i++) {
        servers[i] = new RedisFixedWindowRateLimiter(redisTemplate);
    }

    int totalAllowed = 0;
    for (int i = 0; i < totalRequests; i++) {
        if (servers[i % 3].tryAcquire("user:123", limit, 60).allowed()) {
            totalAllowed++;
        }
    }

    assertThat(totalAllowed).isEqualTo(limit);  // 정확히 100
}
```

**핵심 차이**: 3차에서는 각 인스턴스가 독립된 `ConcurrentHashMap`을 가졌지만, 4차에서는 모든 인스턴스가 **같은 `RedisTemplate`**(= 같은 Redis)을 공유한다.

---

## 구현 순서

```
1. application-phase4.yml 생성 (Redis 활성화)
2. RedisFixedWindowRateLimiter 구현 (@Profile("phase4"))
3. 로컬 Redis 실행 (docker run -p 6379:6379 redis)
4. 기본 단위 테스트 작성 및 실행
5. 다중 서버 시뮬레이션 테스트 → 3차와 동일 조건에서 limit 정확히 지킴 확인
6. 동시성 테스트 → 200개 동시 요청에서도 limit 초과 없음 확인
7. (선택) Lua 스크립트 방식으로 개선
```

---

## 수동 테스트

```bash
# Redis 실행
docker run -d --name redis -p 6379:6379 redis:7

# 프로필 변경 후 서버 시작
# application.yml: spring.profiles.active: phase4

# 동시 200개 요청
seq 1 200 | xargs -P 200 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -H "X-User-Id: 123" http://localhost:8080/posts/1 | sort | uniq -c

# 기대 결과: 200 정확히 100, 429 정확히 100

# Redis에서 카운터 확인
docker exec -it redis redis-cli
> KEYS rate_limit:*
> GET rate_limit:user:123:202603211430
```

---

## Phase 3 vs Phase 4 비교

| 구분 | Phase 3 (메모리) | Phase 4 (Redis) |
|------|-----------------|-----------------|
| 카운터 저장소 | JVM 메모리 (서버별 독립) | Redis (공유) |
| 서버 3대, 200요청 | 200 허용 (limit×2) | 100 허용 (정확) |
| 원자적 증가 | AtomicInteger (JVM 내) | INCR (Redis 서버) |
| 서버 재시작 | 카운트 초기화 | Redis에 유지 |
| TTL/만료 | @Scheduled로 직접 정리 | Redis EXPIRE 자동 처리 |
| 네트워크 비용 | 없음 | Redis 왕복 1회 |

---

## 이 단계의 한계 (5차 시나리오에서 확인할 것)

- **Fixed Window 경계값 문제**: 12:00:59에 100회 + 12:01:00에 100회 → 1초 사이에 200회 허용
- **Redis 장애 시 대응**: Redis가 다운되면 rate limiting이 동작하지 않는다 (fallback 정책 필요)
- **네트워크 지연**: 모든 요청마다 Redis 왕복이 발생하므로 응답 시간이 약간 증가한다
