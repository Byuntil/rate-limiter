# 5차 시나리오: Fixed Window 경계값 문제 재현 및 분석

## 목표

4차에서 Redis 기반 Fixed Window를 구현하여 분산 환경에서의 정확한 rate limiting을 달성했다.
하지만 Fixed Window 알고리즘 자체에 **윈도우 경계에서 burst 트래픽을 허용하는 구조적 문제**가 있다.

이 단계에서는 경계값 문제를 **테스트로 재현**하고, 왜 이것이 문제인지 분석한다.

---

## 현재 문제 (Fixed Window의 구조적 한계)

### 경계값 문제란?

Fixed Window는 시간을 고정된 구간(예: 12:00~12:01, 12:01~12:02)으로 나눈다.
문제는 **두 윈도우의 경계에 요청이 몰리면**, 사실상 제한의 2배가 허용된다는 것이다.

```
제한: 1분 100회

12:00:00 ────────────────── 12:00:59 │ 12:01:00 ────────────────── 12:01:59
            (거의 안 씀)      100회    │  100회        (거의 안 씀)
                               ↑      │   ↑
                         윈도우 1 끝    │  윈도우 2 시작
                                       │
                              1~2초 사이에 200회 허용
```

- 12:00:59에 100회 요청 → 윈도우 1의 limit 이내이므로 **전부 허용**
- 12:01:00에 100회 요청 → 윈도우 2가 새로 시작하므로 **전부 허용**
- 결과: **1~2초 사이에 200회**가 통과한다

### 왜 이것이 문제인가

| 관점 | 문제 |
|------|------|
| 서버 보호 | 순간 burst가 limit의 2배까지 발생 → 서버에 순간 부하 |
| 공정성 | 경계를 노리는 사용자가 더 많은 요청을 보낼 수 있다 |
| 정확도 | "1분에 100회"라는 정책이 실제로는 "1초에 200회"가 될 수 있다 |

### 핵심 원인

Fixed Window의 key에 **분 단위 타임스탬프**가 포함된다.

```
rate_limit:user:123:202603221430  → 윈도우 1
rate_limit:user:123:202603221431  → 윈도우 2 (완전히 별개의 key)
```

윈도우가 바뀌면 카운터가 **0에서 다시 시작**한다. 이전 윈도우에서 얼마나 썼는지 전혀 고려하지 않는다.

---

## 구현할 것

이 단계는 코드를 "고치는" 단계가 아니라, **한계를 재현하고 분석하는** 단계다.

### 1. 경계값 문제 재현 테스트

**위치**: `test/.../algorithm/RedisFixedWindowBoundaryTest.java`

시간을 직접 제어하여 윈도우 경계에서의 burst를 재현한다. 실제 시간을 기다릴 수 없으므로, **key를 직접 구성**하여 시뮬레이션한다.

```java
@Test
@DisplayName("Fixed Window 경계: 윈도우가 바뀌면 카운터가 리셋된다")
void window_boundary_allows_double_limit() {
    int limit = 100;

    // 윈도우 1: 직전 분에 100회 요청 (limit 꽉 채움)
    String window1Key = "rate_limit:user:boundary:202603221430";
    for (int i = 0; i < limit; i++) {
        redisTemplate.opsForValue().increment(window1Key);
    }

    // 윈도우 2: 다음 분에 100회 요청 (새 윈도우이므로 다시 허용)
    String window2Key = "rate_limit:user:boundary:202603221431";
    for (int i = 0; i < limit; i++) {
        redisTemplate.opsForValue().increment(window2Key);
    }

    Long window1Count = Long.parseLong(redisTemplate.opsForValue().get(window1Key));
    Long window2Count = Long.parseLong(redisTemplate.opsForValue().get(window2Key));

    // 두 윈도우 합산: 200회 허용 (limit의 2배)
    assertThat(window1Count + window2Count).isEqualTo(limit * 2);
}
```

---

### 2. tryAcquire를 통한 경계값 재현 테스트

key를 직접 다루는 것 외에, **시간을 주입할 수 있는 구조**로 경계값을 테스트한다.

`RedisFixedWindowRateLimiter`는 내부에서 `LocalDateTime.now()`를 직접 호출하므로, 시간 제어가 어렵다.
테스트를 위해 **Clock을 주입받는 방식**으로 리팩터링한다.

**변경할 것**: `RedisFixedWindowRateLimiter`에 `Clock` 파라미터 추가

```java
public class RedisFixedWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemDefaultZone());
    }

    public RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        String now = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        // ... 나머지 동일
    }
}
```

**Clock 주입 테스트**:

```java
@Test
@DisplayName("Clock 기반: 윈도우 경계에서 limit의 2배가 허용된다")
void boundary_burst_with_clock() {
    int limit = 100;

    // 12:30:59 시점의 Clock
    Clock clock1 = Clock.fixed(
        LocalDateTime.of(2026, 3, 22, 12, 30, 59).toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC
    );
    RedisFixedWindowRateLimiter limiter1 = new RedisFixedWindowRateLimiter(redisTemplate, clock1);

    // 12:31:00 시점의 Clock (1초 후 = 새 윈도우)
    Clock clock2 = Clock.fixed(
        LocalDateTime.of(2026, 3, 22, 12, 31, 0).toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC
    );
    RedisFixedWindowRateLimiter limiter2 = new RedisFixedWindowRateLimiter(redisTemplate, clock2);

    // 윈도우 1에서 100회 요청
    int allowed1 = 0;
    for (int i = 0; i < limit; i++) {
        if (limiter1.tryAcquire("user:burst", limit, 60).allowed()) {
            allowed1++;
        }
    }

    // 윈도우 2에서 100회 요청 (1초 후)
    int allowed2 = 0;
    for (int i = 0; i < limit; i++) {
        if (limiter2.tryAcquire("user:burst", limit, 60).allowed()) {
            allowed2++;
        }
    }

    System.out.println("윈도우 1 허용: " + allowed1);
    System.out.println("윈도우 2 허용: " + allowed2);
    System.out.println("1초 사이 총 허용: " + (allowed1 + allowed2));

    assertThat(allowed1).isEqualTo(limit);
    assertThat(allowed2).isEqualTo(limit);
    assertThat(allowed1 + allowed2)
        .as("경계에서 limit의 2배가 허용된다")
        .isEqualTo(limit * 2);
}
```

