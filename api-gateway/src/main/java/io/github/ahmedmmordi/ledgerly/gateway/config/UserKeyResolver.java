package io.github.ahmedmmordi.ledgerly.gateway.config;

import static io.github.ahmedmmordi.ledgerly.gateway.config.JwtAuthenticationWebFilter.HEADER_USER_ID;

import java.net.InetSocketAddress;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@NullMarked
@SuppressWarnings({ "UnknownHttpHeader", "IncorrectHttpHeaderInspection" })
public class UserKeyResolver implements KeyResolver {

    private static final String ANONYMOUS_KEY = "anonymous";
    private static final String USER_PREFIX = "user:";
    private static final String IP_PREFIX = "ip:";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders().getFirst(HEADER_USER_ID);
        if (userId != null && !userId.isBlank()) {
            return Mono.just(USER_PREFIX + userId.trim());
        }

        String clientIp = resolveClientIp(exchange);
        if (clientIp != null && !clientIp.isBlank()) {
            return Mono.just(IP_PREFIX + clientIp.trim());
        }

        return Mono.just(ANONYMOUS_KEY);
    }

    @Nullable
    private String resolveClientIp(ServerWebExchange exchange) {
        String forwardedFor = exchange.getRequest().getHeaders().getFirst(FORWARDED_FOR_HEADER);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            for (String part : forwardedFor.split(",")) {
                String candidate = part.trim();
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }

        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null) {
            if (remoteAddress.getAddress() != null) {
                return remoteAddress.getAddress().getHostAddress();
            }
            return remoteAddress.getHostString();
        }

        return null;
    }
}
