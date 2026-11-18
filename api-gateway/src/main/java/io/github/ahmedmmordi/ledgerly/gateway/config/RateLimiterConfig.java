package io.github.ahmedmmordi.ledgerly.gateway.config;

import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@NullMarked
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfig {

    @Bean
    public RedisRateLimiter redisRateLimiter(@Value("${app.rate-limiting.replenish-rate:10}") int replenishRate,
            @Value("${app.rate-limiting.burst-capacity:20}") int burstCapacity) {
        return new RedisRateLimiter(replenishRate, burstCapacity);
    }

    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return new UserKeyResolver();
    }
}
