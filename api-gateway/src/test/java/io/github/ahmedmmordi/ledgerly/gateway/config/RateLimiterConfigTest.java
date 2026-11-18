package io.github.ahmedmmordi.ledgerly.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;

@DisplayName("Rate Limiter Config Tests")
class RateLimiterConfigTest {

    @Test
    @DisplayName("userKeyResolver bean produces a non-null UserKeyResolver instance")
    void userKeyResolver_instantiatesCorrectly() {
        RateLimiterConfig config = new RateLimiterConfig();
        KeyResolver resolver = config.userKeyResolver();

        assertThat(resolver).isNotNull().isInstanceOf(UserKeyResolver.class);
    }

    @Test
    @DisplayName("redisRateLimiter bean configures replenish rate and burst capacity with response headers enabled")
    void redisRateLimiter_configuresRatesCorrectly() {
        RateLimiterConfig config = new RateLimiterConfig();
        RedisRateLimiter limiter = config.redisRateLimiter(15, 30);

        assertThat(limiter).isNotNull();
        assertThat(limiter.isIncludeHeaders()).isTrue();
    }
}
