package com.example.ratelimiter.ratelimit.algorithm;

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
@ActiveProfiles("phase4")
class RedisFixedWindowBoundaryTest {

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
    @DisplayName("윈도우 경계 1초 사이에 limit의 2배가 허용된다")
    void boundary_burst_with_clock() {
        int limit = 100;

        // 12:30:59 시점의 Clock (윈도우 1 끝)
        Clock clock1 = Clock.fixed(
                LocalDateTime.of(2026, 3, 22, 12, 30, 59).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC
        );
        TestableRedisFixedWindowRateLimiter limiter1 = new TestableRedisFixedWindowRateLimiter(redisTemplate, clock1);

        // 12:31:00 시점의 Clock (윈도우 2 시작, 1초 후)
        Clock clock2 = Clock.fixed(
                LocalDateTime.of(2026, 3, 22, 12, 31, 0).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC
        );
        TestableRedisFixedWindowRateLimiter limiter2 = new TestableRedisFixedWindowRateLimiter(redisTemplate, clock2);

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

        int totalAllowed = allowed1 + allowed2;

        System.out.println("윈도우 1 허용: " + allowed1);
        System.out.println("윈도우 2 허용: " + allowed2);
        System.out.println("1초 사이 총 허용: " + totalAllowed + " (limit: " + limit + ")");

        assertThat(allowed1).isEqualTo(limit);
        assertThat(allowed2).isEqualTo(limit);
        assertThat(totalAllowed)
                .as("경계에서 limit의 2배가 허용된다")
                .isEqualTo(limit * 2);
    }
}
