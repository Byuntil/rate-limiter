package com.example.ratelimiter.ratelimit;

import com.example.ratelimiter.config.RateLimitProperties;
import com.example.ratelimiter.dto.ErrorResponse;
import com.example.ratelimiter.ratelimit.algorithm.RateLimiter;
import com.example.ratelimiter.ratelimit.resolver.ClientKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final ClientKeyResolver keyResolver;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimiter rateLimiter,
                                ClientKeyResolver keyResolver,
                                RateLimitProperties properties,
                                ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        // 관리자는 rate limit 없이 통과
        if (keyResolver.isAdmin(request)) {
            return true;
        }

        String key = keyResolver.resolve(request);
        int limit = keyResolver.isAuthenticated(request)
                ? properties.getDefaultLimit()
                : properties.getAnonymousLimit();

        RateLimitResult result = rateLimiter.tryAcquire(key, limit, properties.getWindowSize());

        if (result.allowed()) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
            response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetAt()));
            return true;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ErrorResponse errorResponse = ErrorResponse.of(
                "RATE_LIMIT_EXCEEDED",
                "요청 한도를 초과했습니다."
        );
        objectMapper.writeValue(response.getWriter(), errorResponse);

        return false;
    }
}
