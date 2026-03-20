package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterV2ConcurrencyTest {

    @Test
    @DisplayName("200개 스레드가 동시에 요청해도 허용 수가 limit을 초과하지 않는다 (개선 확인)")
    void concurrent_requests_do_not_exceed_limit() throws InterruptedException {
        int totalRounds = 10;
        int threadCount = 200;
        int limit = 100;

        for (int round = 0; round < totalRounds; round++) {
            FixedWindowRateLimiterV2 limiter = new FixedWindowRateLimiterV2();

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger allowedCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        RateLimitResult result = limiter.tryAcquire("user:test", limit, 60);
                        if (result.allowed()) {
                            allowedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            int allowed = allowedCount.get();
            System.out.println("[Round " + (round + 1) + "] 허용된 요청 수: " + allowed + " (limit: " + limit + ")");

            assertThat(allowed).as("ConcurrentHashMap + AtomicInteger는 동시 요청에서도 limit을 초과하지 않아야 한다")
                    .isLessThanOrEqualTo(limit);
        }
    }

    @Test
    @DisplayName("단일 스레드에서는 limit을 정확히 지킨다")
    void single_thread_respects_limit() {
        FixedWindowRateLimiterV2 rateLimiter = new FixedWindowRateLimiterV2();
        int limit = 100;
        int allowedCount = 0;

        for (int i = 0; i < 200; i++) {
            RateLimitResult result = rateLimiter.tryAcquire("user:single", limit, 60);
            if (result.allowed()) {
                allowedCount++;
            }
        }

        assertThat(allowedCount).isEqualTo(limit);
    }
}
