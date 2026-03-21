package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@Profile("phase4")
public class RedisFixedWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String redisKey = "rate_limit:" + key + ":" + now;

        Long count = redisTemplate.opsForValue().increment(redisKey);

        if (count != null && count == 1) {
            redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
        }

        long resetAt = LocalDateTime.now()
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
