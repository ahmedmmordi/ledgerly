package io.github.ahmedmmordi.ledgerly.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedmmordi.ledgerly.identity.auth.dto.AuthResponse;
import io.github.ahmedmmordi.ledgerly.identity.auth.dto.LoginRequest;
import io.github.ahmedmmordi.ledgerly.identity.auth.dto.RegisterRequest;
import io.github.ahmedmmordi.ledgerly.identity.user.Role;
import io.github.ahmedmmordi.ledgerly.identity.user.User;
import io.github.ahmedmmordi.ledgerly.identity.user.UserRepository;
import io.github.ahmedmmordi.ledgerly.identity.user.dto.UserResponse;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Identity Service Authentication Integration Tests")
class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("JWT_SECRET", () -> "ZGV2LW9ubHktc2VjcmV0LWtleS0zMi1ieXRlcy1taW4h");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    record HttpResponse<T>(HttpStatusCode status, T body, ProblemDetail problem) {
    }

    private <T> HttpResponse<T> postJson(String uri, Object body, Class<T> responseType) {
        return restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return new HttpResponse<>(status, response.bodyTo(responseType), null);
                    } else {
                        return new HttpResponse<>(status, null, response.bodyTo(ProblemDetail.class));
                    }
                });
    }

    private HttpResponse<UserResponse> getMe(String authHeaderValue) {
        var spec = restClient.get().uri("/api/v1/users/me");
        if (authHeaderValue != null) {
            spec.header(HttpHeaders.AUTHORIZATION, authHeaderValue);
        }
        return spec.exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            if (status.is2xxSuccessful()) {
                return new HttpResponse<>(status, response.bodyTo(UserResponse.class), null);
            }
            ProblemDetail problem = null;
            try {
                problem = response.bodyTo(ProblemDetail.class);
            } catch (Exception ignored) {
            }
            return new HttpResponse<>(status, null, problem);
        });
    }

    @Test
    @DisplayName("executes end-to-end registration, login, and authenticated user profile retrieval")
    void shouldCompleteFullAuthenticationWorkflow() {
        RegisterRequest registerReq = new RegisterRequest("integration@ledgerly.com", "Password123!", "Integration",
                "User");
        HttpResponse<AuthResponse> registerRes = postJson("/api/v1/auth/register", registerReq, AuthResponse.class);

        assertThat(registerRes.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerRes.body()).isNotNull();
        assertThat(registerRes.body().email()).isEqualTo("integration@ledgerly.com");
        assertThat(registerRes.body().token()).isNotBlank();

        User dbUser = userRepository.findByEmailIgnoreCase("integration@ledgerly.com").orElseThrow();
        assertThat(dbUser.getPasswordHash()).startsWith("$2a$12$").isNotEqualTo("Password123!");

        LoginRequest loginReq = new LoginRequest("integration@ledgerly.com", "Password123!");
        HttpResponse<AuthResponse> loginRes = postJson("/api/v1/auth/login", loginReq, AuthResponse.class);

        assertThat(loginRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(loginRes.body()).isNotNull();
        assertThat(loginRes.body().token()).isNotBlank();

        HttpResponse<UserResponse> meRes = getMe("Bearer " + loginRes.body().token());

        assertThat(meRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(meRes.body()).isNotNull();
        assertThat(meRes.body().email()).isEqualTo("integration@ledgerly.com");
        assertThat(meRes.body().firstName()).isEqualTo("Integration");
        assertThat(meRes.body().lastName()).isEqualTo("User");
        assertThat(meRes.body().role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("enforces email case-insensitivity on login and duplicate registration rejection")
    void shouldHandleEmailCaseInsensitivityAndDuplicateRejection() {
        RegisterRequest registerReq = new RegisterRequest("Upper.Case@LEDGERLY.COM", "Password123!", "Upper", "Case");
        HttpResponse<AuthResponse> registerRes = postJson("/api/v1/auth/register", registerReq, AuthResponse.class);
        assertThat(registerRes.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerRes.body().email()).isEqualTo("upper.case@ledgerly.com");

        LoginRequest loginReq = new LoginRequest("upper.case@ledgerly.com", "Password123!");
        HttpResponse<AuthResponse> loginRes = postJson("/api/v1/auth/login", loginReq, AuthResponse.class);
        assertThat(loginRes.status()).isEqualTo(HttpStatus.OK);

        RegisterRequest duplicateReq = new RegisterRequest("UPPER.CASE@ledgerly.com", "Password123!", "Another",
                "User");
        HttpResponse<AuthResponse> duplicateRes = postJson("/api/v1/auth/register", duplicateReq, AuthResponse.class);

        assertThat(duplicateRes.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateRes.problem()).isNotNull();
        assertThat(duplicateRes.problem().getTitle()).isEqualTo("User Already Exists");
        assertThat(duplicateRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:user-already-exists"));
    }

    @Test
    @DisplayName("rejects invalid registration payload with 400 Bad Request and validation problem detail")
    void shouldRejectInvalidRegistrationPayload() {
        RegisterRequest invalidReq = new RegisterRequest("invalid-email-format", "short", "", "   ");
        HttpResponse<AuthResponse> response = postJson("/api/v1/auth/register", invalidReq, AuthResponse.class);

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.problem()).isNotNull();
        assertThat(response.problem().getTitle()).isEqualTo("Validation Failed");
        assertThat(response.problem().getType()).isEqualTo(URI.create("urn:problem-type:validation-error"));
        assertThat(response.problem().getDetail()).contains("email", "password", "firstName", "lastName");
    }

    @Test
    @DisplayName("rejects invalid login payload with 400 Bad Request and validation problem detail")
    void shouldRejectInvalidLoginPayload() {
        LoginRequest invalidReq = new LoginRequest("invalid-email-format", "");
        HttpResponse<AuthResponse> response = postJson("/api/v1/auth/login", invalidReq, AuthResponse.class);

        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.problem()).isNotNull();
        assertThat(response.problem().getTitle()).isEqualTo("Validation Failed");
        assertThat(response.problem().getType()).isEqualTo(URI.create("urn:problem-type:validation-error"));
        assertThat(response.problem().getDetail()).contains("email", "password");
    }

    @Test
    @DisplayName("ignores injected role field in registration payload and assigns role USER")
    void shouldIgnoreRoleInjectionInRegistrationPayload() {
        Map<String, Object> maliciousPayload = Map.of("email", "hacker@ledgerly.com", "password", "Password123!",
                "firstName", "Hack", "lastName", "Er", "role", "ADMIN");

        HttpResponse<AuthResponse> response = postJson("/api/v1/auth/register", maliciousPayload, AuthResponse.class);

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.body()).isNotNull();
        assertThat(response.body().role()).isEqualTo(Role.USER);

        HttpResponse<UserResponse> meRes = getMe("Bearer " + response.body().token());
        assertThat(meRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(meRes.body().role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("returns uniform 401 Unauthorized for unknown email and wrong password to prevent enumeration")
    void shouldReturnUniformUnauthorizedResponseForInvalidCredentials() {
        RegisterRequest registerReq = new RegisterRequest("existing@ledgerly.com", "Password123!", "Existing", "User");
        postJson("/api/v1/auth/register", registerReq, AuthResponse.class);

        LoginRequest wrongPasswordReq = new LoginRequest("existing@ledgerly.com", "WrongPassword!");
        HttpResponse<AuthResponse> wrongPasswordRes = postJson("/api/v1/auth/login", wrongPasswordReq,
                AuthResponse.class);

        LoginRequest unknownEmailReq = new LoginRequest("unknown@ledgerly.com", "Password123!");
        HttpResponse<AuthResponse> unknownEmailRes = postJson("/api/v1/auth/login", unknownEmailReq,
                AuthResponse.class);

        assertThat(wrongPasswordRes.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmailRes.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(wrongPasswordRes.problem()).isNotNull();
        assertThat(unknownEmailRes.problem()).isNotNull();

        assertThat(wrongPasswordRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:invalid-credentials"));
        assertThat(unknownEmailRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:invalid-credentials"));

        assertThat(wrongPasswordRes.problem().getTitle()).isEqualTo(unknownEmailRes.problem().getTitle());
        assertThat(wrongPasswordRes.problem().getDetail()).isEqualTo(unknownEmailRes.problem().getDetail());
    }

    @Test
    @DisplayName("returns 404 Not Found for a valid token whose user was deleted from the database")
    void shouldReturnNotFoundForDeletedUsersToken() {
        RegisterRequest registerReq = new RegisterRequest("deleted@ledgerly.com", "Password123!", "Deleted", "User");
        HttpResponse<AuthResponse> registerRes = postJson("/api/v1/auth/register", registerReq, AuthResponse.class);
        String validToken = registerRes.body().token();

        userRepository.deleteAll();

        HttpResponse<UserResponse> meRes = getMe("Bearer " + validToken);

        assertThat(meRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(meRes.problem()).isNotNull();
        assertThat(meRes.problem().getTitle()).isEqualTo("User Not Found");
        assertThat(meRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:user-not-found"));
    }

    @Test
    @DisplayName("rejects unauthenticated requests with 401 across missing, non-Bearer, empty, and tampered tokens")
    void shouldRejectUnauthenticatedRequestsAcrossTokenEdgeCases() {
        HttpResponse<UserResponse> missingHeaderRes = getMe(null);
        assertThat(missingHeaderRes.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpResponse<UserResponse> basicAuthRes = getMe("Basic dXNlcjpwYXNz");
        assertThat(basicAuthRes.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpResponse<UserResponse> emptyBearerRes = getMe("Bearer ");
        assertThat(emptyBearerRes.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

        RegisterRequest registerReq = new RegisterRequest("tamper-test@ledgerly.com", "Password123!", "Tamper", "User");
        HttpResponse<AuthResponse> registerRes = postJson("/api/v1/auth/register", registerReq, AuthResponse.class);
        String validToken = registerRes.body().token();

        String[] parts = validToken.split("\\.");
        assertThat(parts).hasSize(3);
        String signature = parts[2];
        int mid = signature.length() / 2;
        char originalChar = signature.charAt(mid);
        char flippedChar = (originalChar == 'a') ? 'b' : 'a';
        String tamperedSignature = signature.substring(0, mid) + flippedChar + signature.substring(mid + 1);
        String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSignature;

        HttpResponse<UserResponse> tamperedRes = getMe("Bearer " + tamperedToken);
        assertThat(tamperedRes.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
