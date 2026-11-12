package io.github.ahmedmmordi.ledgerly.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedmmordi.ledgerly.identity.auth.InvalidTokenException;
import io.github.ahmedmmordi.ledgerly.identity.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("JwtService Unit Tests")
class JwtServiceTest {

    private static final String TEST_SECRET = "ZGV2LW9ubHktc2VjcmV0LWtleS0zMi1ieXRlcy1taW4h";
    private static final String DIFFERENT_SECRET = "ZGlmZmVyZW50LXNlY3JldC1rZXktMzItYnl0ZXMtbWluIQ==";
    private static final long EXPIRATION_MS = 3600000L;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationMs", EXPIRATION_MS);
    }

    @Nested
    @DisplayName("generateToken")
    class GenerateTokenTests {

        @Test
        @DisplayName("generates valid signed JWT token containing subject, email, role, and expiration")
        void withValidInputs_generatesValidSignedJwtToken() {
            UUID userId = UUID.randomUUID();
            String email = "ahmed.mordi@example.com";
            Role role = Role.USER;

            String token = jwtService.generateToken(userId, email, role);

            assertThat(token).isNotBlank();
            String[] parts = token.split("\\.");
            assertThat(parts).hasSize(3);

            Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET))).build()
                    .parseSignedClaims(token).getPayload();

            assertThat(claims.getSubject()).isEqualTo(userId.toString());
            assertThat(claims.get("email", String.class)).isEqualTo(email);
            assertThat(claims.get("role", String.class)).isEqualTo("USER");
            assertThat(claims.getIssuedAt()).isBeforeOrEqualTo(new Date());
            assertThat(claims.getExpiration()).isAfter(new Date());

            long durationMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
            assertThat(durationMs).isBetween(EXPIRATION_MS - 1000L, EXPIRATION_MS + 1000L);
        }
    }

    @Nested
    @DisplayName("extractUserId / extractRole")
    class ExtractUserIdAndRoleTests {

        @Test
        @DisplayName("extracts userId matching the token subject")
        void extractsUserIdMatchingSubject() {
            UUID userId = UUID.randomUUID();
            String token = jwtService.generateToken(userId, "ahmed.mordi@example.com", Role.USER);

            UUID extractedId = jwtService.extractUserId(token);

            assertThat(extractedId).isEqualTo(userId);
        }

        @Test
        @DisplayName("extracts role matching the token claim")
        void extractsRoleMatchingClaim() {
            UUID userId = UUID.randomUUID();
            String token = jwtService.generateToken(userId, "admin@ledgerly.com", Role.ADMIN);

            String role = jwtService.extractRole(token);

            assertThat(role).isEqualTo("ADMIN");
        }
    }

    @Nested
    @DisplayName("parseToken")
    class ParseTokenTests {

        @Test
        @DisplayName("throws InvalidTokenException when token payload has been tampered with")
        void withTamperedPayload_throwsInvalidTokenException() {
            UUID userId = UUID.randomUUID();
            String token = jwtService.generateToken(userId, "ahmed.mordi@example.com", Role.USER);
            String[] parts = token.split("\\.");

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.replace("USER", "ADMIN").getBytes(StandardCharsets.UTF_8));
            assertThat(forgedPayload).isNotEqualTo(parts[1]);

            String tamperedToken = parts[0] + "." + forgedPayload + "." + parts[2];

            assertThatThrownBy(() -> jwtService.parseToken(tamperedToken)).isInstanceOf(InvalidTokenException.class)
                    .hasMessage("Invalid or expired token");
        }

        @Test
        @DisplayName("throws InvalidTokenException when token is unsigned")
        void withUnsignedToken_throwsInvalidTokenException() {
            UUID userId = UUID.randomUUID();
            String unsignedToken = Jwts.builder().subject(userId.toString()).claim("email", "ahmed.mordi@example.com")
                    .claim("role", "ADMIN").compact();

            assertThatThrownBy(() -> jwtService.parseToken(unsignedToken)).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("throws InvalidTokenException when token was signed with different secret")
        void withDifferentSecret_throwsInvalidTokenException() {
            String foreignToken = buildSignedToken(DIFFERENT_SECRET, new Date(),
                    new Date(System.currentTimeMillis() + EXPIRATION_MS));

            assertThatThrownBy(() -> jwtService.parseToken(foreignToken)).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("throws InvalidTokenException when token is expired")
        void withExpiredToken_throwsInvalidTokenException() {
            String expiredToken = buildSignedToken(TEST_SECRET, new Date(System.currentTimeMillis() - 10000),
                    new Date(System.currentTimeMillis() - 1000));

            assertThatThrownBy(() -> jwtService.parseToken(expiredToken)).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("throws InvalidTokenException when token is malformed")
        void withMalformedToken_throwsInvalidTokenException() {
            assertThatThrownBy(() -> jwtService.parseToken("not.a.valid.jwt"))
                    .isInstanceOf(InvalidTokenException.class);
        }
    }

    private String buildSignedToken(String secret, Date issuedAt, Date expiration) {
        return Jwts.builder().subject(UUID.randomUUID().toString()).claim("email", "ahmed.mordi@example.com")
                .claim("role", "USER").issuedAt(issuedAt).expiration(expiration)
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))).compact();
    }
}
