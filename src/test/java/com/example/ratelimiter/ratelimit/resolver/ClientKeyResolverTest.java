package com.example.ratelimiter.ratelimit.resolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientKeyResolverTest {

    private ClientKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ClientKeyResolver();
    }

    @Test
    @DisplayName("X-User-Id 헤더가 있으면 user: 키를 반환한다")
    void resolve_user_key_when_logged_in() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "123");

        String key = resolver.resolve(request);

        assertThat(key).isEqualTo("user:123");
    }

    @Test
    @DisplayName("X-User-Id 헤더가 없으면 ip: 키를 반환한다")
    void resolve_ip_key_when_anonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.0.1");

        String key = resolver.resolve(request);

        assertThat(key).isEqualTo("ip:192.168.0.1");
    }

    @Test
    @DisplayName("X-Forwarded-For 헤더가 있으면 첫 번째 IP를 사용한다")
    void resolve_first_ip_from_x_forwarded_for() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2, 10.0.0.3");

        String key = resolver.resolve(request);

        assertThat(key).isEqualTo("ip:10.0.0.1");
    }

    @Test
    @DisplayName("X-User-Id가 공백이면 IP 기준으로 식별한다")
    void resolve_ip_when_user_id_is_blank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "   ");
        request.setRemoteAddr("192.168.0.2");

        String key = resolver.resolve(request);

        assertThat(key).startsWith("ip:");
    }

    @Test
    @DisplayName("X-User-Role이 ADMIN이면 관리자로 판별한다")
    void is_admin_when_role_is_admin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Role", "ADMIN");

        assertThat(resolver.isAdmin(request)).isTrue();
    }

    @Test
    @DisplayName("X-User-Role이 ADMIN이 아니면 관리자가 아니다")
    void is_not_admin_when_role_is_user() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Role", "USER");

        assertThat(resolver.isAdmin(request)).isFalse();
    }

    @Test
    @DisplayName("X-User-Id 헤더가 있으면 인증된 사용자다")
    void is_authenticated_when_user_id_present() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "42");

        assertThat(resolver.isAuthenticated(request)).isTrue();
    }

    @Test
    @DisplayName("X-User-Id 헤더가 없으면 비인증 사용자다")
    void is_not_authenticated_when_no_user_id() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(resolver.isAuthenticated(request)).isFalse();
    }
}
