package com.example.ratelimiter.ratelimit;

public record RateLimitResult(
        boolean allowed,
        int limit,
        int remaining,
        long resetAt
) {
    public static RateLimitResult allowed(int limit, int remaining, long resetAt) {
        return new RateLimitResult(true, limit, remaining, resetAt);
    }

    public static RateLimitResult blocked(int limit, long resetAt) {
        return new RateLimitResult(false, limit, 0, resetAt);
    }
}
