package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("phase4")
class RedisFixedWindowRateLimiterTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RedisFixedWindowRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RedisFixedWindowRateLimiter(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        Set<String> keys = redisTemplate.keys("rate_limit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("Redis 기반: 제한 횟수 이하의 요청은 허용된다")
    void allow_when_under_limit() {
        RateLimitResult result = rateLimiter.tryAcquire("user:1", 5, 60);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(4);
    }

    @Test
    @DisplayName("Redis 기반: 제한 횟수까지는 모두 허용된다")
    void allow_up_to_limit() {
        int limit = 5;
        for (int i = 0; i < limit; i++) {
            RateLimitResult result = rateLimiter.tryAcquire("user:2", limit, 60);
            assertThat(result.allowed()).isTrue();
        }
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
        assertThat(result.remaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("Redis 기반: 다른 키는 독립적으로 카운트된다")
    void different_keys_are_independent() {
        int limit = 2;
        for (int i = 0; i < limit; i++) {
            rateLimiter.tryAcquire("user:A", limit, 60);
        }

        RateLimitResult resultA = rateLimiter.tryAcquire("user:A", limit, 60);
        RateLimitResult resultB = rateLimiter.tryAcquire("user:B", limit, 60);

        assertThat(resultA.allowed()).isFalse();
        assertThat(resultB.allowed()).isTrue();
    }

    @Test
    @DisplayName("Redis 기반: remaining이 정확히 감소한다")
    void remaining_decreases_correctly() {
        int limit = 5;

        for (int i = 0; i < limit; i++) {
            RateLimitResult result = rateLimiter.tryAcquire("user:4", limit, 60);
            assertThat(result.remaining()).isEqualTo(limit - i - 1);
        }
    }
}
