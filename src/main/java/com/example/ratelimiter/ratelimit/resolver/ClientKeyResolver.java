package com.example.ratelimiter.ratelimit.resolver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ClientKeyResolver {

    public String resolve(HttpServletRequest request) {
        // 1순위: 로그인 사용자 (헤더에서 userId 추출 - 실제로는 인증 정보에서 가져옴)
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }

        // 2순위: 비로그인 사용자 (IP 기준)
        String clientIp = getClientIp(request);
        return "ip:" + clientIp;
    }

    public boolean isAdmin(HttpServletRequest request) {
        String role = request.getHeader("X-User-Role");
        return "ADMIN".equalsIgnoreCase(role);
    }

    public boolean isAuthenticated(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        return userId != null && !userId.isBlank();
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
