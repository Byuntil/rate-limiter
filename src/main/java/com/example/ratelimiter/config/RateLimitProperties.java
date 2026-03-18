package com.example.ratelimiter.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private int defaultLimit = 100;
    private int anonymousLimit = 30;
    private int windowSize = 60;
}
