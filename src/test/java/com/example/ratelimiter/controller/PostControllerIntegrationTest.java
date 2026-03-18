package com.example.ratelimiter.controller;

import com.example.ratelimiter.domain.post.Post;
import com.example.ratelimiter.domain.post.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PostControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostRepository postRepository;

    private Long postId;

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        Post post = postRepository.save(new Post("테스트 제목", "테스트 내용", "작성자"));
        postId = post.getId();
    }

    @Test
    @DisplayName("로그인 사용자는 게시글을 정상 조회할 수 있다")
    void get_post_success_for_authenticated_user() throws Exception {
        mockMvc.perform(get("/posts/{id}", postId)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(postId))
                .andExpect(jsonPath("$.title").value("테스트 제목"))
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"));
    }

    @Test
    @DisplayName("비로그인 사용자는 게시글을 정상 조회할 수 있다")
    void get_post_success_for_anonymous_user() throws Exception {
        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    @Test
    @DisplayName("로그인 사용자의 X-RateLimit-Limit은 100이다")
    void rate_limit_header_is_100_for_authenticated_user() throws Exception {
        mockMvc.perform(get("/posts/{id}", postId)
                        .header("X-User-Id", "99"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "100"));
    }

    @Test
    @DisplayName("비로그인 사용자의 X-RateLimit-Limit은 30이다")
    void rate_limit_header_is_30_for_anonymous_user() throws Exception {
        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "30"));
    }

    @Test
    @DisplayName("관리자는 제한 횟수를 초과해도 허용된다")
    void admin_is_not_rate_limited() throws Exception {
        for (int i = 0; i < 35; i++) {
            mockMvc.perform(get("/posts/{id}", postId)
                            .header("X-User-Id", "admin")
                            .header("X-User-Role", "ADMIN"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("비로그인 사용자가 30회 초과하면 429를 반환한다")
    void anonymous_user_blocked_after_30_requests() throws Exception {
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/posts/{id}", postId)
                            .with(request -> {
                                request.setRemoteAddr("10.0.0.99");
                                return request;
                            }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/posts/{id}", postId)
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.99");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("서로 다른 사용자의 요청 횟수는 독립적으로 관리된다")
    void different_users_have_independent_counters() throws Exception {
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/posts/{id}", postId)
                            .header("X-User-Id", "user-limit-test"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/posts/{id}", postId)
                        .header("X-User-Id", "user-other"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("존재하지 않는 게시글 조회 시 400을 반환한다")
    void return_400_when_post_not_found() throws Exception {
        mockMvc.perform(get("/posts/{id}", 9999L)
                        .header("X-User-Id", "1"))
                .andExpect(status().isBadRequest());
    }
}
