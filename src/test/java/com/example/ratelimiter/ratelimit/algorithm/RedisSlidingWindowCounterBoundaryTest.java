package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("phase6")
class RedisSlidingWindowCounterBoundaryTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        Set<String> keys = redisTemplate.keys("rate_limit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("경계에서 이전 윈도우 사용량을 반영하여 burst를 억제한다")
    void boundary_burst_suppressed() {
        int limit = 100;

        // 윈도우 1: 12:30:59 (윈도우 마지막 초)
        Clock clock1 = Clock.fixed(
                LocalDateTime.of(2026, 3, 22, 12, 30, 59).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC
        );
        TestableRedisSlidingWindowCounterRateLimiter limiter1 =
                new TestableRedisSlidingWindowCounterRateLimiter(redisTemplate, clock1);

        // 윈도우 1에서 100회 소진
        int allowed1 = 0;
        for (int i = 0; i < limit; i++) {
            if (limiter1.tryAcquire("user:boundary", limit, 60).allowed()) {
                allowed1++;
            }
        }

        // 윈도우 2: 12:31:00 (경계 직후, 경과 비율 = 0/60 = 0.0)
        // 가중 평균 = 100 × (1 - 0.0) + 0 = 100 → limit 도달로 차단
        Clock clock2 = Clock.fixed(
                LocalDateTime.of(2026, 3, 22, 12, 31, 0).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC
        );
        TestableRedisSlidingWindowCounterRateLimiter limiter2 =
                new TestableRedisSlidingWindowCounterRateLimiter(redisTemplate, clock2);

        RateLimitResult result = limiter2.tryAcquire("user:boundary", limit, 60);

        System.out.println("윈도우 1 허용: " + allowed1);
        System.out.println("윈도우 2 첫 요청: " + (result.allowed() ? "허용" : "차단"));

        assertThat(allowed1).isEqualTo(limit);
        // Sliding Window Counter는 경계에서 이전 사용량을 반영하므로 차단
        assertThat(result.allowed()).isFalse();
    }

    @Test
    @DisplayName("윈도우 중간 시점에서는 이전 윈도우 가중치가 줄어든다")
    void previous_window_weight_decreases_over_time() {
        int limit = 100;

        // 윈도우 1에서 100회 소진
        Clock clock1 = Clock.fixed(
                LocalDateTime.of(2026, 3, 22, 12, 30, 59).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC
        );
        TestableRedisSlidingWindowCounterRateLimiter limiter1 =
                new TestableRedisSlidingWindowCounterRateLimiter(redisTemplate, clock1);
        for (int i = 0; i < limit; i++) {
            limiter1.tryAcquire("user:weight", limit, 60);
        }

        // 윈도우 2의 30초 시점: 경과 비율 = 30/60 = 0.5
        // 가중 평균 = 100 × (1 - 0.5) + 0 = 50 → 50회 더 허용 가능
        Clock clockMid = Clock.fixed(
                LocalDateTime.of(2026, 3, 22, 12, 31, 30).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC
        );
        TestableRedisSlidingWindowCounterRateLimiter limiterMid =
                new TestableRedisSlidingWindowCounterRateLimiter(redisTemplate, clockMid);

        int allowedAtMid = 0;
        for (int i = 0; i < limit; i++) {
            if (limiterMid.tryAcquire("user:weight", limit, 60).allowed()) {
                allowedAtMid++;
            }
        }

        System.out.println("윈도우 2의 30초 시점 허용량: " + allowedAtMid + " (기대값: ~50)");
        // 30초 경과 시 이전 윈도우 가중치 0.5 → 약 50회 허용
        assertThat(allowedAtMid).isBetween(45, 55);
    }
}
