package com.example.ratelimiter.global;

import com.example.ratelimiter.dto.ErrorResponse;
import com.example.ratelimiter.ratelimit.RateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    @ExceptionHandler(RateLimitExceededException.class)
    public ErrorResponse handleRateLimitExceeded(RateLimitExceededException e) {
        return ErrorResponse.of("RATE_LIMIT_EXCEEDED", e.getMessage());
    }
}
