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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("phase4")
class RedisFixedWindowRateLimiterMultiServerTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RedisFixedWindowRateLimiter[] servers;

    @BeforeEach
    void setUp() {
        servers = new RedisFixedWindowRateLimiter[3];
        for (int i = 0; i < 3; i++) {
            servers[i] = new RedisFixedWindowRateLimiter(redisTemplate);
        }
    }

    @AfterEach
    void tearDown() {
        Set<String> keys = redisTemplate.keys("rate_limit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("Redis 기반: 서버 3대에서 요청해도 전체 limit을 지킨다")
    void multi_server_respects_limit_with_redis() {
        int limit = 100;
        int totalRequests = 200;

        int totalAllowed = 0;
        int[] serverRequestCount = {0, 0, 0};

        for (int i = 0; i < totalRequests; i++) {
            if (servers[i % 3].tryAcquire("user:123", limit, 60).allowed()) {
                serverRequestCount[i % 3]++;
                totalAllowed++;
            }
        }

        for (int i = 0; i < serverRequestCount.length; i++) {
            System.out.println("server " + (i + 1) + ": " + serverRequestCount[i] + " requests");
        }
        System.out.println("[Redis 순차 분배] 전체 허용 수: " + totalAllowed + " (limit: " + limit + ")");

        assertThat(totalAllowed)
                .as("Redis를 공유하므로 전체 limit이 정확히 지켜진다")
                .isEqualTo(limit);
    }

    @Test
    @DisplayName("Redis 기반: 동시 요청 + 다중 서버에서도 limit을 초과하지 않는다")
    void concurrent_multi_server_respects_limit() throws InterruptedException {
        int limit = 100;
        int threadCount = 200;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger totalAllowed = new AtomicInteger(0);
        int[] serverRequestCount = {0, 0, 0};

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            int serverIndex = i % 3;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RateLimitResult result = servers[serverIndex].tryAcquire("user:456", limit, 60);
                    if (result.allowed()) {
                        totalAllowed.incrementAndGet();
                        serverRequestCount[serverIndex]++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        int allowed = totalAllowed.get();
        for (int i = 0; i < serverRequestCount.length; i++) {
            System.out.println("server " + (i + 1) + ": " + serverRequestCount[i] + " requests");
        }
        System.out.println("[Redis 동시 분배] 전체 허용 수: " + allowed + " (limit: " + limit + ")");

        assertThat(allowed)
                .as("Redis INCR의 원자성 덕분에 동시 요청에서도 limit을 초과하지 않는다")
                .isEqualTo(limit);
    }

    @Test
    @DisplayName("Phase 3 vs Phase 4 비교: 메모리 기반은 초과, Redis 기반은 정확")
    void memory_vs_redis_comparison() {
        int limit = 100;
        int totalRequests = 200;

        // Phase 3: 메모리 기반 (각 인스턴스 독립 Map)
        FixedWindowRateLimiterV2[] memoryServers = new FixedWindowRateLimiterV2[3];
        for (int i = 0; i < 3; i++) {
            memoryServers[i] = new FixedWindowRateLimiterV2();
        }

        int memoryAllowed = 0;
        for (int i = 0; i < totalRequests; i++) {
            if (memoryServers[i % 3].tryAcquire("user:compare", limit, 60).allowed()) {
                memoryAllowed++;
            }
        }

        // Phase 4: Redis 기반 (같은 RedisTemplate 공유)
        int redisAllowed = 0;
        for (int i = 0; i < totalRequests; i++) {
            if (servers[i % 3].tryAcquire("user:compare", limit, 60).allowed()) {
                redisAllowed++;
            }
        }

        System.out.println("메모리 기반 허용: " + memoryAllowed + " (limit 초과)");
        System.out.println("Redis 기반 허용: " + redisAllowed + " (limit 정확)");

        assertThat(memoryAllowed).isGreaterThan(limit);
        assertThat(redisAllowed).isEqualTo(limit);
    }
}
