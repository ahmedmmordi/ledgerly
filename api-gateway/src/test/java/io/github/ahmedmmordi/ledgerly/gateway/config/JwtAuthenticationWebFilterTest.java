package io.github.ahmedmmordi.ledgerly.gateway.config;

import static io.github.ahmedmmordi.ledgerly.gateway.config.JwtAuthenticationWebFilter.HEADER_USER_ID;
import static io.github.ahmedmmordi.ledgerly.gateway.config.JwtAuthenticationWebFilter.HEADER_USER_ROLES;
import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("JWT Authentication WebFilter Tests")
@SuppressWarnings({ "UnknownHttpHeader", "IncorrectHttpHeaderInspection" })
class JwtAuthenticationWebFilterTest {

    private static final String SECRET = "ZGV2LW9ubHktc2VjcmV0LWtleS0zMi1ieXRlcy1taW4h";
    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    private static final UUID TEST_USER_ID = UUID.fromString("01932b1c-7f20-711e-8422-79e0a0d927d1");
    private static final long DEFAULT_TTL_MS = 3_600_000L;

    private JwtAuthenticationWebFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationWebFilter(new JwtService(SECRET));
    }

    private String createToken(String role) {
        return createToken(role, DEFAULT_TTL_MS);
    }

    private String createToken(String role, long ttlMs) {
        return buildToken(TEST_USER_ID.toString(), "role", role, ttlMs);
    }

    private String buildToken(@Nullable String subject, @Nullable String claimName, @Nullable Object claimValue,
            long ttlMs) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder().issuedAt(new Date(now)).expiration(new Date(now + ttlMs)).signWith(SIGNING_KEY);
        if (subject != null) {
            builder.subject(subject);
        }
        if (claimName != null && claimValue != null) {
            builder.claim(claimName, claimValue);
        }
        return builder.compact();
    }

    @Test
    @DisplayName("Should return order of -100 to ensure early filter execution")
    void shouldHaveCorrectFilterOrder() {
        assertThat(filter.getOrder()).isEqualTo(-100);
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/auth/register", "/api/v1/auth/login", "/auth/register", "/auth/login" })
    @DisplayName("Should bypass authentication for public auth endpoints on POST")
    void shouldBypassAuthenticationForPublicAuthEndpoints(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "/actuator/health", "/actuator/info", "/swagger/index.html", "/api-docs/swagger-config",
            "/v3/api-docs", "/favicon.ico" })
    @DisplayName("Should bypass authentication for public utility endpoints on GET")
    void shouldBypassAuthenticationForPublicUtilityEndpoints(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Should reject protected endpoint with 401 when Authorization header is missing")
    void shouldRejectWhenAuthorizationHeaderIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange
                .from(MockServerHttpRequest.get("/api/v1/users/me").build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody).contains("\"status\":401");
        assertThat(responseBody).contains("Missing or invalid Authorization header");
        assertThat(responseBody).contains("\"instance\":\"/api/v1/users/me\"");
    }

    @Test
    @DisplayName("Should reject protected endpoint with 401 when Authorization header lacks Bearer prefix")
    void shouldRejectWhenAuthorizationHeaderLacksBearerPrefix() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz").build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should reject protected endpoint with 401 when token is malformed")
    void shouldRejectWhenTokenIsMalformed() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-malformed-token").build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody).contains("\"status\":401");
        assertThat(responseBody).contains("Invalid or expired JWT token");
    }

    @Test
    @DisplayName("Should reject protected endpoint with 401 when token is expired")
    void shouldRejectWhenTokenIsExpired() {
        String expiredToken = createToken("USER", -10_000L);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody).contains("Invalid or expired JWT token");
    }

    @Test
    @DisplayName("Should validate token and inject X-User-Id and X-User-Roles headers on mutated request")
    void shouldInjectHeadersWhenTokenIsValid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + createToken("ADMIN")).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ID))
                .isEqualTo(TEST_USER_ID.toString());
        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ROLES)).isEqualTo("ADMIN");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Should allow CORS preflight OPTIONS requests without authentication")
    void shouldAllowCorsPreflightOptionsRequestsWithoutAuthentication() {
        MockServerWebExchange exchange = MockServerWebExchange
                .from(MockServerHttpRequest.options("/api/v1/accounts").build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Should reject protected endpoint with 401 when Bearer token is empty")
    void shouldRejectWhenBearerTokenIsEmpty() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer    ").build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should strip spoofed X-User-Id and X-User-Roles headers on public endpoints")
    void shouldStripSpoofedUserHeadersOnPublicEndpoints() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/register")
                .header(HEADER_USER_ID, "spoofed-user-id").header(HEADER_USER_ROLES, "ADMIN").build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.getDownstreamRequest().getHeaders().get(HEADER_USER_ID)).isNull();
        assertThat(chain.getDownstreamRequest().getHeaders().get(HEADER_USER_ROLES)).isNull();
    }

    @Test
    @DisplayName("Should overwrite any existing spoofed X-User-Id and X-User-Roles headers on authenticated requests")
    void shouldOverwriteExistingSpoofedHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + createToken("USER"))
                .header(HEADER_USER_ID, "spoofed-hacker-id").header(HEADER_USER_ROLES, "ADMIN").build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.getDownstreamRequest().getHeaders().get(HEADER_USER_ID))
                .containsExactly(TEST_USER_ID.toString());
        assertThat(chain.getDownstreamRequest().getHeaders().get(HEADER_USER_ROLES)).containsExactly("USER");
    }

    @Test
    @DisplayName("Should remove spoofed X-User-Roles header when authenticated token lacks role claim")
    void shouldRemoveSpoofedRolesHeaderWhenTokenLacksRole() {
        String tokenWithoutRole = buildToken(TEST_USER_ID.toString(), null, null, DEFAULT_TTL_MS);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutRole)
                .header(HEADER_USER_ROLES, "SPOOFED_ADMIN").build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ID))
                .isEqualTo(TEST_USER_ID.toString());
        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ROLES)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "bearer ", "Bearer ", "BEARER " })
    @DisplayName("Should accept case-insensitive Bearer scheme per RFC 6750")
    void shouldAcceptCaseInsensitiveBearerScheme(String prefix) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, prefix + createToken("USER")).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ID))
                .isEqualTo(TEST_USER_ID.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/auth/register/", "/api/v1/auth/login/" })
    @DisplayName("Should bypass public auth endpoints even with trailing slash")
    void shouldBypassPublicEndpointsWithTrailingSlash(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Should extract role from fallback roles claim when role claim is absent")
    void shouldExtractRoleFromFallbackRolesClaim() {
        String tokenWithRoles = buildToken(TEST_USER_ID.toString(), "roles", "USER", DEFAULT_TTL_MS);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithRoles).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ID))
                .isEqualTo(TEST_USER_ID.toString());
        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ROLES)).isEqualTo("USER");
    }

    @Test
    @DisplayName("Should omit X-User-Roles when token has no role or roles claim")
    void shouldOmitRolesHeaderWhenRoleClaimIsMissing() {
        String tokenWithoutRole = buildToken(TEST_USER_ID.toString(), null, null, DEFAULT_TTL_MS);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutRole).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ID))
                .isEqualTo(TEST_USER_ID.toString());
        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ROLES)).isNull();
    }

    @Test
    @DisplayName("Should omit X-User-Roles when role claim is blank")
    void shouldOmitRolesHeaderWhenRoleClaimIsBlank() {
        String tokenWithBlankRole = buildToken(TEST_USER_ID.toString(), "role", "   ", DEFAULT_TTL_MS);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithBlankRole).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ID))
                .isEqualTo(TEST_USER_ID.toString());
        assertThat(chain.getDownstreamRequest().getHeaders().getFirst(HEADER_USER_ROLES)).isNull();
    }

    @Test
    @DisplayName("Should reject protected endpoint with 401 when token subject is not a valid UUID")
    void shouldRejectWhenSubjectIsNotValidUuid() {
        String tokenWithInvalidSubject = buildToken("not-a-valid-uuid", "role", "USER", DEFAULT_TTL_MS);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithInvalidSubject).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should reject protected endpoint with 401 when token subject is blank")
    void shouldRejectWhenSubjectIsBlank() {
        String tokenWithBlankSubject = buildToken("   ", "role", "USER", DEFAULT_TTL_MS);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithBlankSubject).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should reject protected endpoint with 401 when token subject is null")
    void shouldRejectWhenSubjectIsNull() {
        String tokenWithoutSubject = buildToken(null, "role", "USER", DEFAULT_TTL_MS);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutSubject).build());
        TrackingWebFilterChain chain = new TrackingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.wasInvoked()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @NullMarked
    private static class TrackingWebFilterChain implements WebFilterChain {
        private final AtomicBoolean invoked = new AtomicBoolean(false);
        private final AtomicReference<@Nullable ServerHttpRequest> downstreamRequest = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            invoked.set(true);
            downstreamRequest.set(exchange.getRequest());
            return Mono.empty();
        }

        public boolean wasInvoked() {
            return invoked.get();
        }

        public ServerHttpRequest getDownstreamRequest() {
            ServerHttpRequest request = downstreamRequest.get();
            if (request == null) {
                throw new IllegalStateException("Downstream request was not captured; chain was not invoked");
            }
            return request;
        }
    }
}
