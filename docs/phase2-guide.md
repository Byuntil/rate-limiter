# 2차 시나리오: 동시성 문제 재현 및 개선

## 목표

1차 구현(FixedWindowRateLimiter)에서 동시 요청 시 limit을 초과한 요청이 허용되는 현상을 **테스트로 재현**하고, 원인을 분석한 뒤 **개선**한다.

---

## 현재 구현의 문제

### 어디서 경쟁 조건이 발생하는가

`FixedWindowRateLimiter.tryAcquire()`의 핵심 코드다.

```java
int count = request.getOrDefault(value, 0) + 1;
request.put(value, count);

if (count <= limit) {
    return RateLimitResult.allowed(...);
} else {
    return RateLimitResult.blocked(...);
}
```

`getOrDefault`(읽기)와 `put`(쓰기)이 분리되어 있다. 단일 스레드에서는 문제없지만, 동시 요청 시 여러 스레드가 **같은 값을 읽고 같은 값을 쓸 수 있다**.

이것이 전형적인 **read-modify-write 경쟁 조건**이다.

```
현재 count = 99, limit = 100

스레드 A: getOrDefault() → 99 → count = 100 → put(100) → 허용
스레드 B: getOrDefault() → 99 → count = 100 → put(100) → 허용
스레드 C: getOrDefault() → 99 → count = 100 → put(100) → 허용
```

세 스레드 모두 99를 읽고 100으로 올린다. 실제로는 3번 허용되었지만 카운터는 100으로만 기록된다. 이후 스레드들도 100을 읽고 101로 올려 차단하지만, **이미 초과 허용된 요청은 되돌릴 수 없다**.

> **요약**: `HashMap`의 `getOrDefault` + `put`은 읽기와 쓰기가 분리된 비원자적 연산이다. 동시 요청 시 여러 스레드가 같은 카운트 값을 읽어 limit을 초과하는 요청이 허용된다.

---

## 구현할 것

### 1. 동시성 재현 테스트

**위치**: `test/.../ratelimit/algorithm/FixedWindowRateLimiterConcurrencyTest.java`

**테스트 시나리오**:
- 로그인 사용자 1명, limit = 100
- 200개 스레드가 동시에 `tryAcquire` 호출
- 10회 반복하여 1번이라도 limit을 초과하면 경쟁 조건 재현 성공

**핵심 코드 구조**:
```java
int totalRounds = 10;
int threadCount = 200;
int limit = 100;
int exceededCount = 0;

for (int round = 0; round < totalRounds; round++) {
    FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger allowedCount = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            startLatch.await();
            RateLimitResult result = limiter.tryAcquire("user:test", limit, 60);
            if (result.allowed()) {
                allowedCount.incrementAndGet();
            }
        });
    }
    startLatch.countDown(); // 동시 출발

    if (allowedCount.get() > limit) {
        exceededCount++;
    }
}

// 10회 중 1번이라도 초과하면 경쟁 조건 재현 성공
assertThat(exceededCount).isGreaterThan(0);
```

**실제 테스트 결과** (10회 반복):
```
[Round 1] 허용된 요청 수: 102 (limit: 100)
[Round 2] 허용된 요청 수: 106 (limit: 100)
[Round 3] 허용된 요청 수: 104 (limit: 100)
[Round 4] 허용된 요청 수: 135 (limit: 100)
[Round 5] 허용된 요청 수: 100 (limit: 100)
...
초과 발생 횟수: 6/10
```

limit=100인데 최대 135개가 허용되었다. **경쟁 조건 재현 성공**.

---

### 2. 개선: ConcurrentHashMap + AtomicInteger

기존 `FixedWindowRateLimiter`의 `HashMap` + `getOrDefault/put`을 `ConcurrentHashMap` + `AtomicInteger`로 교체한다.

**핵심 변경**:

```java
// Before (1차 - 경쟁 조건 발생)
private final Map<String, Integer> request = new HashMap<>();

int count = request.getOrDefault(value, 0) + 1;  // 읽기
request.put(value, count);                        // 쓰기 (분리됨)

// After (2차 - 원자적 연산)
private final ConcurrentHashMap<String, AtomicInteger> request = new ConcurrentHashMap<>();

int count = request.computeIfAbsent(value, k -> new AtomicInteger(0)).incrementAndGet();
```

**왜 이 방식이 안전한가**:
- `AtomicInteger.incrementAndGet()`은 CAS(Compare-And-Swap) 연산으로, 읽기+증가+쓰기가 하나의 원자적 연산이다
- 두 스레드가 동시에 호출해도 **반드시 다른 값**(예: 100, 101)을 반환받는다
- 따라서 101번째 스레드는 무조건 `count = 101`을 받아 차단된다

**개선 후 동일 테스트 결과**:
```
[Round 1~10] 허용된 요청 수: 100 (limit: 100)
초과 발생 횟수: 0/10
```

---

### 3. 설정 변경 (Bean 교체)

`FixedWindowRateLimiter`를 개선하면 기존 `@Component`를 그대로 유지하면 된다. 만약 1차 구현체를 별도로 보존하고 싶다면 `@Profile`로 분리할 수 있다.

```java
// 1차 (naive)
@Component
@Profile("phase1")
public class FixedWindowRateLimiter implements RateLimiter { ... }

// 2차 (개선)
@Component
@Profile("phase2")
public class ConcurrentFixedWindowRateLimiter implements RateLimiter { ... }
```

application.yml에서:
```yaml
spring:
  profiles:
    active: phase2
```

---

## 구현 순서

```
1. FixedWindowRateLimiterConcurrencyTest 작성 → 1차 구현(HashMap)에서 limit 초과 재현
2. FixedWindowRateLimiter를 ConcurrentHashMap + AtomicInteger로 개선
3. 동일 테스트로 개선 검증 → 100 이하로 정확히 제한됨을 확인
```

---

## 수동 테스트

```bash
# 동시 200개 요청 (한계 확인)
seq 1 200 | xargs -P 200 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -H "X-User-Id: 123" http://localhost:8080/posts/1 | sort | uniq -c

# 기대 결과 (개선 전): 200 100~약간 초과, 429 나머지
# 기대 결과 (개선 후): 200 정확히 100, 429 정확히 100
```

---

## 이 단계의 한계 (이후 시나리오에서 확인할 것)

- 단일 서버 내 동시성은 해결했지만, 서버가 여러 대면 각 서버가 독립적인 카운터를 가진다
- 서버 A에서 100번 허용 + 서버 B에서 100번 허용 → 총 200번 허용
- 공유 저장소(Redis) 없이는 분산 환경에서 정확한 rate limiting이 불가능하다
