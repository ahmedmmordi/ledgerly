package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@DisplayName("IdempotencyKey Repository & PostgreSQL Schema Integration Tests")
class IdempotencyKeyRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
    }

    @Autowired
    private IdempotencyKeyRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final UUID USER_A = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final UUID USER_B = UUID.fromString("0193297a-9a90-7888-8123-000000000002");
    private static final String ENDPOINT = "POST /api/v1/transfers";
    private static final String HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private IdempotencyKey createDefaultKey(UUID userId, String key) {
        return IdempotencyKey.create(userId, key, ENDPOINT, HASH, Instant.now().plus(24, ChronoUnit.HOURS));
    }

    private void executeDirectInsert(UUID id, UUID userId, String key, String endpoint, String hash, String status,
            Integer responseStatus) {
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        java.sql.Timestamp expiry = java.sql.Timestamp.from(Instant.now().plusSeconds(3600));
        jdbcTemplate.update(
                """
                        INSERT INTO idempotency_keys
                        (id, user_id, idempotency_key, endpoint, request_hash, status, response_status, created_at, updated_at, expires_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """,
                id, userId, key, endpoint, hash, status, responseStatus, now, now, expiry);
    }

    @Test
    @DisplayName("Should verify PostgreSQL indexes created on idempotency_keys table")
    void shouldVerifyPostgreSqlIndexesOnTable() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
                SELECT indexname, indexdef
                FROM pg_indexes
                WHERE tablename = 'idempotency_keys'
                ORDER BY indexname
                """);

        List<String> indexNames = indexes.stream().map(row -> (String) row.get("indexname")).toList();

        // Must have: Primary key index, Unique constraint index, and expires_at index
        assertThat(indexNames).contains("pk_idempotency_keys", "uq_idempotency_keys_user_key",
                "idx_idempotency_keys_expires_at");

        // Verify there is NO redundant duplicate index on (user_id, idempotency_key)
        long userKeyIndexCount = indexes.stream().map(row -> (String) row.get("indexdef"))
                .filter(def -> def.contains("(user_id, idempotency_key)")).count();

        assertThat(userKeyIndexCount)
                .as("Exactly ONE index should cover (user_id, idempotency_key) to avoid write overhead").isEqualTo(1L);
    }

    @Test
    @DisplayName("Should persist and retrieve idempotency key with UUID v7 and auditing timestamps")
    void shouldPersistAndRetrieveKey() {
        IdempotencyKey key = createDefaultKey(USER_A, "key-persist-01");
        IdempotencyKey saved = repository.save(key);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isZero();

        Optional<IdempotencyKey> found = repository.findByUserIdAndIdempotencyKey(USER_A, "key-persist-01");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getEndpoint()).isEqualTo(ENDPOINT);
        assertThat(found.get().getRequestHash()).isEqualTo(HASH);
        assertThat(found.get().getStatus()).isEqualTo(IdempotencyStatus.PROCESSING);
    }

    @Test
    @DisplayName("Should enforce unique constraint on (user_id, idempotency_key)")
    void shouldEnforceUniqueConstraint() {
        IdempotencyKey key1 = createDefaultKey(USER_A, "duplicate-key");
        repository.saveAndFlush(key1);

        IdempotencyKey key2 = createDefaultKey(USER_A, "duplicate-key");
        assertThatThrownBy(() -> repository.saveAndFlush(key2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should allow identical idempotency key for different users")
    void shouldAllowSameKeyForDifferentUsers() {
        IdempotencyKey keyA = createDefaultKey(USER_A, "shared-key");
        IdempotencyKey keyB = createDefaultKey(USER_B, "shared-key");

        repository.saveAndFlush(keyA);
        repository.saveAndFlush(keyB);

        assertThat(repository.findByUserIdAndIdempotencyKey(USER_A, "shared-key")).isPresent();
        assertThat(repository.findByUserIdAndIdempotencyKey(USER_B, "shared-key")).isPresent();
    }

    @Test
    @DisplayName("Should acquire pessimistic write lock and update key to completed")
    void shouldAcquirePessimisticWriteLockAndUpdate() {
        IdempotencyKey initial = repository.saveAndFlush(createDefaultKey(USER_A, "lock-key"));

        transactionTemplate.executeWithoutResult(status -> {
            IdempotencyKey locked = repository.findByUserIdAndIdempotencyKeyForUpdate(USER_A, "lock-key").orElseThrow();
            locked.complete(201, "{\"transferId\":\"0193297a-9a90-7888-8123-000000000099\"}");
            repository.saveAndFlush(locked);
        });

        IdempotencyKey reloaded = repository.findById(initial.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(reloaded.getResponseStatus()).isEqualTo(201);
        assertThat(reloaded.getResponseBody()).contains("0193297a-9a90-7888-8123-000000000099");
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should detect optimistic locking conflict when modifying stale entity")
    void shouldDetectOptimisticLockingConflict() {
        IdempotencyKey initial = repository.saveAndFlush(createDefaultKey(USER_A, "opt-lock-key"));

        // Transaction 1 commits first, incrementing version to 1
        transactionTemplate.executeWithoutResult(status -> {
            IdempotencyKey key1 = repository.findById(initial.getId()).orElseThrow();
            key1.complete(201, "{\"transferId\":\"first\"}");
            repository.saveAndFlush(key1);
        });

        // Stale initial instance (holding version 0) fails when saved
        assertThatThrownBy(() -> {
            initial.complete(200, "{\"transferId\":\"stale\"}");
            repository.saveAndFlush(initial);
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("Should check key existence efficiently")
    void shouldCheckKeyExistence() {
        repository.saveAndFlush(createDefaultKey(USER_A, "exists-key"));

        assertThat(repository.existsByUserIdAndIdempotencyKey(USER_A, "exists-key")).isTrue();
        assertThat(repository.existsByUserIdAndIdempotencyKey(USER_A, "non-existent")).isFalse();
        assertThat(repository.existsByUserIdAndIdempotencyKey(USER_B, "exists-key")).isFalse();
    }

    @Test
    @DisplayName("Should return empty optional when finding or locking non-existent key")
    void shouldReturnEmptyOptionalForNonExistentKey() {
        assertThat(repository.findByUserIdAndIdempotencyKey(USER_A, "non-existent")).isEmpty();

        transactionTemplate.executeWithoutResult(status -> {
            Optional<IdempotencyKey> locked = repository.findByUserIdAndIdempotencyKeyForUpdate(USER_A, "non-existent");
            assertThat(locked).isEmpty();
        });
    }

    @Test
    @DisplayName("Should find stalled keys in PROCESSING status before threshold while ignoring completed and failed keys")
    void shouldFindStalledKeys() {
        Instant now = Instant.now();
        repository.saveAndFlush(createDefaultKey(USER_A, "stalled-key"));

        // Completed key created before threshold should NOT be returned
        IdempotencyKey completedKey = createDefaultKey(USER_A, "completed-key");
        completedKey.complete(200, "{}");
        repository.saveAndFlush(completedKey);

        // Failed key created before threshold should NOT be returned
        IdempotencyKey failedKey = createDefaultKey(USER_A, "failed-key");
        failedKey.fail();
        repository.saveAndFlush(failedKey);

        // Threshold after creation should match ONLY the PROCESSING key
        Instant threshold = now.plus(1, ChronoUnit.MINUTES);
        List<IdempotencyKey> stalled = repository.findByStatusAndCreatedAtBefore(IdempotencyStatus.PROCESSING,
                threshold);

        assertThat(stalled).extracting(IdempotencyKey::getIdempotencyKey).containsExactly("stalled-key");

        // Threshold before creation should NOT match
        Instant pastThreshold = now.minus(1, ChronoUnit.MINUTES);
        List<IdempotencyKey> none = repository.findByStatusAndCreatedAtBefore(IdempotencyStatus.PROCESSING,
                pastThreshold);
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("Should bulk delete expired keys using modifying query")
    void shouldBulkDeleteExpiredKeys() {
        Instant now = Instant.now();

        // 2 expired keys, 1 valid future key
        IdempotencyKey expired1 = IdempotencyKey.create(USER_A, "exp-1", ENDPOINT, HASH, now.minusSeconds(100));
        IdempotencyKey expired2 = IdempotencyKey.create(USER_A, "exp-2", ENDPOINT, HASH, now.minusSeconds(50));
        IdempotencyKey valid = IdempotencyKey.create(USER_A, "valid", ENDPOINT, HASH, now.plusSeconds(3600));

        repository.saveAllAndFlush(List.of(expired1, expired2, valid));

        int deletedCount = transactionTemplate.execute(status -> repository.deleteExpiredBefore(now));

        assertThat(deletedCount).isEqualTo(2);
        assertThat(repository.existsByUserIdAndIdempotencyKey(USER_A, "exp-1")).isFalse();
        assertThat(repository.existsByUserIdAndIdempotencyKey(USER_A, "exp-2")).isFalse();
        assertThat(repository.existsByUserIdAndIdempotencyKey(USER_A, "valid")).isTrue();
    }

    @ParameterizedTest(name = "Invalid response status: {0}")
    @ValueSource(ints = { 99, 600 })
    @DisplayName("Should enforce database check constraint on response_status (100 to 599)")
    void shouldEnforceDatabaseResponseStatusConstraint(int invalidResponseStatus) {
        assertThatThrownBy(() -> executeDirectInsert(UUID.randomUUID(), USER_A,
                "chk-status-key-" + invalidResponseStatus, ENDPOINT, HASH, "COMPLETED", invalidResponseStatus))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("chk_idempotency_keys_response_status");
    }

    @Test
    @DisplayName("Should enforce database check constraint on request_hash (64 lowercase hex chars)")
    void shouldEnforceDatabaseRequestHashConstraint() {
        // Non-hex characters
        assertThatThrownBy(() -> executeDirectInsert(UUID.randomUUID(), USER_A, "chk-hash-key-1", ENDPOINT,
                "invalid-not-64-hex", "PROCESSING", null)).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("chk_idempotency_keys_request_hash");

        // Uppercase hex characters (PostgreSQL regex ~ is case-sensitive)
        assertThatThrownBy(() -> executeDirectInsert(UUID.randomUUID(), USER_A, "chk-hash-key-2", ENDPOINT,
                HASH.toUpperCase(), "PROCESSING", null)).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("chk_idempotency_keys_request_hash");
    }

    @Test
    @DisplayName("Should enforce database check constraint on status (PROCESSING, COMPLETED, FAILED)")
    void shouldEnforceDatabaseStatusConstraint() {
        assertThatThrownBy(() -> executeDirectInsert(UUID.randomUUID(), USER_A, "chk-status-enum", ENDPOINT, HASH,
                "UNKNOWN_STATUS", null)).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("chk_idempotency_keys_status");
    }

    @ParameterizedTest(name = "Invalid key format: \"{0}\"")
    @ValueSource(strings = { "key with spaces", "key@invalid!" })
    @DisplayName("Should enforce database check constraint on key format")
    void shouldEnforceDatabaseKeyFormatConstraint(String invalidKey) {
        assertThatThrownBy(
                () -> executeDirectInsert(UUID.randomUUID(), USER_A, invalidKey, ENDPOINT, HASH, "PROCESSING", null))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("chk_idempotency_keys_key_format");
    }
}