---

### 3. Sliding Window와의 차이를 보여주는 개념 테스트

Fixed Window의 경계 문제가 Sliding Window에서는 어떻게 해결되는지 **개념적으로 비교**하는 테스트를 작성한다.

```java
@Test
@DisplayName("개념 비교: Fixed Window vs Sliding Window 경계 동작")
void fixed_vs_sliding_concept() {
    int limit = 100;

    // Fixed Window: 12:30 윈도우에서 100회, 12:31 윈도우에서 100회
    // → 경계에서 200회 허용

    // Sliding Window (가상): 현재 시점 기준 최근 60초를 봄
    // 12:30:59에 100회를 썼다면, 12:31:00 시점에서 최근 60초 = 12:30:00~12:31:00
    // → 이미 100회를 썼으므로 추가 요청 차단

    // 이 테스트는 Fixed Window의 동작만 검증하고,
    // Sliding Window는 6차에서 구현한다

    int fixedWindowTotal = 0;

    // 윈도우 1 (12:30분대)
    Clock clock1 = Clock.fixed(
        LocalDateTime.of(2026, 3, 22, 12, 30, 59).toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC
    );
    RedisFixedWindowRateLimiter fw1 = new RedisFixedWindowRateLimiter(redisTemplate, clock1);
    for (int i = 0; i < limit; i++) {
        if (fw1.tryAcquire("user:concept", limit, 60).allowed()) {
            fixedWindowTotal++;
        }
    }

    // 윈도우 2 (12:31분대)
    Clock clock2 = Clock.fixed(
        LocalDateTime.of(2026, 3, 22, 12, 31, 0).toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC
    );
    RedisFixedWindowRateLimiter fw2 = new RedisFixedWindowRateLimiter(redisTemplate, clock2);
    for (int i = 0; i < limit; i++) {
        if (fw2.tryAcquire("user:concept", limit, 60).allowed()) {
            fixedWindowTotal++;
        }
    }

    System.out.println("[Fixed Window] 경계 1초간 허용: " + fixedWindowTotal);
    System.out.println("[Sliding Window] 경계 1초간 허용: " + limit + " (6차에서 구현)");

    assertThat(fixedWindowTotal).isEqualTo(limit * 2);
}
```

---

## 구현 순서

```
1. RedisFixedWindowRateLimiter에 Clock 주입 지원 추가
2. 기존 테스트가 깨지지 않는지 확인 (기본 생성자는 Clock.systemDefaultZone())
3. 경계값 재현 테스트 작성 (key 직접 조작 방식)
4. Clock 기반 경계값 재현 테스트 작성
5. Fixed Window vs Sliding Window 개념 비교 테스트 작성
```

---

## 분석 정리

### Fixed Window 경계값 문제 시각화

```
요청 수
  200 ┤                    ╭─╮
      │                    │ │ ← 경계에서 burst
  100 ┤────────────────────╯ ╰────────────────────
      │
    0 ┤
      └──────────────────────────────────────────→ 시간
      12:00              12:01              12:02
      ← 윈도우 1 →       ← 윈도우 2 →
```

### 알고리즘별 경계 동작 비교

| 구분 | Fixed Window | Sliding Window Log | Sliding Window Counter | Token Bucket |
|------|-------------|-------------------|----------------------|-------------|
| 경계 burst | limit x 2 허용 | 발생 안 함 | 근사치로 완화 | burst 허용 but 토큰 소진 |
| 구현 복잡도 | 낮음 | 높음 | 중간 | 중간 |
| 저장 비용 | key 1개 / 윈도우 | 요청마다 기록 | key 2개 / 윈도우 | key 1개 |
| 정확도 | 낮음 (경계) | 높음 | 중간 (가중 평균) | 높음 |

### Sliding Window Counter 방식 (6차 후보)

경계 문제를 **완전히 없애지는 않지만 크게 완화**하는 방식이다.

```
현재 시점: 12:01:15 (현재 윈도우의 25% 지점)

이전 윈도우 (12:00) 카운트: 84
현재 윈도우 (12:01) 카운트: 36

가중 평균 = 이전 윈도우 * (1 - 경과 비율) + 현재 윈도우
         = 84 * 0.75 + 36
         = 63 + 36
         = 99

→ limit=100이므로 1회 더 허용
```

이전 윈도우의 카운트를 **경과 시간 비율로 감소**시켜 반영한다. 완벽하지는 않지만, Fixed Window보다 훨씬 정확하다.

---

## 이 단계의 한계 (6차 시나리오에서 해결할 것)

- 이 단계에서는 문제를 **확인만** 한다. 해결은 하지 않는다.
- Clock 주입은 테스트 가능성을 높이는 리팩터링이지, 경계 문제의 해결책이 아니다.
- 6차 시나리오에서 Sliding Window 또는 Token Bucket을 구현하여 경계값 문제를 해결한다.
- 어떤 알고리즘을 선택할지는 scenario.md의 12번 섹션에서 정한 기준을 따른다.
