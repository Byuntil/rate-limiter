package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class TestableRedisFixedWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public TestableRedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        String now = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String redisKey = "rate_limit:" + key + ":" + now;

        Long count = redisTemplate.opsForValue().increment(redisKey);

        if (count != null && count == 1) {
            redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
        }

        long resetAt = LocalDateTime.now(clock)
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
