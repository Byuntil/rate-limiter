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

class FixedWindowRateLimiterV2MultiServerTest {

    @Test
    @DisplayName("서버 3대 시뮬레이션: 메모리 기반은 전체 limit을 보장하지 못한다")
    void multi_server_exceeds_limit() {
        int serverCount = 3;
        int limit = 100;
        int totalRequests = 200;

        FixedWindowRateLimiterV2[] servers = new FixedWindowRateLimiterV2[serverCount];
        int[] serverRequestCount = {0, 0, 0};
        for (int i = 0; i < serverCount; i++) {
            servers[i] = new FixedWindowRateLimiterV2();
        }

        int totalAllowed = 0;
        for (int i = 0; i < totalRequests; i++) {
            FixedWindowRateLimiterV2 server = servers[i % serverCount];
            RateLimitResult result = server.tryAcquire("user:123", limit, 60);
            if (result.allowed()) {
                serverRequestCount[i % serverCount]++;
                totalAllowed++;
            }
        }

        for (int i = 0; i < serverCount; i++) {
            System.out.println("server " + (i + 1) + ": " + serverRequestCount[i] + " requests");
        }

        System.out.println("[순차 분배] 전체 허용 수: " + totalAllowed + " (limit: " + limit + ")");

        assertThat(totalAllowed)
                .as("서버가 3대면 각각 독립 카운터를 가지므로 전체 limit을 초과한다")
                .isGreaterThan(limit);
    }

    @Test
    @DisplayName("동시 요청 + 다중 서버: 동시성까지 겹치면 더 심하게 초과한다")
    void concurrent_multi_server_exceeds_limit() throws InterruptedException {
        int serverCount = 3;
        int limit = 100;
        int threadCount = 300;

        FixedWindowRateLimiterV2[] servers = new FixedWindowRateLimiterV2[serverCount];
        int[] serverRequestCount = {0, 0, 0};
        for (int i = 0; i < serverCount; i++) {
            servers[i] = new FixedWindowRateLimiterV2();
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger totalAllowed = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            int serverIndex = i % serverCount;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RateLimitResult result = servers[serverIndex].tryAcquire("user:123", limit, 60);
                    if (result.allowed()) {
                        serverRequestCount[serverIndex]++;
                        totalAllowed.incrementAndGet();
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

        int allowed = totalAllowed.get();
        for (int i = 0; i < serverCount; i++) {
            System.out.println("server " + (i + 1) + ": " + serverRequestCount[i] + " requests");
        }

        System.out.println("[동시 분배] 전체 허용 수: " + allowed + " (limit: " + limit + ")");

        assertThat(allowed)
                .as("다중 서버 환경에서 전체 limit을 초과한다")
                .isGreaterThan(limit);
    }

    @Test
    @DisplayName("단일 서버 vs 다중 서버: 같은 요청 수인데 결과가 다르다")
    void single_vs_multi_server_comparison() {
        int limit = 100;
        int totalRequests = 200;

        // 단일 서버
        FixedWindowRateLimiterV2 singleServer = new FixedWindowRateLimiterV2();
        int singleAllowed = 0;
        for (int i = 0; i < totalRequests; i++) {
            RateLimitResult result = singleServer.tryAcquire("user:single", limit, 60);
            if (result.allowed()) {
                singleAllowed++;
            }
        }

        // 다중 서버 (3대)
        FixedWindowRateLimiterV2[] multiServers = {
                new FixedWindowRateLimiterV2(),
                new FixedWindowRateLimiterV2(),
                new FixedWindowRateLimiterV2()
        };
        int multiAllowed = 0;
        for (int i = 0; i < totalRequests; i++) {
            if (multiServers[i % 3].tryAcquire("user:123", limit, 60).allowed()) {
                multiAllowed++;
            }
        }

        System.out.println("단일 서버 허용: " + singleAllowed);
        System.out.println("다중 서버 허용: " + multiAllowed);

        assertThat(singleAllowed).isEqualTo(limit);
        assertThat(multiAllowed).isGreaterThan(limit);
    }

    @Test
    @DisplayName("서버 수가 늘어날수록 초과 허용 수도 비례하여 증가한다")
    void more_servers_more_exceeded() {
        int limit = 100;
        int totalRequests = 500;

        int[] serverCounts = {1, 2, 3, 5};
        int[] results = new int[serverCounts.length];

        for (int s = 0; s < serverCounts.length; s++) {
            int serverCount = serverCounts[s];
            FixedWindowRateLimiterV2[] servers = new FixedWindowRateLimiterV2[serverCount];
            for (int i = 0; i < serverCount; i++) {
                servers[i] = new FixedWindowRateLimiterV2();
            }

            int allowed = 0;
            for (int i = 0; i < totalRequests; i++) {
                if (servers[i % serverCount].tryAcquire("user:123", limit, 60).allowed()) {
                    allowed++;
                }
            }
            results[s] = allowed;
            System.out.println("서버 " + serverCount + "대 → 허용: " + allowed);
        }

        // 서버 1대: 정확히 100
        assertThat(results[0]).isEqualTo(limit);
        // 서버가 많을수록 더 많이 허용됨
        assertThat(results[1]).isGreaterThan(results[0]);
        assertThat(results[2]).isGreaterThan(results[1]);
        assertThat(results[3]).isGreaterThan(results[2]);
    }
}
