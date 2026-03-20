package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterV1Test {

    private FixedWindowRateLimiterV1 rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new FixedWindowRateLimiterV1();
    }

    @Test
    @DisplayName("제한 횟수 이하의 요청은 허용된다")
    void allow_when_under_limit() {
        RateLimitResult result = rateLimiter.tryAcquire("user:1", 5, 60);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("요청할수록 remaining이 1씩 감소한다")
    void remaining_decreases_per_request() {
        rateLimiter.tryAcquire("user:2", 5, 60); // 1회
        rateLimiter.tryAcquire("user:2", 5, 60); // 2회
        RateLimitResult result = rateLimiter.tryAcquire("user:2", 5, 60); // 3회

        assertThat(result.remaining()).isEqualTo(2);
    }

    @Test
    @DisplayName("제한 횟수를 초과하면 차단된다")
    void block_when_over_limit() {
        int limit = 3;
        for (int i = 0; i < limit; i++) {
            rateLimiter.tryAcquire("user:3", limit, 60);
        }

        RateLimitResult result = rateLimiter.tryAcquire("user:3", limit, 60);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
    }

    @Test
    @DisplayName("정확히 제한 횟수만큼은 허용된다")
    void allow_exactly_at_limit() {
        int limit = 3;
        RateLimitResult last = null;
        for (int i = 0; i < limit; i++) {
            last = rateLimiter.tryAcquire("user:4", limit, 60);
        }

        assertThat(last.allowed()).isTrue();
        assertThat(last.remaining()).isZero();
    }

    @Test
    @DisplayName("서로 다른 key는 독립적으로 카운트된다")
    void different_keys_are_independent() {
        int limit = 2;
        rateLimiter.tryAcquire("user:A", limit, 60);
        rateLimiter.tryAcquire("user:A", limit, 60);

        RateLimitResult resultA = rateLimiter.tryAcquire("user:A", limit, 60);
        RateLimitResult resultB = rateLimiter.tryAcquire("user:B", limit, 60);

        assertThat(resultA.allowed()).isFalse();
        assertThat(resultB.allowed()).isTrue();
    }

    @Test
    @DisplayName("응답에 limit 값이 포함된다")
    void result_contains_limit() {
        RateLimitResult result = rateLimiter.tryAcquire("user:5", 10, 60);

        assertThat(result.limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("응답에 resetAt이 미래 시각으로 포함된다")
    void result_contains_future_reset_at() {
        long before = System.currentTimeMillis() / 1000;

        RateLimitResult result = rateLimiter.tryAcquire("user:6", 10, 60);

        assertThat(result.resetAt()).isGreaterThanOrEqualTo(before);
    }
}
