package com.example.ratelimiter.controller;

import com.example.ratelimiter.domain.post.Post;
import com.example.ratelimiter.domain.post.PostRepository;
import com.example.ratelimiter.dto.PostResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PostController {

    private final PostRepository postRepository;

    @GetMapping("/posts/{postId}")
    public ResponseEntity<PostResponse> getPost(@PathVariable Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시글을 찾을 수 없습니다. id=" + postId));
        return ResponseEntity.ok(PostResponse.from(post));
    }
}
