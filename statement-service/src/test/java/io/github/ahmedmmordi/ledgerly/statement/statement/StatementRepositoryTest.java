package io.github.ahmedmmordi.ledgerly.statement.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@SpringBootTest
@Testcontainers
@DisplayName("StatementRepository MongoDB Integration Tests")
@SuppressWarnings({ "null" })
class StatementRepositoryTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void configureMongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> mongo.getConnectionString() + "/test");
    }

    @Autowired
    private StatementRepository statementRepository;

    private UUID accountId;
    private final UUID transferId = UUID.randomUUID();
    private final Instant now = Instant.now();

    private StatementEntry createEntry(UUID eventId, StatementEntryType type, long amount, long balanceAfter) {
        return new StatementEntry(eventId, transferId, type, amount, balanceAfter, now);
    }

    @BeforeEach
    void setUp() {
        statementRepository.deleteAll();
        accountId = UUID.randomUUID();
    }

    @Test
    @DisplayName("save() persists statement and embedded entries, findByAccountIdAndYearMonth() retrieves it")
    void saveAndFindStatementWithEntries() {
        Statement statement = Statement.create(accountId, "2026-11", "USD", 50000L);
        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();

        statement.addEntry(createEntry(eventId1, StatementEntryType.CREDIT, 10000L, 60000L));
        statement.addEntry(createEntry(eventId2, StatementEntryType.DEBIT, 5000L, 55000L));

        Statement saved = statementRepository.save(statement);
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getVersion()).isNotNull().isEqualTo(0L);

        Optional<Statement> found = statementRepository.findByAccountIdAndYearMonth(accountId, "2026-11");
        assertThat(found).isPresent();

        Statement retrieved = found.get();
        assertThat(retrieved.getId()).isEqualTo(saved.getId());
        assertThat(retrieved.getAccountId()).isEqualTo(accountId);
        assertThat(retrieved.getYearMonth()).isEqualTo("2026-11");
        assertThat(retrieved.getCurrency()).isEqualTo("USD");
        assertThat(retrieved.getOpeningBalanceMinorUnits()).isEqualTo(50000L);
        assertThat(retrieved.getClosingBalanceMinorUnits()).isEqualTo(55000L);
        assertThat(retrieved.getEntries()).hasSize(2);
        assertThat(retrieved.containsEvent(eventId1)).isTrue();
        assertThat(retrieved.containsEvent(eventId2)).isTrue();
        assertThat(retrieved.getCreatedAt()).isNotNull();
        assertThat(retrieved.getUpdatedAt()).isNotNull();
        assertThat(retrieved.getVersion()).isEqualTo(0L);

        StatementEntry firstEntry = retrieved.getEntries().getFirst();
        assertThat(firstEntry.eventId()).isEqualTo(eventId1);
        assertThat(firstEntry.transferId()).isEqualTo(transferId);
        assertThat(firstEntry.type()).isEqualTo(StatementEntryType.CREDIT);
        assertThat(firstEntry.amountMinorUnits()).isEqualTo(10000L);
        assertThat(firstEntry.balanceAfterMinorUnits()).isEqualTo(60000L);
    }

    @Test
    @DisplayName("findByAccountIdAndYearMonth() returns empty Optional when statement does not exist")
    void findByAccountIdAndYearMonthReturnsEmptyWhenNotFound() {
        Optional<Statement> found = statementRepository.findByAccountIdAndYearMonth(accountId, "2099-01");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("existsByAccountIdAndYearMonth() returns true when present, false when absent")
    void existsByAccountIdAndYearMonth() {
        statementRepository.save(Statement.create(accountId, "2026-11", "USD", 0L));

        assertThat(statementRepository.existsByAccountIdAndYearMonth(accountId, "2026-11")).isTrue();
        assertThat(statementRepository.existsByAccountIdAndYearMonth(accountId, "2026-12")).isFalse();
        assertThat(statementRepository.existsByAccountIdAndYearMonth(UUID.randomUUID(), "2026-11")).isFalse();
    }

    @Test
    @DisplayName("compound index enforces uniqueness on (accountId, yearMonth)")
    void compoundUniqueIndexRejectsDuplicates() {
        Statement first = Statement.create(accountId, "2026-11", "USD", 10000L);
        statementRepository.save(first);

        Statement duplicate = Statement.create(accountId, "2026-11", "USD", 20000L);
        assertThatThrownBy(() -> statementRepository.save(duplicate)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("optimistic locking increments version on save() and rejects stale updates")
    void optimisticLockingPreventsConcurrentOverwrites() {
        Statement statement = Statement.create(accountId, "2026-11", "USD", 50000L);
        Statement saved = statementRepository.save(statement);
        assertThat(saved.getVersion()).isNotNull().isEqualTo(0L);

        saved.addEntry(createEntry(UUID.randomUUID(), StatementEntryType.CREDIT, 1000L, 51000L));
        Statement updated = statementRepository.save(saved);
        assertThat(updated.getVersion()).isNotNull().isEqualTo(1L);

        Statement staleStatement = new Statement(saved.getId(), accountId, "2026-11", "USD", 50000L, 50000L, List.of(),
                saved.getCreatedAt(), saved.getUpdatedAt(), 0L);

        assertThatThrownBy(() -> statementRepository.save(staleStatement))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc() returns paged statements in descending order")
    void findByAccountIdAndYearMonthBetween() {
        statementRepository.save(Statement.create(accountId, "2026-01", "USD", 1000L));
        statementRepository.save(Statement.create(accountId, "2026-02", "USD", 2000L));
        statementRepository.save(Statement.create(accountId, "2026-03", "USD", 3000L));
        statementRepository.save(Statement.create(accountId, "2026-04", "USD", 4000L));
        statementRepository.save(Statement.create(UUID.randomUUID(), "2026-02", "USD", 9999L));

        var page = statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(accountId, "2026-01",
                "2026-03", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Statement::getYearMonth).containsExactly("2026-03", "2026-02",
                "2026-01");

        var emptyPage = statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(accountId, "2030-01",
                "2030-03", PageRequest.of(0, 10));
        assertThat(emptyPage.getContent()).isEmpty();
        assertThat(emptyPage.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("findByAccountIdOrderByYearMonthDesc() returns all statements for account ordered descending")
    void findByAccountIdOrderByYearMonthDesc() {
        statementRepository.save(Statement.create(accountId, "2026-05", "USD", 1000L));
        statementRepository.save(Statement.create(accountId, "2026-06", "USD", 2000L));
        statementRepository.save(Statement.create(UUID.randomUUID(), "2026-06", "USD", 9999L));

        List<Statement> statements = statementRepository.findByAccountIdOrderByYearMonthDesc(accountId);

        assertThat(statements).extracting(Statement::getYearMonth).containsExactly("2026-06", "2026-05");
        assertThat(statementRepository.findByAccountIdOrderByYearMonthDesc(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("findFirstByAccountIdOrderByYearMonthDesc() returns latest statement for rollover")
    void findFirstByAccountIdOrderByYearMonthDesc() {
        statementRepository.save(Statement.create(accountId, "2026-04", "USD", 1000L));
        statementRepository.save(Statement.create(accountId, "2026-05", "USD", 2500L));
        statementRepository.save(Statement.create(UUID.randomUUID(), "2026-12", "USD", 9999L));

        Optional<Statement> latest = statementRepository.findFirstByAccountIdOrderByYearMonthDesc(accountId);

        assertThat(latest).isPresent();
        assertThat(latest.get().getYearMonth()).isEqualTo("2026-05");
        assertThat(latest.get().getClosingBalanceMinorUnits()).isEqualTo(2500L);
        assertThat(statementRepository.findFirstByAccountIdOrderByYearMonthDesc(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc() returns statements ascending for CSV export")
    void findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc() {
        statementRepository.save(Statement.create(accountId, "2026-02", "USD", 2000L));
        statementRepository.save(Statement.create(accountId, "2026-01", "USD", 1000L));
        statementRepository.save(Statement.create(accountId, "2026-03", "USD", 3000L));
        statementRepository.save(Statement.create(UUID.randomUUID(), "2026-02", "USD", 9999L));

        List<Statement> statements = statementRepository
                .findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(accountId, "2026-01", "2026-03");

        assertThat(statements).extracting(Statement::getYearMonth).containsExactly("2026-01", "2026-02", "2026-03");
    }
}
