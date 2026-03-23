package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class TestableRedisSlidingWindowCounterRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public TestableRedisSlidingWindowCounterRateLimiter(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        LocalDateTime now = LocalDateTime.now(clock);
        int currentSecond = now.getSecond();

        String currentWindowKey = "rate_limit:" + key + ":" +
                now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String previousWindowKey = "rate_limit:" + key + ":" +
                now.minusMinutes(1).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));

        String prevCountStr = redisTemplate.opsForValue().get(previousWindowKey);
        long previousCount = (prevCountStr != null) ? Long.parseLong(prevCountStr) : 0;

        String currCountStr = redisTemplate.opsForValue().get(currentWindowKey);
        long currentCount = (currCountStr != null) ? Long.parseLong(currCountStr) : 0;

        double elapsedRatio = currentSecond / (double) windowSeconds;
        double weightedCount = previousCount * (1 - elapsedRatio) + currentCount;

        long resetAt = now.withSecond(0).withNano(0)
                .plusMinutes(1)
                .toEpochSecond(ZoneOffset.UTC);

        if (weightedCount >= limit) {
            return RateLimitResult.blocked(limit, resetAt);
        }

        Long newCount = redisTemplate.opsForValue().increment(currentWindowKey);
        if (newCount != null && newCount == 1) {
            redisTemplate.expire(currentWindowKey, Duration.ofSeconds(windowSeconds * 2L));
        }

        int remaining = (int) Math.max(0, limit - (weightedCount + 1));
        return RateLimitResult.allowed(limit, remaining, resetAt);
    }
}
