package io.github.ahmedmmordi.ledgerly.gateway.config;

import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@NullMarked
@Slf4j
@Component
@SuppressWarnings({ "UnknownHttpHeader", "UastIncorrectHttpHeaderInspection", "IncorrectHttpHeaderInspection" })
public class JwtAuthenticationWebFilter implements WebFilter, Ordered {

    @SuppressWarnings({ "UnknownHttpHeader", "UastIncorrectHttpHeaderInspection" })
    public static final String HEADER_USER_ID = "X-User-Id";

    @SuppressWarnings({ "UnknownHttpHeader", "UastIncorrectHttpHeaderInspection" })
    public static final String HEADER_USER_ROLES = "X-User-Roles";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int FILTER_ORDER = -100;

    private final JwtService jwtService;

    public JwtAuthenticationWebFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        if (isPublicPath(request)) {
            ServerHttpRequest strippedRequest = request.mutate().headers(httpHeaders -> {
                httpHeaders.remove(HEADER_USER_ID);
                httpHeaders.remove(HEADER_USER_ROLES);
            }).build();
            return chain.filter(exchange.mutate().request(strippedRequest).build());
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !startsWithBearer(authHeader)) {
            log.warn("Missing or malformed Authorization header on protected path: {}", request.getPath().value());
            return writeUnauthorizedResponse(exchange, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            log.warn("Empty Bearer token on protected path: {}", request.getPath().value());
            return writeUnauthorizedResponse(exchange, "Missing or invalid Authorization header");
        }

        try {
            Claims claims = jwtService.parseToken(token);
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                log.warn("JWT subject is missing or blank on path: {}", request.getPath().value());
                return writeUnauthorizedResponse(exchange, "Invalid or expired JWT token");
            }

            UUID userId = UUID.fromString(subject);
            String role = extractRole(claims);

            ServerHttpRequest mutatedRequest = request.mutate().headers(httpHeaders -> {
                httpHeaders.set(HEADER_USER_ID, userId.toString());
                if (role != null) {
                    httpHeaders.set(HEADER_USER_ROLES, role);
                } else {
                    httpHeaders.remove(HEADER_USER_ROLES);
                }
            }).build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (Exception ex) {
            log.warn("JWT validation failed for path {}: {}", request.getPath().value(), ex.getMessage());
            return writeUnauthorizedResponse(exchange, "Invalid or expired JWT token");
        }
    }

    @Nullable
    private String extractRole(Claims claims) {
        if (claims.get("role") instanceof String roleStr && !roleStr.isBlank()) {
            return roleStr.trim();
        }
        if (claims.get("roles") instanceof String rolesStr && !rolesStr.isBlank()) {
            return rolesStr.trim();
        }
        return null;
    }

    private static boolean startsWithBearer(String header) {
        return header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
    }

    private boolean isPublicPath(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        if (HttpMethod.OPTIONS.equals(method)) {
            return true;
        }

        String rawPath = request.getPath().value();
        String path = (rawPath.endsWith("/") && rawPath.length() > 1) ? rawPath.substring(0, rawPath.length() - 1)
                : rawPath;

        if (HttpMethod.POST.equals(method) && (path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/login")
                || path.equals("/auth/register") || path.equals("/auth/login"))) {
            return true;
        }

        return path.startsWith("/actuator") || path.startsWith("/swagger") || path.startsWith("/api-docs")
                || path.startsWith("/v3/api-docs") || path.equals("/favicon.ico");
    }

    private Mono<Void> writeUnauthorizedResponse(ServerWebExchange exchange, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String sanitizedPath = exchange.getRequest().getPath().value().replace("\"", "\\\"");
        String body = String.format(
                "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"%s\",\"instance\":\"%s\"}",
                detail, sanitizedPath);

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
