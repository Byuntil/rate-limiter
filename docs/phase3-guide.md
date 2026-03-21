# 3차 시나리오: 다중 서버 환경에서의 한계 분석

## 목표

2차에서 단일 서버 내 동시성 문제를 해결했지만, **서버가 여러 대인 환경에서는 메모리 기반 rate limiter가 근본적으로 동작하지 않음**을 증명한다.

이 단계는 코드를 "고치는" 단계가 아니라, **한계를 재현하고 분석하는** 단계다.

---

## 문제 상황

### 왜 메모리 기반은 분산 환경에서 깨지는가

현재 `FixedWindowRateLimiterV2`는 `ConcurrentHashMap`에 카운트를 저장한다. 이 Map은 **JVM 인스턴스마다 독립적**이다.

```
Load Balancer
    ├── Server A (ConcurrentHashMap: user:123 → 40)
    ├── Server B (ConcurrentHashMap: user:123 → 40)
    └── Server C (ConcurrentHashMap: user:123 → 40)
```

같은 사용자 `user:123`이 limit=100인데, 로드밸런서가 요청을 분산하면:
- 서버 A에서 40회 허용
- 서버 B에서 40회 허용
- 서버 C에서 40회 허용

**총 120회가 허용된다.** 각 서버는 자기 카운터만 보기 때문에 전체 제한이 깨진다.

### 핵심 원인

| 구분 | 단일 서버 | 다중 서버 |
|------|-----------|-----------|
| 카운터 저장소 | JVM 메모리 (공유됨) | JVM 메모리 (서버별 독립) |
| 같은 key 접근 | 동일 Map 참조 | 서버마다 별도 Map |
| limit 보장 | O (2차에서 해결) | X (서버 수만큼 배수로 허용) |

> **요약**: 메모리 기반 rate limiter는 단일 서버에서만 유효하다. 서버가 N대면 최악의 경우 limit × N 만큼 허용될 수 있다.

---

## 구현할 것

### 1. 다중 서버 시뮬레이션 테스트

**위치**: `test/.../ratelimit/algorithm/FixedWindowRateLimiterV2MultiServerTest.java`

서버 3대를 시뮬레이션한다. 실제 서버를 3대 띄우는 것이 아니라, **독립된 limiter 인스턴스 3개**를 만들어 로드밸런서처럼 요청을 분산한다.

**테스트 시나리오**:
- 같은 사용자 `user:123`, limit = 100
- limiter 인스턴스 3개 (서버 A, B, C 시뮬레이션)
- 총 200개 요청을 Round-Robin으로 3개 인스턴스에 분배
- 전체 허용 수가 limit(100)을 초과하는지 확인

**핵심 코드 구조**:
```java
@Test
@DisplayName("서버 3대 시뮬레이션: 메모리 기반은 전체 limit을 보장하지 못한다")
void multi_server_exceeds_limit() {
    int serverCount = 3;
    int limit = 100;
    int totalRequests = 200;

    // 서버 3대 시뮬레이션 (독립된 인스턴스)
    FixedWindowRateLimiterV2[] servers = new FixedWindowRateLimiterV2[serverCount];
    for (int i = 0; i < serverCount; i++) {
        servers[i] = new FixedWindowRateLimiterV2();
    }

    int totalAllowed = 0;
    for (int i = 0; i < totalRequests; i++) {
        // Round-Robin 분배
        FixedWindowRateLimiterV2 server = servers[i % serverCount];
        RateLimitResult result = server.tryAcquire("user:123", limit, 60);
        if (result.allowed()) {
            totalAllowed++;
        }
    }

    System.out.println("전체 허용 수: " + totalAllowed + " (limit: " + limit + ")");

    // 메모리 기반이므로 limit을 초과할 수밖에 없다
    assertThat(totalAllowed)
        .as("서버가 3대면 각각 독립 카운터를 가지므로 전체 limit을 초과한다")
        .isGreaterThan(limit);
}
```

**기대 결과**:
```
전체 허용 수: 200 (limit: 100)
```

200개 요청 중 각 서버가 최대 67개씩 받으므로(200/3), 모든 서버에서 limit=100 이하로 판단하여 **전부 허용**된다.

---

### 2. 동시 요청 + 다중 서버 시뮬레이션 테스트

단순 순차 분배가 아닌, **동시 요청이 다중 서버에 분산**되는 상황도 테스트한다.

**테스트 시나리오**:
- 300개 스레드가 동시에 요청
- 각 스레드가 랜덤하게 서버 A/B/C 중 하나에 요청
- 전체 허용 수가 limit을 초과하는지 확인

