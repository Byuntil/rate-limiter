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
@Profile("phase6")
public class RedisSlidingWindowCounterRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisSlidingWindowCounterRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {

        LocalDateTime now = LocalDateTime.now();
        int currentSecond = now.getSecond();

        String currentWindowKey = "rate_limit:" + key + ":" +
                now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String previousWindowKey = "rate_limit:" + key + ":" +
                now.minusMinutes(1).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));

        // 현재 구현 GET → 계산 → INCR (비원자적)
        // GET→INCR 사이에 다른 요청이 끼어들 수 있어 동시 요청에서 약간의 오차 발생 가능.

        // GET
        String prevCountStr = redisTemplate.opsForValue().get(previousWindowKey);
        long previousCount = (prevCountStr != null) ? Long.parseLong(prevCountStr) : 0;

        String currCountStr = redisTemplate.opsForValue().get(currentWindowKey);
        long currentCount = (currCountStr != null) ? Long.parseLong(currCountStr) : 0;

        // 계산: 가중 평균 = 이전 윈도우 카운트 × (1 - 경과 비율) + 현재 윈도우 카운트
        double elapsedRatio = currentSecond / (double) windowSeconds;
        double weightedCount = previousCount * (1 - elapsedRatio) + currentCount;

        long resetAt = now.withSecond(0).withNano(0)
                .plusMinutes(1)
                .toEpochSecond(ZoneOffset.UTC);

        if (weightedCount >= limit) {
            return RateLimitResult.blocked(limit, resetAt);
        }

        // INCR
        Long newCount = redisTemplate.opsForValue().increment(currentWindowKey);
        if (newCount != null && newCount == 1) {
            redisTemplate.expire(currentWindowKey, Duration.ofSeconds(windowSeconds * 2L));
        }

        int remaining = (int) Math.max(0, limit - (weightedCount + 1));
        return RateLimitResult.allowed(limit, remaining, resetAt);

        // Lua 스크립트 적용 시 GET + 계산 + INCR을 Redis에서 원자적으로 실행
        //
        // 위의 GET→계산→INCR 블록 전체를 아래로 교체한다.
        // Lua 스크립트는 Redis 서버에서 단일 명령으로 실행되므로
        // 다른 요청이 중간에 끼어들 수 없어 동시성 오차가 제거된다.
        //
        // 적용 방법:
        //   1. 생성자에서 스크립트를 한 번만 로드한다 (매 요청마다 로드하면 성능 저하)
        //      DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        //      script.setLocation(new ClassPathResource("scripts/sliding-window-counter.lua"));
        //      script.setResultType(Long.class);
        //
        //   2. tryAcquire 안에서 execute로 호출한다
        //      Long result = redisTemplate.execute(
        //              script,
        //              List.of(currentWindowKey, previousWindowKey),  // KEYS[1], KEYS[2]
        //              String.valueOf(limit),                          // ARGV[1]
        //              String.valueOf(windowSeconds),                  // ARGV[2]
        //              String.valueOf(currentSecond)                   // ARGV[3]
        //      );
        //
        //   3. 반환값으로 허용/차단을 판단한다
        //      -1이면 차단 (스크립트 내부에서 가중 평균 >= limit 판정)
        //      양수면 허용 (현재 윈도우의 새 카운트)
        //
        //      if (result == null || result == -1L) {
        //          return RateLimitResult.blocked(limit, resetAt);
        //      }
        //      int remaining = (int) Math.max(0, limit - result);
        //      return RateLimitResult.allowed(limit, remaining, resetAt);
    }
}
