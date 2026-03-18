package com.example.ratelimiter.ratelimit.algorithm;

import com.example.ratelimiter.ratelimit.RateLimitResult;

public interface RateLimiter {

    RateLimitResult tryAcquire(String key, int limit, int windowSeconds);
}