**핵심 코드 구조**:
```java
@Test
@DisplayName("동시 요청 + 다중 서버: 동시성까지 겹치면 더 심하게 초과한다")
void concurrent_multi_server_exceeds_limit() throws InterruptedException {
    int serverCount = 3;
    int limit = 100;
    int threadCount = 300;

    FixedWindowRateLimiterV2[] servers = new FixedWindowRateLimiterV2[serverCount];
    for (int i = 0; i < serverCount; i++) {
        servers[i] = new FixedWindowRateLimiterV2();
    }

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    AtomicInteger totalAllowed = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
        int serverIndex = i % serverCount;
        executor.submit(() -> {
            try {
                startLatch.await();
                RateLimitResult result = servers[serverIndex].tryAcquire("user:123", limit, 60);
                if (result.allowed()) {
                    totalAllowed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
    }

    startLatch.countDown();
    doneLatch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    System.out.println("전체 허용 수: " + totalAllowed.get() + " (limit: " + limit + ")");

    assertThat(totalAllowed.get())
        .as("다중 서버 환경에서 전체 limit을 초과한다")
        .isGreaterThan(limit);
}
```

**기대 결과**:
```
전체 허용 수: 300 (limit: 100)
```

각 서버에 100개씩 분배되므로, 서버당 limit 이하 → 전부 허용. **limit의 3배**가 통과한다.

---

### 3. 단일 서버 vs 다중 서버 비교 테스트

같은 조건에서 단일 서버와 다중 서버의 결과를 나란히 비교하는 테스트를 작성한다.

```java
@Test
@DisplayName("단일 서버 vs 다중 서버: 같은 요청인데 결과가 다르다")
void single_vs_multi_server_comparison() {
    int limit = 100;
    int totalRequests = 200;

    // 단일 서버
    FixedWindowRateLimiterV2 singleServer = new FixedWindowRateLimiterV2();
    int singleAllowed = 0;
    for (int i = 0; i < totalRequests; i++) {
        if (singleServer.tryAcquire("user:123", limit, 60).allowed()) {
            singleAllowed++;
        }
    }

    // 다중 서버 (3대)
    FixedWindowRateLimiterV2[] multiServers = {
        new FixedWindowRateLimiterV2(),
        new FixedWindowRateLimiterV2(),
        new FixedWindowRateLimiterV2()
    };
    int multiAllowed = 0;
    for (int i = 0; i < totalRequests; i++) {
        if (multiServers[i % 3].tryAcquire("user:123", limit, 60).allowed()) {
            multiAllowed++;
        }
    }

    System.out.println("단일 서버 허용: " + singleAllowed);
    System.out.println("다중 서버 허용: " + multiAllowed);

    assertThat(singleAllowed).isEqualTo(limit);           // 단일: 정확히 100
    assertThat(multiAllowed).isGreaterThan(limit);         // 다중: 100 초과
}
```

**기대 결과**:
```
단일 서버 허용: 100
다중 서버 허용: 200
```

---

## 구현 순서

```
1. FixedWindowRateLimiterV2MultiServerTest 작성
2. 다중 서버 시뮬레이션 테스트 실행 → limit 초과 재현
3. 동시 요청 + 다중 서버 테스트 실행 → 더 심한 초과 재현
4. 단일 vs 다중 비교 테스트로 차이 확인
```

---

## 분석 정리

### 메모리 기반 rate limiter의 한계

| 문제 | 원인 | 영향 |
|------|------|------|
| 서버별 독립 카운터 | JVM 메모리는 프로세스 간 공유 불가 | limit × N 허용 |
| 서버 재시작 시 초기화 | 메모리는 휘발성 | 재시작 직후 limit 리셋 |
| 스케일 아웃 불가 | 서버 추가 시 제한 정확도 하락 | 수평 확장과 양립 불가 |

### 해결 방향

모든 서버가 **하나의 공유 카운터**를 바라봐야 한다.

```
Load Balancer
    ├── Server A ──┐
    ├── Server B ──┼──→ Redis (user:123 → 120, 차단)
    └── Server C ──┘
```

- **Redis INCR**: 원자적 카운트 증가
- **Redis TTL**: 윈도우 만료 자동 처리
- **단일 저장소**: 모든 서버가 동일 카운터 참조

---

## 이 단계의 한계 (4차 시나리오에서 해결할 것)

- 이 단계에서는 문제를 **확인만** 한다. 해결은 하지 않는다.
- 4차 시나리오에서 Redis 기반 Fixed Window를 구현하여 분산 환경에서도 정확한 rate limiting을 달성한다.
- Redis 도입 시 고려할 점: 네트워크 지연, Redis 장애 시 fallback 정책, key 설계
