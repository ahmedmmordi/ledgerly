package io.github.ahmedmmordi.ledgerly.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@DisplayName("OutboxEvent Repository & PostgreSQL Schema Integration Tests")
@SuppressWarnings({ "SqlResolve", "null", "SpellCheckingInspection" })
class OutboxEventRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("ledger.outbox.poller.enabled", () -> "false");
    }

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    private static final String AGGREGATE_TYPE = "TRANSFER";
    private static final UUID AGGREGATE_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final String EVENT_TYPE = "TransferCompletedEvent";
    private static final String PAYLOAD = "{\"transferId\":\"0193297a-9a90-7888-8123-000000000001\",\"amount\":1000}";

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Nested
    @DisplayName("Persistence & BaseEntity Auditing Tests")
    class PersistenceTests {

        @Test
        @DisplayName("Should persist OutboxEvent with UUID v7 PK, created_at, updated_at, and version 0")
        void shouldPersistOutboxEventWithAuditedFields() {
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            OutboxEvent saved = repository.saveAndFlush(event);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getId().version()).isEqualTo(7);
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
            assertThat(saved.getVersion()).isZero();
            assertThat(saved.getRetryCount()).isZero();
            assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(saved.getAggregateType()).isEqualTo(AGGREGATE_TYPE);
            assertThat(saved.getAggregateId()).isEqualTo(AGGREGATE_ID);
            assertThat(saved.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(saved.getPayload()).isEqualTo(PAYLOAD);
            assertThat(saved.getSentAt()).isNull();
            assertThat(saved.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("Should detect optimistic locking conflict when modifying concurrently")
        void shouldDetectOptimisticLockingConflict() {
            OutboxEvent event = repository
                    .saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD));

            OutboxEvent copy1 = repository.findById(event.getId()).orElseThrow();
            OutboxEvent copy2 = repository.findById(event.getId()).orElseThrow();

            copy1.markSent(Instant.now());
            repository.saveAndFlush(copy1);

            copy2.recordFailure("Stale update error", 3);
            assertThatThrownBy(() -> repository.saveAndFlush(copy2))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        }
    }

    @Nested
    @DisplayName("PostgreSQL Check Constraints & Indexes Tests")
    @SuppressWarnings({ "SqlResolve", "SpellCheckingInspection" })
    class ConstraintAndIndexTests {

        private void insertRawOutboxRow(String aggregateType, String eventType, String payload, String status,
                int retryCount) {
            UUID id = UUID.randomUUID();
            Timestamp now = Timestamp.from(Instant.now());
            jdbcTemplate.update(
                    "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, created_at, updated_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, aggregateType, AGGREGATE_ID, eventType, payload, status, retryCount, now, now, 0);
        }

        @ParameterizedTest(name = "Invalid status: ''{0}''")
        @ValueSource(strings = { "INVALID", "COMPLETED", "PROCESSED", "" })
        @DisplayName("Should reject invalid status via chk_outbox_events_status")
        void shouldRejectInvalidStatus(String invalidStatus) {
            assertThatThrownBy(() -> insertRawOutboxRow(AGGREGATE_TYPE, EVENT_TYPE, PAYLOAD, invalidStatus, 0))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_outbox_events_status");
        }

        @Test
        @DisplayName("Should reject negative retry_count via chk_outbox_events_retry_count")
        void shouldRejectNegativeRetryCount() {
            assertThatThrownBy(() -> insertRawOutboxRow(AGGREGATE_TYPE, EVENT_TYPE, PAYLOAD, "PENDING", -1))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_outbox_events_retry_count");
        }

        @ParameterizedTest(name = "Blank aggregate_type: ''{0}''")
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should reject blank aggregate_type via chk_outbox_events_aggregate_type")
        void shouldRejectBlankAggregateType(String blankType) {
            assertThatThrownBy(() -> insertRawOutboxRow(blankType, EVENT_TYPE, PAYLOAD, "PENDING", 0))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_outbox_events_aggregate_type");
        }

        @ParameterizedTest(name = "Blank event_type: ''{0}''")
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should reject blank event_type via chk_outbox_events_event_type")
        void shouldRejectBlankEventType(String blankEventType) {
            assertThatThrownBy(() -> insertRawOutboxRow(AGGREGATE_TYPE, blankEventType, PAYLOAD, "PENDING", 0))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_outbox_events_event_type");
        }

        @ParameterizedTest(name = "Blank payload: ''{0}''")
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should reject blank payload via chk_outbox_events_payload")
        void shouldRejectBlankPayload(String blankPayload) {
            assertThatThrownBy(() -> insertRawOutboxRow(AGGREGATE_TYPE, EVENT_TYPE, blankPayload, "PENDING", 0))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_outbox_events_payload");
        }

        @Test
        @DisplayName("Should verify PostgreSQL indexes in pg_indexes including partial index on PENDING status")
        void shouldVerifyPostgreSqlIndexes() {
            List<Map<String, Object>> indexes = jdbcTemplate
                    .queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'outbox_events'");

            Map<String, String> indexDefs = indexes.stream().collect(java.util.stream.Collectors
                    .toMap(row -> (String) row.get("indexname"), row -> (String) row.get("indexdef")));

            assertThat(indexDefs).containsKey("pk_outbox_events");
            assertThat(indexDefs).containsKey("idx_outbox_events_pending_created");
            assertThat(indexDefs).containsKey("idx_outbox_events_aggregate");

            // Verify partial index clause
            String pendingIndexDef = indexDefs.get("idx_outbox_events_pending_created");
            assertThat(pendingIndexDef).contains("WHERE ((status)::text = 'PENDING'::text)");
        }
    }

    @Nested
    @DisplayName("Query & Polling Tests")
    class QueryAndPollingTests {

        @Test
        @DisplayName("Should poll pending events in chronological order with pagination")
        void shouldPollPendingEventsChronologically() {
            OutboxEvent e1 = repository
                    .saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"seq\":1}"));
            OutboxEvent e2 = repository
                    .saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"seq\":2}"));
            OutboxEvent e3 = repository
                    .saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"seq\":3}"));

            // Sent event should not be returned by the PENDING query
            OutboxEvent sentEvent = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"seq\":0}");
            sentEvent.markSent(Instant.now());
            repository.saveAndFlush(sentEvent);

            List<OutboxEvent> firstPage = repository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING,
                    PageRequest.of(0, 2));
            assertThat(firstPage).hasSize(2);
            assertThat(firstPage.getFirst().getId()).isEqualTo(e1.getId());
            assertThat(firstPage.get(1).getId()).isEqualTo(e2.getId());

            List<OutboxEvent> secondPage = repository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING,
                    PageRequest.of(1, 2));
            assertThat(secondPage).hasSize(1);
            assertThat(secondPage.getFirst().getId()).isEqualTo(e3.getId());
        }

        @Test
        @DisplayName("Should lock and skip locked rows under concurrent transactions via FOR UPDATE SKIP LOCKED")
        void shouldSkipLockedRowsUnderConcurrentTransactions() throws Exception {
            OutboxEvent e1 = repository
                    .saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"seq\":1}"));
            OutboxEvent e2 = repository
                    .saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"seq\":2}"));

            CountDownLatch thread1HoldingLock = new CountDownLatch(1);
            CountDownLatch thread2Completed = new CountDownLatch(1);
            CountDownLatch thread1Finish = new CountDownLatch(1);

            AtomicReference<List<OutboxEvent>> thread1Result = new AtomicReference<>();
            AtomicReference<List<OutboxEvent>> thread2Result = new AtomicReference<>();
            AtomicReference<Throwable> thread1Error = new AtomicReference<>();
            AtomicReference<Throwable> thread2Error = new AtomicReference<>();

            TransactionTemplate requiresNewTemplate = new TransactionTemplate(transactionManager);
            requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            // Thread 1: Acquire lock on the first available pending event (limit 1)
            Thread t1 = new Thread(() -> {
                try {
                    requiresNewTemplate.execute(_ -> {
                        List<OutboxEvent> locked = repository.findPendingForProcessing(OutboxStatus.PENDING,
                                PageRequest.of(0, 1));
                        thread1Result.set(locked);
                        thread1HoldingLock.countDown();
                        try {
                            boolean finished = thread1Finish.await(5, TimeUnit.SECONDS);
                            if (!finished) {
                                throw new IllegalStateException("Timed out waiting for thread1Finish latch");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    thread1Error.set(t);
                    thread1HoldingLock.countDown();
                }
            });

            // Thread 2: Concurrently execute findPendingForProcessing (limit 1) - should SKIP row locked by Thread 1
            Thread t2 = new Thread(() -> {
                try {
                    boolean lockedAcquired = thread1HoldingLock.await(5, TimeUnit.SECONDS);
                    if (!lockedAcquired) {
                        throw new IllegalStateException("Timed out waiting for thread1HoldingLock latch");
                    }
                    requiresNewTemplate.execute(_ -> {
                        List<OutboxEvent> lockedEvents = repository.findPendingForProcessing(OutboxStatus.PENDING,
                                PageRequest.of(0, 1));
                        thread2Result.set(lockedEvents);
                        return null;
                    });
                } catch (Throwable t) {
                    thread2Error.set(t);
                } finally {
                    thread2Completed.countDown();
                }
            });

            t1.start();
            t2.start();

            boolean t2Finished = thread2Completed.await(5, TimeUnit.SECONDS);
            thread1Finish.countDown();
            t1.join(5000);
            t2.join(5000);

            assertThat(thread1Error.get()).isNull();
            assertThat(thread2Error.get()).isNull();
            assertThat(t2Finished).as("Thread 2 should complete immediately by skipping locked row").isTrue();
            assertThat(thread1Result.get()).hasSize(1);
            assertThat(thread1Result.get().getFirst().getId()).isEqualTo(e1.getId());

            // Thread 2 skipped e1 and grabbed e2 directly without waiting or deadlocking
            assertThat(thread2Result.get()).hasSize(1);
            assertThat(thread2Result.get().getFirst().getId()).isEqualTo(e2.getId());
        }

        @Test
        @DisplayName("Should return empty list when no pending events exist")
        void shouldReturnEmptyListWhenNoPendingEventsExist() {
            OutboxEvent sent = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"status\":\"sent\"}");
            sent.markSent(Instant.now());
            repository.saveAndFlush(sent);

            List<OutboxEvent> pending = transactionTemplate
                    .execute(_ -> repository.findPendingForProcessing(OutboxStatus.PENDING, PageRequest.of(0, 10)));
            assertThat(pending).isEmpty();
        }

        @Test
        @DisplayName("Should enforce active transaction when executing findPendingForProcessing with pessimistic write lock")
        void shouldEnforceActiveTransactionForPessimisticLocking() {
            assertThatThrownBy(() -> repository.findPendingForProcessing(OutboxStatus.PENDING, PageRequest.of(0, 10)))
                    .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);
        }

        @Test
        @DisplayName("Should query events by aggregateType and aggregateId in chronological order")
        void shouldFindByAggregateTypeAndAggregateId() {
            UUID otherAggregateId = UUID.randomUUID();
            OutboxEvent e1 = repository
                    .saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"step\":1}"));
            repository.saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, otherAggregateId, EVENT_TYPE, "{\"step\":99}"));
            OutboxEvent e2 = repository
                    .saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"step\":2}"));

            List<OutboxEvent> aggregateEvents = repository
                    .findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(AGGREGATE_TYPE, AGGREGATE_ID);

            assertThat(aggregateEvents).hasSize(2);
            assertThat(aggregateEvents.getFirst().getId()).isEqualTo(e1.getId());
            assertThat(aggregateEvents.get(1).getId()).isEqualTo(e2.getId());
        }

        @Test
        @DisplayName("Should count events by status accurately")
        void shouldCountEventsByStatus() {
            repository.saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"c\":1}"));
            repository.saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"c\":2}"));

            OutboxEvent sent = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"c\":3}");
            sent.markSent(Instant.now());
            repository.saveAndFlush(sent);

            OutboxEvent failed = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"c\":4}");
            failed.recordFailure("Fatal broker error", 1);
            repository.saveAndFlush(failed);

            assertThat(repository.countByStatus(OutboxStatus.PENDING)).isEqualTo(2);
            assertThat(repository.countByStatus(OutboxStatus.SENT)).isEqualTo(1);
            assertThat(repository.countByStatus(OutboxStatus.FAILED)).isEqualTo(1);
        }

        @Test
        @DisplayName("Should bulk delete SENT events older than retention threshold without affecting PENDING or recent events")
        void shouldBulkDeleteOldSentEvents() {
            Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS);
            Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);

            // Old SENT event (should be deleted)
            OutboxEvent oldSent = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"old\":true}");
            oldSent.markSent(tenDaysAgo);
            repository.saveAndFlush(oldSent);

            // Recent SENT event (should be preserved)
            OutboxEvent recentSent = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"recent\":true}");
            recentSent.markSent(oneHourAgo);
            repository.saveAndFlush(recentSent);

            // PENDING event (should be preserved)
            OutboxEvent pending = repository
                    .saveAndFlush(OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, "{\"pending\":true}"));

            Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
            Integer deletedCount = transactionTemplate
                    .execute(_ -> repository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, sevenDaysAgo));

            assertThat(deletedCount).isEqualTo(1);
            assertThat(repository.existsById(oldSent.getId())).isFalse();
            assertThat(repository.existsById(recentSent.getId())).isTrue();
            assertThat(repository.existsById(pending.getId())).isTrue();
        }

        @Test
        @DisplayName("Should return 0 when bulk delete finds no matching events")
        void shouldReturnZeroWhenNoEventsMatchBulkDelete() {
            Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
            Integer deletedCount = transactionTemplate
                    .execute(_ -> repository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, sevenDaysAgo));
            assertThat(deletedCount).isZero();
        }
    }
}
