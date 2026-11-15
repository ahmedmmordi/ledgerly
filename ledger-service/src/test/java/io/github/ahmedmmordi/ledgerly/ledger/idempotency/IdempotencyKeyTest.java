package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("IdempotencyKey Domain Entity Tests")
class IdempotencyKeyTest {

    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final String VALID_KEY = "transfer-req-12345";
    private static final String VALID_ENDPOINT = "POST /api/v1/transfers";
    private static final String VALID_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Instant FUTURE_EXPIRY = Instant.now().plus(24, ChronoUnit.HOURS);

    @Nested
    @DisplayName("Creation & Validation")
    class CreationTests {

        @Test
        @DisplayName("Should create idempotency key in PROCESSING status with normalized hash and trimmed key")
        void shouldCreateIdempotencyKeySuccessfully() {
            String upperHash = VALID_HASH.toUpperCase();
            IdempotencyKey key = IdempotencyKey.create(USER_ID, "  my-key-001  ", VALID_ENDPOINT, upperHash,
                    FUTURE_EXPIRY);

            assertThat(key.getUserId()).isEqualTo(USER_ID);
            assertThat(key.getIdempotencyKey()).isEqualTo("my-key-001");
            assertThat(key.getEndpoint()).isEqualTo(VALID_ENDPOINT);
            assertThat(key.getRequestHash()).isEqualTo(VALID_HASH.toLowerCase());
            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.PROCESSING);
            assertThat(key.isProcessing()).isTrue();
            assertThat(key.isCompleted()).isFalse();
            assertThat(key.isFailed()).isFalse();
            assertThat(key.getResponseStatus()).isNull();
            assertThat(key.getResponseBody()).isNull();
            assertThat(key.getExpiresAt()).isEqualTo(FUTURE_EXPIRY);
        }

