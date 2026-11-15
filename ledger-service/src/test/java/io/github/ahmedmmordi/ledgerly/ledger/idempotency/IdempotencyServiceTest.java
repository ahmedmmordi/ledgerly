package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ahmedmmordi.ledgerly.ledger.config.JacksonConfig;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.TransferStatus;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService Unit Tests")
@NullMarked
@SuppressWarnings({ "DataFlowIssue", "ConstantConditions" })
class IdempotencyServiceTest {

    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-456789abcdef");
    private static final UUID KEY_ID = UUID.fromString("0193297a-9a90-7888-8123-111111111111");
    private static final UUID TRANSFER_ID = UUID.fromString("0193297a-9a90-7888-8123-222222222222");
    private static final UUID FROM_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-333333333333");
    private static final UUID TO_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-444444444444");

    private static final String IDEMPOTENCY_KEY = "key-test-123";
    private static final String ENDPOINT = "POST /api/v1/transfers";
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final Duration TTL = Duration.ofHours(24);
    private static final Instant TIMESTAMP = Instant.parse("2026-11-15T12:00:00Z");

    @Mock
    private IdempotencyKeyRepository repository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private ObjectMapper objectMapper;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service = new IdempotencyService(repository, transactionManager, objectMapper, TTL);
    }

    private IdempotencyKey createTestKey(IdempotencyStatus status) {
        IdempotencyKey key = IdempotencyKey.create(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH,
                Instant.now().plus(TTL));
        ReflectionTestUtils.setField(key, "id", KEY_ID);
        ReflectionTestUtils.setField(key, "status", status);
        return key;
    }

    private TransferResponse createSampleTransferResponse() {
        return new TransferResponse(TRANSFER_ID, FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, new BigDecimal("50.00"), "USD",
                TransferStatus.COMPLETED, "Test transfer", null, USER_ID, TIMESTAMP, TIMESTAMP);
    }

    @Nested
    @DisplayName("Constructor & Configuration Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should fail when repository is null")
        void shouldFailWhenRepositoryIsNull() {
            assertThatThrownBy(() -> new IdempotencyService(null, transactionManager, objectMapper, TTL))
                    .isInstanceOf(NullPointerException.class).hasMessage("Repository must not be null");
        }

        @Test
        @DisplayName("Should fail when transaction manager is null")
        void shouldFailWhenTransactionManagerIsNull() {
            assertThatThrownBy(() -> new IdempotencyService(repository, null, objectMapper, TTL))
                    .isInstanceOf(NullPointerException.class).hasMessage("Transaction manager must not be null");
        }

        @Test
        @DisplayName("Should create default ObjectMapper when null is provided")
        void shouldUseDefaultObjectMapperWhenNull() {
            IdempotencyService customService = new IdempotencyService(repository, transactionManager, null, TTL);
            assertThat(ReflectionTestUtils.getField(customService, "objectMapper")).isNotNull();
            assertThat(customService.computeHash("hello")).isNotBlank();
        }

        @Test
        @DisplayName("Should fail when TTL is null")
        void shouldFailWhenTtlIsNull() {
            assertThatThrownBy(() -> new IdempotencyService(repository, transactionManager, objectMapper, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("TTL must not be null");
        }
    }

    @Nested
    @DisplayName("Compute Hash Tests")
    class ComputeHashTests {

        @Test
        @DisplayName("Should fail when payload is null")
        void shouldFailWhenPayloadIsNull() {
            assertThatThrownBy(() -> service.computeHash(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Payload must not be null");
        }

        @Test
        @DisplayName("Should compute SHA-256 for string payload")
        void shouldComputeSha256ForString() {
            String hash = service.computeHash("transfer-payload-data");

            assertThat(hash).hasSize(64).matches("^[a-f0-9]{64}$");
        }

        @Test
        @DisplayName("Should compute SHA-256 for byte array payload")
        void shouldComputeSha256ForByteArray() {
            byte[] bytes = "transfer-bytes".getBytes(StandardCharsets.UTF_8);

            String hash = service.computeHash(bytes);

            assertThat(hash).hasSize(64).matches("^[a-f0-9]{64}$");
        }

        @Test
        @DisplayName("Should compute SHA-256 for DTO object")
        void shouldComputeSha256ForDto() {
            TransferResponse response = createSampleTransferResponse();

            String hash = service.computeHash(response);

            assertThat(hash).hasSize(64).matches("^[a-f0-9]{64}$");
        }

        @Test
        @DisplayName("Should be deterministic for identical objects")
        void shouldBeDeterministicForIdenticalObjects() {
            TransferResponse r1 = createSampleTransferResponse();
            TransferResponse r2 = createSampleTransferResponse();

            assertThat(service.computeHash(r1)).isEqualTo(service.computeHash(r2));
        }

        @Test
        @DisplayName("Should produce different hash for different payloads")
        void shouldProduceDifferentHashForDifferentPayloads() {
            String h1 = service.computeHash("payload-1");
            String h2 = service.computeHash("payload-2");

            assertThat(h1).isNotEqualTo(h2);
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when object cannot be serialized to JSON")
        void shouldThrowWhenObjectCannotBeSerialized() throws Exception {
            ObjectMapper mockMapper = mock(ObjectMapper.class);
            when(mockMapper.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("serialization error") {
            });

            IdempotencyService customService = new IdempotencyService(repository, transactionManager, mockMapper, TTL);

            assertThatThrownBy(() -> customService.computeHash(new Object()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Failed to serialize payload to JSON for hashing");
        }
    }

    @Nested
    @DisplayName("Key Reservation Tests")
    class ReserveKeyTests {

        @Test
        @DisplayName("Should fail when required arguments are null")
        void shouldFailWhenArgumentsAreNull() {
            assertThatThrownBy(() -> service.reserveKey(null, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, TTL))
                    .isInstanceOf(NullPointerException.class).hasMessage("User ID must not be null");

            assertThatThrownBy(() -> service.reserveKey(USER_ID, null, ENDPOINT, REQUEST_HASH, TTL))
                    .isInstanceOf(NullPointerException.class).hasMessage("Idempotency key must not be null");

            assertThatThrownBy(() -> service.reserveKey(USER_ID, IDEMPOTENCY_KEY, null, REQUEST_HASH, TTL))
                    .isInstanceOf(NullPointerException.class).hasMessage("Endpoint must not be null");

            assertThatThrownBy(() -> service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, null, TTL))
                    .isInstanceOf(NullPointerException.class).hasMessage("Request hash must not be null");

            assertThatThrownBy(() -> service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("TTL must not be null");
        }

        @Test
        @DisplayName("Should reserve brand new key successfully")
        void shouldReserveNewKey() {
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
            given(repository.saveAndFlush(any(IdempotencyKey.class))).willAnswer(inv -> {
                IdempotencyKey saved = inv.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", KEY_ID);
                return saved;
            });

            ReservationResult result = service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, TTL);

            assertThat(result.isCached()).isFalse();
            assertThat(result.key().getId()).isEqualTo(KEY_ID);
            assertThat(result.key().getStatus()).isEqualTo(IdempotencyStatus.PROCESSING);
            verify(transactionManager).commit(transactionStatus);
        }

        @Test
        @DisplayName("Should throw IdempotencyConflictException when existing key is PROCESSING")
        void shouldThrowConflictWhenExistingIsProcessing() {
            IdempotencyKey processingKey = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                    .willReturn(Optional.of(processingKey));

            assertThatThrownBy(() -> service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, TTL))
                    .isInstanceOf(IdempotencyConflictException.class)
                    .hasMessageContaining("is currently being processed by another request");
        }

        @Test
        @DisplayName("Should return cached result when existing key is COMPLETED and matches")
        void shouldReturnCachedWhenExistingIsCompletedAndMatches() {
            IdempotencyKey completedKey = createTestKey(IdempotencyStatus.COMPLETED);
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                    .willReturn(Optional.of(completedKey));

            ReservationResult result = service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, TTL);

            assertThat(result.isCached()).isTrue();
            assertThat(result.key().getId()).isEqualTo(KEY_ID);
        }

        @Test
        @DisplayName("Should throw IdempotencyPayloadMismatchException when endpoint mismatches")
        void shouldThrowPayloadMismatchWhenEndpointMismatches() {
            IdempotencyKey completedKey = createTestKey(IdempotencyStatus.COMPLETED);
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                    .willReturn(Optional.of(completedKey));

            assertThatThrownBy(
                    () -> service.reserveKey(USER_ID, IDEMPOTENCY_KEY, "POST /api/v1/deposits", REQUEST_HASH, TTL))
                            .isInstanceOf(IdempotencyPayloadMismatchException.class)
                            .hasMessageContaining("Endpoint mismatch");
        }

        @Test
        @DisplayName("Should throw IdempotencyPayloadMismatchException when request hash mismatches")
        void shouldThrowPayloadMismatchWhenHashMismatches() {
            IdempotencyKey completedKey = createTestKey(IdempotencyStatus.COMPLETED);
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                    .willReturn(Optional.of(completedKey));

            String differentHash = "b".repeat(64);
            assertThatThrownBy(() -> service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, differentHash, TTL))
                    .isInstanceOf(IdempotencyPayloadMismatchException.class)
                    .hasMessageContaining("Payload hash mismatch");
        }

        @Test
        @DisplayName("Should prune and re-reserve expired key")
        void shouldPruneAndReReserveExpiredKey() {
            IdempotencyKey expiredKey = IdempotencyKey.create(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH,
                    Instant.now().minus(Duration.ofHours(1)));
            ReflectionTestUtils.setField(expiredKey, "id", KEY_ID);
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                    .willReturn(Optional.of(expiredKey));
            given(repository.saveAndFlush(any(IdempotencyKey.class))).willAnswer(inv -> {
                IdempotencyKey saved = inv.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            ReservationResult result = service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, TTL);

            verify(repository).delete(expiredKey);
            verify(repository).flush();
            assertThat(result.isCached()).isFalse();
            assertThat(result.key().getStatus()).isEqualTo(IdempotencyStatus.PROCESSING);
        }

        @Test
        @DisplayName("Should prune and re-reserve previously failed key")
        void shouldPruneAndReReserveFailedKey() {
            IdempotencyKey failedKey = createTestKey(IdempotencyStatus.PROCESSING);
            failedKey.fail();
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                    .willReturn(Optional.of(failedKey));
            given(repository.saveAndFlush(any(IdempotencyKey.class))).willAnswer(inv -> {
                IdempotencyKey saved = inv.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            ReservationResult result = service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, TTL);

            verify(repository).delete(failedKey);
            verify(repository).flush();
            assertThat(result.isCached()).isFalse();
        }

        @Test
        @DisplayName("Should recover on concurrent insert collision when concurrent is PROCESSING")
        void shouldRecoverOnConcurrentCollisionWhenProcessing() {
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
            given(repository.saveAndFlush(any(IdempotencyKey.class)))
                    .willThrow(new DataIntegrityViolationException("duplicate key"));

            IdempotencyKey concurrent = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty())
                    .willReturn(Optional.of(concurrent));

            assertThatThrownBy(() -> service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, TTL))
                    .isInstanceOf(IdempotencyConflictException.class);
        }

        @Test
        @DisplayName("Should recover on concurrent insert collision when concurrent is COMPLETED")
        void shouldRecoverOnConcurrentCollisionWhenCompleted() {
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
            given(repository.saveAndFlush(any(IdempotencyKey.class)))
                    .willThrow(new DataIntegrityViolationException("duplicate key"));

            IdempotencyKey concurrent = createTestKey(IdempotencyStatus.COMPLETED);
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty())
                    .willReturn(Optional.of(concurrent));

            ReservationResult result = service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, TTL);

            assertThat(result.isCached()).isTrue();
            assertThat(result.key().getId()).isEqualTo(KEY_ID);
        }

        @Test
        @DisplayName("Should throw DataIntegrityViolationException if concurrent lookup returns empty")
        void shouldRethrowExceptionIfConcurrentNotFound() {
            DataIntegrityViolationException integrityEx = new DataIntegrityViolationException("duplicate key");
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
            given(repository.saveAndFlush(any(IdempotencyKey.class))).willThrow(integrityEx);

            assertThatThrownBy(() -> service.reserveKey(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, REQUEST_HASH, TTL))
                    .isSameAs(integrityEx);
        }
    }

    @Nested
    @DisplayName("Key Completion Tests")
    class CompleteKeyTests {

        @Test
        @DisplayName("Should fail when key ID is null")
        void shouldFailWhenKeyIdIsNull() {
            assertThatThrownBy(() -> service.completeKey(null, 200, "{}")).isInstanceOf(NullPointerException.class)
                    .hasMessage("Key ID must not be null");

            assertThatThrownBy(() -> service.completeKey(null, ResponseEntity.ok().build()))
                    .isInstanceOf(NullPointerException.class).hasMessage("Key ID must not be null");
        }

        @Test
        @DisplayName("Should fail when response is null")
        void shouldFailWhenResponseIsNull() {
            assertThatThrownBy(() -> service.completeKey(KEY_ID, (ResponseEntity<String>) null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Response must not be null");
        }

        @Test
        @DisplayName("Should complete key with status and body successfully")
        void shouldCompleteKeySuccessfully() {
            IdempotencyKey key = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(key));

            service.completeKey(KEY_ID, 201, "{\"id\":\"" + TRANSFER_ID + "\"}");

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(key.getResponseStatus()).isEqualTo(201);
            assertThat(key.getResponseBody()).isEqualTo("{\"id\":\"" + TRANSFER_ID + "\"}");
            verify(repository).saveAndFlush(key);
        }

        @Test
        @DisplayName("Should throw IllegalStateException when key is not found")
        void shouldThrowWhenKeyNotFound() {
            given(repository.findById(KEY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.completeKey(KEY_ID, 200, "{}")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Idempotency key not found");
        }

        @Test
        @DisplayName("Should serialize response body when completing with ResponseEntity")
        void shouldSerializeResponseBody() {
            IdempotencyKey key = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(key));

            TransferResponse response = createSampleTransferResponse();
            service.completeKey(KEY_ID, ResponseEntity.status(201).body(response));

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(key.getResponseStatus()).isEqualTo(201);
            assertThat(key.getResponseBody()).contains(TRANSFER_ID.toString());
            verify(repository).saveAndFlush(key);
        }

        @Test
        @DisplayName("Should complete key with null body when response body is null")
        void shouldCompleteKeyWithNullBody() {
            IdempotencyKey key = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(key));

            service.completeKey(KEY_ID, ResponseEntity.noContent().build());

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(key.getResponseStatus()).isEqualTo(204);
            assertThat(key.getResponseBody()).isNull();
            verify(repository).saveAndFlush(key);
        }
    }

    @Nested
    @DisplayName("Key Failure Tests")
    class FailKeyTests {

        @Test
        @DisplayName("Should return silently when key ID is null")
        void shouldReturnSilentlyWhenKeyIdIsNull() {
            service.failKey(null);
            verify(repository, never()).findById(any());
        }

        @Test
        @DisplayName("Should mark processing key as FAILED")
        void shouldMarkProcessingKeyAsFailed() {
            IdempotencyKey key = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(key));

            service.failKey(KEY_ID);

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
            verify(repository).saveAndFlush(key);
        }

        @Test
        @DisplayName("Should mark processing key as FAILED with status and body")
        void shouldMarkProcessingKeyAsFailedWithStatusAndBody() {
            IdempotencyKey key = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(key));

            service.failKey(KEY_ID, 422, "{\"error\":\"insufficient funds\"}");

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
            assertThat(key.getResponseStatus()).isEqualTo(422);
            assertThat(key.getResponseBody()).isEqualTo("{\"error\":\"insufficient funds\"}");
            verify(repository).saveAndFlush(key);
        }

        @Test
        @DisplayName("Should not alter key if it is not in PROCESSING state")
        void shouldNotAlterNonProcessingKey() {
            IdempotencyKey key = createTestKey(IdempotencyStatus.COMPLETED);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(key));

            service.failKey(KEY_ID);

            assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            verify(repository, never()).saveAndFlush(key);
        }

        @Test
        @DisplayName("Should return silently when key is not found in repository")
        void shouldReturnSilentlyWhenKeyNotFound() {
            given(repository.findById(KEY_ID)).willReturn(Optional.empty());

            service.failKey(KEY_ID);

            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Should catch repository exceptions and not propagate")
        void shouldCatchExceptionsAndNotPropagate() {
            given(repository.findById(KEY_ID)).willThrow(new RuntimeException("DB connection error"));

            // Must not throw
            service.failKey(KEY_ID);
        }
    }

    @Nested
    @DisplayName("Execute Coordination Pipeline Tests")
    class ExecutePipelineTests {

        @Test
        @DisplayName("Should fail when execute arguments are null or blank")
        void shouldFailWhenExecuteArgumentsAreInvalid() {
            assertThatThrownBy(() -> service.execute(null, IDEMPOTENCY_KEY, ENDPOINT, "payload", String.class,
                    () -> ResponseEntity.ok("ok"))).isInstanceOf(NullPointerException.class)
                            .hasMessage("User ID must not be null");

            assertThatThrownBy(() -> service.execute(USER_ID, null, ENDPOINT, "payload", String.class,
                    () -> ResponseEntity.ok("ok"))).isInstanceOf(NullPointerException.class)
                            .hasMessage("Idempotency key must not be null");

            assertThatThrownBy(() -> service.execute(USER_ID, "   ", ENDPOINT, "payload", String.class,
                    () -> ResponseEntity.ok("ok"))).isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Idempotency key must not be blank");

            assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, null, "payload", String.class,
                    () -> ResponseEntity.ok("ok"))).isInstanceOf(NullPointerException.class)
                            .hasMessage("Endpoint must not be null");

            assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, null, String.class,
                    () -> ResponseEntity.ok("ok"))).isInstanceOf(NullPointerException.class)
                            .hasMessage("Request payload must not be null");

            assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, "payload", null,
                    () -> ResponseEntity.ok("ok"))).isInstanceOf(NullPointerException.class)
                            .hasMessage("Response type must not be null");

            assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, "payload", String.class, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Action must not be null");
        }

        @Test
        @DisplayName("Should execute action, complete key, and return response on first execution")
        void shouldExecuteFirstTimeSuccessfully() {
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
            IdempotencyKey savedKey = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.saveAndFlush(any(IdempotencyKey.class))).willReturn(savedKey);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(savedKey));

            TransferResponse sampleResponse = createSampleTransferResponse();

            ResponseEntity<TransferResponse> response = service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT,
                    "sample-request", TransferResponse.class,
                    () -> ResponseEntity.status(HttpStatus.CREATED).body(sampleResponse));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isEqualTo(sampleResponse);
            assertThat(savedKey.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(savedKey.getResponseStatus()).isEqualTo(201);
            assertThat(savedKey.getResponseBody()).contains(TRANSFER_ID.toString());
        }

        @Test
        @DisplayName("Should return cached response with X-Served-From-Cache header without executing action")
        void shouldReturnCachedResponse() throws Exception {
            String payload = "sample-request";
            String payloadHash = service.computeHash(payload);

            TransferResponse sampleResponse = createSampleTransferResponse();
            String jsonBody = objectMapper.writeValueAsString(sampleResponse);

            IdempotencyKey completedKey = IdempotencyKey.create(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, payloadHash,
                    Instant.now().plus(TTL));
            ReflectionTestUtils.setField(completedKey, "id", KEY_ID);
            completedKey.complete(201, jsonBody);

            given(repository.findByUserIdAndIdempotencyKey(any(), any())).willReturn(Optional.of(completedKey));

            ResponseEntity<TransferResponse> response = service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, payload,
                    TransferResponse.class, () -> {
                        throw new AssertionError("Action should never be called for cached replay");
                    });

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getFirst(IdempotencyService.X_SERVED_FROM_CACHE_HEADER)).isEqualTo("true");
            assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create("/api/v1/transfers/" + TRANSFER_ID));
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().id()).isEqualTo(TRANSFER_ID);
        }

        @Test
        @DisplayName("Should mark key as FAILED and rethrow exception when action fails")
        void shouldMarkKeyFailedWhenActionFails() {
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
            IdempotencyKey savedKey = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.saveAndFlush(any(IdempotencyKey.class))).willReturn(savedKey);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(savedKey));

            RuntimeException businessError = new RuntimeException("Insufficient balance in account");

            assertThatThrownBy(
                    () -> service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, "sample-request", String.class, () -> {
                        throw businessError;
                    })).isSameAs(businessError);

            assertThat(savedKey.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
            verify(repository).saveAndFlush(savedKey);
        }

        @Test
        @DisplayName("Should return cached response with null body when cached response body is null")
        void shouldReturnCachedResponseWithNullBody() {
            String payload = "sample-request-no-body";
            String payloadHash = service.computeHash(payload);

            IdempotencyKey completedKey = IdempotencyKey.create(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, payloadHash,
                    Instant.now().plus(TTL));
            ReflectionTestUtils.setField(completedKey, "id", KEY_ID);
            completedKey.complete(204, null);

            given(repository.findByUserIdAndIdempotencyKey(any(), any())).willReturn(Optional.of(completedKey));

            ResponseEntity<Void> response = service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, payload, Void.class,
                    () -> {
                        throw new AssertionError("Action should never be called for cached replay");
                    });

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(response.getHeaders().getFirst(IdempotencyService.X_SERVED_FROM_CACHE_HEADER)).isEqualTo("true");
            assertThat(response.getBody()).isNull();
        }

        @Test
        @DisplayName("Should trim idempotency key before reserving and querying repository")
        void shouldTrimIdempotencyKeyDuringExecution() {
            String untrimmedKey = "  " + IDEMPOTENCY_KEY + "  ";
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
            IdempotencyKey savedKey = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.saveAndFlush(any(IdempotencyKey.class))).willReturn(savedKey);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(savedKey));

            ResponseEntity<String> response = service.execute(USER_ID, untrimmedKey, ENDPOINT, "payload", String.class,
                    () -> ResponseEntity.ok("success"));

            assertThat(response.getBody()).isEqualTo("success");
            verify(repository).findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY);
        }

        @Test
        @DisplayName("Should execute action, complete key with null body on first execution for 204 response")
        void shouldExecuteFirstTimeSuccessfullyWithNullBody() {
            given(repository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
            IdempotencyKey savedKey = createTestKey(IdempotencyStatus.PROCESSING);
            given(repository.saveAndFlush(any(IdempotencyKey.class))).willReturn(savedKey);
            given(repository.findById(KEY_ID)).willReturn(Optional.of(savedKey));

            ResponseEntity<Void> response = service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, "sample-payload",
                    Void.class, () -> ResponseEntity.noContent().build());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(response.getBody()).isNull();
            assertThat(savedKey.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(savedKey.getResponseStatus()).isEqualTo(204);
            assertThat(savedKey.getResponseBody()).isNull();
        }

        @Test
        @DisplayName("Should not set Location header on cached response when status is not 201 Created")
        void shouldNotSetLocationHeaderWhenStatusIsNotCreated() throws Exception {
            String payload = "sample-request";
            String payloadHash = service.computeHash(payload);

            IdempotencyKey completedKey = IdempotencyKey.create(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, payloadHash,
                    Instant.now().plus(TTL));
            ReflectionTestUtils.setField(completedKey, "id", KEY_ID);
            completedKey.complete(200, objectMapper.writeValueAsString("ok"));

            given(repository.findByUserIdAndIdempotencyKey(any(), any())).willReturn(Optional.of(completedKey));

            ResponseEntity<String> response = service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, payload, String.class,
                    () -> ResponseEntity.ok("ok"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(IdempotencyService.X_SERVED_FROM_CACHE_HEADER)).isEqualTo("true");
            assertThat(response.getHeaders().getLocation()).isNull();
            assertThat(response.getBody()).isEqualTo("ok");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when cached response body is malformed JSON")
        void shouldThrowWhenCachedBodyIsMalformedJson() {
            String payload = "sample-request-malformed";
            String payloadHash = service.computeHash(payload);

            IdempotencyKey completedKey = IdempotencyKey.create(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, payloadHash,
                    Instant.now().plus(TTL));
            ReflectionTestUtils.setField(completedKey, "id", KEY_ID);
            completedKey.complete(200, "{malformed-json}");

            given(repository.findByUserIdAndIdempotencyKey(any(), any())).willReturn(Optional.of(completedKey));

            assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, ENDPOINT, payload,
                    TransferResponse.class, () -> ResponseEntity.ok().build()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Failed to deserialize cached idempotency response");
        }
    }
}
