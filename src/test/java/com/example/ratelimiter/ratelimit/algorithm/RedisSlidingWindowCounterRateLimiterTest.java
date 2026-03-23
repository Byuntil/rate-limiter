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
@ActiveProfiles("phase6")
class RedisSlidingWindowCounterRateLimiterTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RedisSlidingWindowCounterRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RedisSlidingWindowCounterRateLimiter(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        Set<String> keys = redisTemplate.keys("rate_limit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("제한 이하 요청은 허용된다")
    void allow_under_limit() {
        RateLimitResult result = rateLimiter.tryAcquire("user:1", 100, 60);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("첫 번째 요청의 remaining은 limit - 1이다")
    void first_request_remaining_is_limit_minus_one() {
        int limit = 5;
        RateLimitResult result = rateLimiter.tryAcquire("user:remaining", limit, 60);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(limit - 1);
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
        assertThat(result.remaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("제한 횟수까지는 모두 허용된다")
    void allow_up_to_limit() {
        int limit = 5;
        for (int i = 0; i < limit; i++) {
            RateLimitResult result = rateLimiter.tryAcquire("user:3", limit, 60);
            assertThat(result.allowed()).isTrue();
        }

        RateLimitResult blocked = rateLimiter.tryAcquire("user:3", limit, 60);
        assertThat(blocked.allowed()).isFalse();
    }

    @Test
    @DisplayName("다른 키는 독립적으로 카운트된다")
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
}
