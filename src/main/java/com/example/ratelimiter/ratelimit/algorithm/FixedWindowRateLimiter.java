package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class FixedWindowRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, AtomicInteger> request = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String value = key + ":" + now;

        int count = request.computeIfAbsent(value, k -> new AtomicInteger(0)).incrementAndGet();

        long resetAt = LocalDateTime.now()
                .withSecond(0).withNano(0)
                .plusMinutes(1)
                .toEpochSecond(ZoneOffset.UTC);

        if (count <= limit) {
            return RateLimitResult.allowed(limit, limit - count, resetAt);
        } else {
            return RateLimitResult.blocked(limit, resetAt);
        }
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanUp() {
        String currentWindow = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        request.keySet().removeIf(k -> !k.contains(":" + currentWindow));
    }
}
