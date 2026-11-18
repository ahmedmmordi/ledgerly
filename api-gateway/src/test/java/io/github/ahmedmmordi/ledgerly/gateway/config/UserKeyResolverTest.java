package io.github.ahmedmmordi.ledgerly.gateway.config;

import static io.github.ahmedmmordi.ledgerly.gateway.config.JwtAuthenticationWebFilter.HEADER_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

@DisplayName("User Key Resolver Tests")
@SuppressWarnings({ "UnknownHttpHeader", "IncorrectHttpHeaderInspection" })
class UserKeyResolverTest {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String SAMPLE_USER_ID = "01932b1c-7f20-711e-8422-79e0a0d927d1";

    private UserKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new UserKeyResolver();
    }

    private void assertResolvedKey(MockServerHttpRequest request, String expectedKey) {
        var exchange = MockServerWebExchange.from(request);
        StepVerifier.create(resolver.resolve(exchange)).expectNext(expectedKey).verifyComplete();
    }

    @Nested
    @DisplayName("Authenticated User Resolution")
    class AuthenticatedUserResolution {

        @Test
        @DisplayName("Resolves to 'user:<id>' when X-User-Id header is present")
        void resolve_withValidUserId_returnsUserKey() {
            var request = MockServerHttpRequest.get("/api/v1/accounts").header(HEADER_USER_ID, SAMPLE_USER_ID).build();
            assertResolvedKey(request, "user:" + SAMPLE_USER_ID);
        }

        @Test
        @DisplayName("Trims whitespace in X-User-Id header")
        void resolve_withWhitespaceInUserId_trimsAndReturnsUserKey() {
            var request = MockServerHttpRequest.get("/api/v1/accounts")
                    .header(HEADER_USER_ID, "   " + SAMPLE_USER_ID + "   ").build();
            assertResolvedKey(request, "user:" + SAMPLE_USER_ID);
        }

        @Test
        @DisplayName("User ID takes precedence over X-Forwarded-For and remote address")
        void resolve_withBothUserIdAndIpSources_prefersUserId() {
            var request = MockServerHttpRequest.get("/api/v1/accounts").header(HEADER_USER_ID, SAMPLE_USER_ID)
                    .header(FORWARDED_FOR, "203.0.113.195").remoteAddress(new InetSocketAddress("192.0.2.1", 54321))
                    .build();
            assertResolvedKey(request, "user:" + SAMPLE_USER_ID);
        }
    }

    @Nested
    @DisplayName("Forwarded IP Resolution")
    class ForwardedIpResolution {

        @Test
        @DisplayName("Resolves to 'ip:<ip>' when X-Forwarded-For has a single IP")
        void resolve_withSingleForwardedForIp_returnsIpKey() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").header(FORWARDED_FOR, "198.51.100.42")
                    .build();
            assertResolvedKey(request, "ip:198.51.100.42");
        }

        @Test
        @DisplayName("Trims whitespace in single X-Forwarded-For IP")
        void resolve_withWhitespaceInSingleForwardedForIp_trimsAndReturnsIpKey() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").header(FORWARDED_FOR, "   198.51.100.42   ")
                    .build();
            assertResolvedKey(request, "ip:198.51.100.42");
        }

        @Test
        @DisplayName("Resolves to first IP when X-Forwarded-For contains a proxy chain")
        void resolve_withMultipleForwardedForIps_returnsFirstClientIp() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login")
                    .header(FORWARDED_FOR, "198.51.100.42, 10.0.0.1, 172.16.0.2").build();
            assertResolvedKey(request, "ip:198.51.100.42");
        }

        @Test
        @DisplayName("Skips leading empty/whitespace entries in X-Forwarded-For proxy chain")
        void resolve_withLeadingEmptyEntryInForwardedFor_skipsAndReturnsFirstValidIp() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login")
                    .header(FORWARDED_FOR, "  ,  198.51.100.42 , 10.0.0.1").build();
            assertResolvedKey(request, "ip:198.51.100.42");
        }

        @Test
        @DisplayName("X-Forwarded-For takes precedence over remote TCP address")
        void resolve_withForwardedForAndRemoteAddress_prefersForwardedFor() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").header(FORWARDED_FOR, "198.51.100.42")
                    .remoteAddress(new InetSocketAddress("10.0.0.50", 8080)).build();
            assertResolvedKey(request, "ip:198.51.100.42");
        }

        @Test
        @DisplayName("Falls through to X-Forwarded-For when X-User-Id is blank")
        void resolve_withBlankUserIdAndValidForwardedFor_resolvesToForwardedIp() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").header(HEADER_USER_ID, "   ")
                    .header(FORWARDED_FOR, "198.51.100.42").build();
            assertResolvedKey(request, "ip:198.51.100.42");
        }

        @Test
        @DisplayName("Falls through to remote address when X-Forwarded-For is all commas and whitespace")
        void resolve_withEmptyTokensInForwardedFor_fallsThroughToRemoteAddress() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").header(FORWARDED_FOR, ",  , ,  ")
                    .remoteAddress(new InetSocketAddress("192.0.2.88", 9090)).build();
            assertResolvedKey(request, "ip:192.0.2.88");
        }
    }

    @Nested
    @DisplayName("Remote Socket Address Resolution")
    class RemoteAddressResolution {

        @Test
        @DisplayName("Resolves to 'ip:<hostAddress>' for standard IPv4 socket address")
        void resolve_withStandardRemoteAddress_returnsIpKey() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login")
                    .remoteAddress(new InetSocketAddress("192.0.2.100", 12345)).build();
            assertResolvedKey(request, "ip:192.0.2.100");
        }

        @Test
        @DisplayName("Resolves to IPv6 address for IPv6 socket address")
        void resolve_withIPv6RemoteAddress_returnsHostAddress() throws Exception {
            InetAddress ipv6 = InetAddress.getByName("2001:db8::1");
            var request = MockServerHttpRequest.get("/api/v1/auth/login")
                    .remoteAddress(new InetSocketAddress(ipv6, 12345)).build();
            var exchange = MockServerWebExchange.from(request);

            StepVerifier.create(resolver.resolve(exchange))
                    .assertNext(key -> assertThat(key).startsWith("ip:").contains("2001:db8")).verifyComplete();
        }

        @Test
        @DisplayName("Resolves to hostString when socket address cannot resolve InetAddress")
        void resolve_withUnresolvedSocketAddress_returnsHostString() {
            InetSocketAddress unresolved = InetSocketAddress.createUnresolved("edge-proxy.internal", 443);
            assertThat(unresolved.getAddress()).isNull();

            var request = MockServerHttpRequest.get("/api/v1/auth/login").remoteAddress(unresolved).build();
            assertResolvedKey(request, "ip:edge-proxy.internal");
        }

        @Test
        @DisplayName("Falls through to remote address when both X-User-Id and X-Forwarded-For are blank")
        void resolve_withBlankHeadersAndValidRemoteAddress_resolvesToRemoteAddress() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").header(HEADER_USER_ID, "   ")
                    .header(FORWARDED_FOR, "   ").remoteAddress(new InetSocketAddress("192.0.2.100", 12345)).build();
            assertResolvedKey(request, "ip:192.0.2.100");
        }
    }

    @Nested
    @DisplayName("Anonymous Fallback")
    class AnonymousFallback {

        @Test
        @DisplayName("Resolves to 'anonymous' when neither headers nor remote address are present")
        void resolve_withNoHeadersAndNoRemoteAddress_returnsAnonymous() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").build();
            assertResolvedKey(request, "anonymous");
        }

        @ParameterizedTest(name = "Resolves to anonymous when X-User-Id is blank: ''{0}''")
        @ValueSource(strings = { "", "   ", "\t", "\n" })
        void resolve_withBlankUserIdAndNoRemoteAddress_returnsAnonymous(String blankValue) {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").header(HEADER_USER_ID, blankValue).build();
            assertResolvedKey(request, "anonymous");
        }

        @ParameterizedTest(name = "Resolves to anonymous when X-Forwarded-For is blank: ''{0}''")
        @ValueSource(strings = { "", "   ", "\t", "\n" })
        void resolve_withBlankForwardedForAndNoRemoteAddress_returnsAnonymous(String blankValue) {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").header(FORWARDED_FOR, blankValue).build();
            assertResolvedKey(request, "anonymous");
        }

        @Test
        @DisplayName("Resolves to anonymous when both headers are empty and remote address is null")
        void resolve_withAllBlankHeadersAndNullRemoteAddress_returnsAnonymous() {
            var request = MockServerHttpRequest.get("/api/v1/auth/login").header(HEADER_USER_ID, "   ")
                    .header(FORWARDED_FOR, "   ").build();
            assertResolvedKey(request, "anonymous");
        }
    }
}
