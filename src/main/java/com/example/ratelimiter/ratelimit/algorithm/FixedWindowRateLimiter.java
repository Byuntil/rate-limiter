package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;

public class FixedWindowRateLimiter implements RateLimiter{
    @Override
    public RateLimitResult tryAcquire(String key, int limit, int windowSeconds) {
        return null;
    }
}