        @ParameterizedTest(name = "Valid key: {0}")
        @ValueSource(strings = { "0193297a-9a90-7888-8123-000000000001", "key_123", "client.tx-99", "api:v1:orders:10",
                "a", "A1-B2_C3.D4:E5" })
        @DisplayName("Should accept diverse valid idempotency key formats")
        void shouldAcceptValidKeyFormats(String validKeyFormat) {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, validKeyFormat, VALID_ENDPOINT, VALID_HASH,
                    FUTURE_EXPIRY);
            assertThat(key.getIdempotencyKey()).isEqualTo(validKeyFormat);
        }

        @ParameterizedTest(name = "Invalid key: \"{0}\"")
        @ValueSource(strings = { "", "   ", "key with spaces", "key@invalid!", "key#123", "key$val", "key%10",
                "key/slash", "key?query", "key=value", "key;semi", "key\nnewline" })
        @DisplayName("Should reject invalid idempotency key formats")
        void shouldRejectInvalidKeyFormats(String invalidKey) {
            assertThatThrownBy(
                    () -> IdempotencyKey.create(USER_ID, invalidKey, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Idempotency key must match pattern");
        }

        @Test
        @DisplayName("Should accept maximum allowed key length of 128 characters")
        void shouldAcceptMaxKeyLength128Chars() {
            String maxKey = "a".repeat(128);
            IdempotencyKey key = IdempotencyKey.create(USER_ID, maxKey, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            assertThat(key.getIdempotencyKey()).isEqualTo(maxKey);
        }

        @Test
        @DisplayName("Should reject key exceeding 128 characters")
        void shouldRejectKeyExceeding128Chars() {
            String longKey = "a".repeat(129);
            assertThatThrownBy(() -> IdempotencyKey.create(USER_ID, longKey, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Idempotency key must match pattern");
        }

        @Test
        @DisplayName("Should accept maximum allowed endpoint length of 100 characters")
        void shouldAcceptMaxEndpointLength100Chars() {
            String maxEndpoint = "POST /" + "a".repeat(94);
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, maxEndpoint, VALID_HASH, FUTURE_EXPIRY);
            assertThat(key.getEndpoint()).isEqualTo(maxEndpoint);
        }

        @Test
        @DisplayName("Should trim whitespace from endpoint during creation")
        void shouldTrimEndpointWhitespace() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, "   " + VALID_ENDPOINT + "   ", VALID_HASH,
                    FUTURE_EXPIRY);
            assertThat(key.getEndpoint()).isEqualTo(VALID_ENDPOINT);
        }

        @ParameterizedTest(name = "Invalid endpoint: \"{0}\"")
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should reject blank endpoints")
        void shouldRejectBlankEndpoint(String blankEndpoint) {
            assertThatThrownBy(
                    () -> IdempotencyKey.create(USER_ID, VALID_KEY, blankEndpoint, VALID_HASH, FUTURE_EXPIRY))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Endpoint must not be blank");
        }

        @Test
        @DisplayName("Should reject endpoint exceeding 100 characters")
        void shouldRejectEndpointExceeding100Chars() {
            String longEndpoint = "POST /" + "a".repeat(95);
            assertThatThrownBy(() -> IdempotencyKey.create(USER_ID, VALID_KEY, longEndpoint, VALID_HASH, FUTURE_EXPIRY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Endpoint must not be blank and must not exceed 100 characters");
        }

        @ParameterizedTest(name = "Invalid hash: \"{0}\"")
        @ValueSource(strings = { "", "short", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b8555",
                "zzb0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" })
        @DisplayName("Should reject invalid SHA-256 hash digests")
        void shouldRejectInvalidSha256Hash(String invalidHash) {
            assertThatThrownBy(
                    () -> IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, invalidHash, FUTURE_EXPIRY))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Request hash must be a 64-character hexadecimal SHA-256 digest");
        }

        @Test
        @DisplayName("Should reject null arguments during creation")
        void shouldRejectNullArguments() {
            assertThatThrownBy(() -> IdempotencyKey.create(null, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("User ID");
            assertThatThrownBy(() -> IdempotencyKey.create(USER_ID, null, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Idempotency key");
            assertThatThrownBy(() -> IdempotencyKey.create(USER_ID, VALID_KEY, null, VALID_HASH, FUTURE_EXPIRY))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Endpoint");
            assertThatThrownBy(() -> IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, null, FUTURE_EXPIRY))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Request hash");
            assertThatThrownBy(() -> IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Expires at");
        }
    }

    @Nested
    @DisplayName("State Transitions")
    class StateTransitionTests {

        @Test
        @DisplayName("Should complete key and record HTTP response status and body")
        void shouldCompleteProcessingKey() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            key.complete(201, "{\"id\":\"tx-123\",\"status\":\"COMPLETED\"}");

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(key.isCompleted()).isTrue();
            assertThat(key.isProcessing()).isFalse();
            assertThat(key.getResponseStatus()).isEqualTo(201);
            assertThat(key.getResponseBody()).isEqualTo("{\"id\":\"tx-123\",\"status\":\"COMPLETED\"}");
        }

        @Test
        @DisplayName("Should fail key with error status and body")
        void shouldFailProcessingKeyWithDetails() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            key.fail(422, "{\"title\":\"Unprocessable Entity\"}");

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
            assertThat(key.isFailed()).isTrue();
            assertThat(key.isProcessing()).isFalse();
            assertThat(key.getResponseStatus()).isEqualTo(422);
            assertThat(key.getResponseBody()).isEqualTo("{\"title\":\"Unprocessable Entity\"}");
        }

        @Test
        @DisplayName("Should fail key without status code")
        void shouldFailProcessingKeyWithoutDetails() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            key.fail();

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
            assertThat(key.isFailed()).isTrue();
            assertThat(key.getResponseStatus()).isNull();
            assertThat(key.getResponseBody()).isNull();
        }

        @Test
        @DisplayName("Should allow completing key with null response body (e.g. HTTP 204 No Content)")
        void shouldAllowCompletionWithNullResponseBody() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            key.complete(204, null);

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(key.getResponseStatus()).isEqualTo(204);
            assertThat(key.getResponseBody()).isNull();
        }

        @Test
        @DisplayName("Should reject completing key when already completed")
        void shouldRejectDoubleCompletion() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            key.complete(201, "{}");

            assertThatThrownBy(() -> key.complete(201, "{}")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot complete idempotency key in status COMPLETED");
        }

        @Test
        @DisplayName("Should reject completing key when already failed")
        void shouldRejectCompletionAfterFailure() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            key.fail();

            assertThatThrownBy(() -> key.complete(201, "{}")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot complete idempotency key in status FAILED");
        }

        @Test
        @DisplayName("Should reject failing key when already completed")
        void shouldRejectFailureAfterCompletion() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            key.complete(201, "{}");

            assertThatThrownBy(key::fail).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot fail idempotency key in status COMPLETED");
            assertThatThrownBy(() -> key.fail(500, "error")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot fail idempotency key in status COMPLETED");
        }

        @Test
        @DisplayName("Should reject failing key when already failed")
        void shouldRejectDoubleFailure() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            key.fail();

            assertThatThrownBy(key::fail).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot fail idempotency key in status FAILED");
            assertThatThrownBy(() -> key.fail(500, "error")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot fail idempotency key in status FAILED");
        }

        @ParameterizedTest(name = "Valid boundary HTTP status: {0}")
        @ValueSource(ints = { 100, 200, 201, 204, 400, 404, 409, 422, 500, 599 })
        @DisplayName("Should accept valid boundary HTTP status codes on completion and failure")
        void shouldAcceptValidHttpStatusBoundaries(int validStatus) {
            IdempotencyKey completeKey = IdempotencyKey.create(USER_ID, "key-complete-" + validStatus, VALID_ENDPOINT,
                    VALID_HASH, FUTURE_EXPIRY);
            completeKey.complete(validStatus, "{}");
            assertThat(completeKey.getResponseStatus()).isEqualTo(validStatus);

            IdempotencyKey failKey = IdempotencyKey.create(USER_ID, "key-fail-" + validStatus, VALID_ENDPOINT,
                    VALID_HASH, FUTURE_EXPIRY);
            failKey.fail(validStatus, "{}");
            assertThat(failKey.getResponseStatus()).isEqualTo(validStatus);
        }

        @ParameterizedTest(name = "Invalid HTTP status: {0}")
        @ValueSource(ints = { 99, 600, -1, 0, 1000 })
        @DisplayName("Should reject invalid HTTP status codes on failure")
        void shouldRejectInvalidHttpStatusCodesOnFailure(int invalidStatus) {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            assertThatThrownBy(() -> key.fail(invalidStatus, "{}")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Response status must be a valid HTTP status code");
        }

        @ParameterizedTest(name = "Invalid HTTP status: {0}")
        @ValueSource(ints = { 99, 600, -1, 0, 1000 })
        @DisplayName("Should reject invalid HTTP status codes on completion")
        void shouldRejectInvalidHttpStatusCodes(int invalidStatus) {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);
            assertThatThrownBy(() -> key.complete(invalidStatus, "{}")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Response status must be a valid HTTP status code");
        }
    }

    @Nested
    @DisplayName("Matching & Expiration Verification")
    class MatchingAndExpirationTests {

        @Test
        @DisplayName("Should match hash case-insensitively and ignore whitespace")
        void shouldMatchHashCaseInsensitively() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);

            assertThat(key.matchesHash(VALID_HASH)).isTrue();
            assertThat(key.matchesHash(VALID_HASH.toUpperCase())).isTrue();
            assertThat(key.matchesHash("   " + VALID_HASH + "   ")).isTrue();
            assertThat(key.matchesHash("0000000000000000000000000000000000000000000000000000000000000000")).isFalse();
            assertThat(key.matchesHash(null)).isFalse();
        }

        @Test
        @DisplayName("Should match endpoint exactly and ignore whitespace")
        void shouldMatchEndpoint() {
            IdempotencyKey key = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, FUTURE_EXPIRY);

            assertThat(key.matchesEndpoint(VALID_ENDPOINT)).isTrue();
            assertThat(key.matchesEndpoint("   " + VALID_ENDPOINT + "   ")).isTrue();
            assertThat(key.matchesEndpoint("POST /api/v1/accounts")).isFalse();
            assertThat(key.matchesEndpoint(null)).isFalse();
        }

        @Test
        @DisplayName("Should correctly evaluate key expiration boundaries and reject null")
        void shouldEvaluateExpiration() {
            Instant now = Instant.now();
            IdempotencyKey futureKey = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH,
                    now.plusSeconds(3600));
            IdempotencyKey pastKey = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH,
                    now.minusSeconds(1));
            IdempotencyKey exactKey = IdempotencyKey.create(USER_ID, VALID_KEY, VALID_ENDPOINT, VALID_HASH, now);

            assertThat(futureKey.isExpired(now)).isFalse();
            assertThat(pastKey.isExpired(now)).isTrue();
            assertThat(exactKey.isExpired(now)).isFalse();

            assertThatThrownBy(() -> futureKey.isExpired(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Instant must not be null");
        }
    }
}
