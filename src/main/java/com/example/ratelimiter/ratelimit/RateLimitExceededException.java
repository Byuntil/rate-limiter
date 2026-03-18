package com.example.ratelimiter.ratelimit;

public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException() {
        super("요청 한도를 초과했습니다.");
    }
}
