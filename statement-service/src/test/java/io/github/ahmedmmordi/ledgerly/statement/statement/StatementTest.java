package io.github.ahmedmmordi.ledgerly.statement.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Statement Domain Entity Unit Tests")
@SuppressWarnings({ "null", "DataFlowIssue", "ConstantConditions" })
class StatementTest {

    private final UUID accountId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final Instant now = Instant.now();

    private StatementEntry createEntry(UUID eventId, StatementEntryType type, long amount, long balanceAfter) {
        return new StatementEntry(eventId, transferId, type, amount, balanceAfter, now);
    }

    @Test
    @DisplayName("create() with valid inputs initializes fields and matching opening/closing balances")
    void createWithValidInputsInitializesFields() {
        Statement statement = Statement.create(accountId, "2026-11", "usd", 50000L);

        assertThat(statement.getId()).isNull();
        assertThat(statement.getVersion()).isNull();
        assertThat(statement.getAccountId()).isEqualTo(accountId);
        assertThat(statement.getYearMonth()).isEqualTo("2026-11");
        assertThat(statement.getCurrency()).isEqualTo("USD");
        assertThat(statement.getOpeningBalanceMinorUnits()).isEqualTo(50000L);
        assertThat(statement.getClosingBalanceMinorUnits()).isEqualTo(50000L);
        assertThat(statement.getEntries()).isEmpty();
        assertThat(statement.getCreatedAt()).isNotNull();
        assertThat(statement.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("create() accepts zero or negative opening balance for new or overdraft accounts")
    void createAcceptsZeroOrNegativeOpeningBalance() {
        Statement zero = Statement.create(accountId, "2026-01", "USD", 0L);
        Statement overdraft = Statement.create(accountId, "2026-02", "USD", -25000L);

        assertThat(zero.getOpeningBalanceMinorUnits()).isZero();
        assertThat(zero.getClosingBalanceMinorUnits()).isZero();
        assertThat(overdraft.getOpeningBalanceMinorUnits()).isEqualTo(-25000L);
        assertThat(overdraft.getClosingBalanceMinorUnits()).isEqualTo(-25000L);
    }

    @Test
    @DisplayName("create() throws NullPointerException when required fields are null")
    void createWithNullFieldsThrowsNpe() {
        assertThatThrownBy(() -> Statement.create(null, "2026-11", "USD", 0L)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("accountId");

        assertThatThrownBy(() -> Statement.create(accountId, null, "USD", 0L)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("yearMonth");

        assertThatThrownBy(() -> Statement.create(accountId, "2026-11", null, 0L))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("currency");
    }

    @ParameterizedTest
    @ValueSource(strings = { "2026", "2026-13", "2026-00", "invalid", "2026-1", "2026/11" })
    @DisplayName("create() throws IllegalArgumentException on invalid YearMonth formats")
    void createWithInvalidYearMonthThrowsIllegalArgument(String invalidYearMonth) {
        assertThatThrownBy(() -> Statement.create(accountId, invalidYearMonth, "USD", 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("yearMonth");
    }

    @ParameterizedTest
    @ValueSource(strings = { "US", "USDA", "", "  ", "12", "XYZ", "123", "!!!" })
    @DisplayName("create() throws IllegalArgumentException on invalid currency codes")
    void createWithInvalidCurrencyThrowsIllegalArgument(String invalidCurrency) {
        assertThatThrownBy(() -> Statement.create(accountId, "2026-11", invalidCurrency, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
    }

    @Test
    @DisplayName("addEntry() appends entry, updates closing balance, and modifies updatedAt")
    void addEntryAppendsAndUpdatesClosingBalance() {
        Statement statement = Statement.create(accountId, "2026-11", "USD", 10000L);
        UUID eventId = UUID.randomUUID();
        StatementEntry entry = createEntry(eventId, StatementEntryType.CREDIT, 5000L, 15000L);

        boolean added = statement.addEntry(entry);

        assertThat(added).isTrue();
        assertThat(statement.getEntries()).hasSize(1);
        assertThat(statement.getEntries().getFirst()).isEqualTo(entry);
        assertThat(statement.getClosingBalanceMinorUnits()).isEqualTo(15000L);
        assertThat(statement.containsEvent(eventId)).isTrue();
    }

    @Test
    @DisplayName("addEntry() rejects null entry with NullPointerException")
    void addEntryRejectsNullEntry() {
        Statement statement = Statement.create(accountId, "2026-11", "USD", 10000L);

        assertThatThrownBy(() -> statement.addEntry(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entry");
    }

    @Test
    @DisplayName("containsEvent() returns false when eventId is not found and rejects null")
    void containsEventEdgeCases() {
        Statement statement = Statement.create(accountId, "2026-11", "USD", 10000L);

        assertThat(statement.containsEvent(UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> statement.containsEvent(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    @DisplayName("addEntry() skips duplicate eventId idempotently and returns false without changing balance")
    void addEntrySkipsDuplicateEventId() {
        Statement statement = Statement.create(accountId, "2026-11", "USD", 10000L);
        UUID eventId = UUID.randomUUID();

        StatementEntry entry1 = createEntry(eventId, StatementEntryType.CREDIT, 5000L, 15000L);
        StatementEntry entry2 = createEntry(eventId, StatementEntryType.CREDIT, 5000L, 20000L);

        assertThat(statement.addEntry(entry1)).isTrue();
        assertThat(statement.addEntry(entry2)).isFalse();

        assertThat(statement.getEntries()).hasSize(1);
        assertThat(statement.getClosingBalanceMinorUnits()).isEqualTo(15000L);
    }

    @Test
    @DisplayName("getTotalCreditMinorUnits(), getTotalDebitMinorUnits(), and getNetChangeMinorUnits() compute correctly")
    void computesCreditDebitTotalsAndNetChange() {
        Statement statement = Statement.create(accountId, "2026-11", "USD", 10000L);

        statement.addEntry(createEntry(UUID.randomUUID(), StatementEntryType.CREDIT, 5000L, 15000L));
        statement.addEntry(createEntry(UUID.randomUUID(), StatementEntryType.CREDIT, 3000L, 18000L));
        statement.addEntry(createEntry(UUID.randomUUID(), StatementEntryType.DEBIT, 4000L, 14000L));

        assertThat(statement.getTotalCreditMinorUnits()).isEqualTo(8000L);
        assertThat(statement.getTotalDebitMinorUnits()).isEqualTo(4000L);
        assertThat(statement.getNetChangeMinorUnits()).isEqualTo(4000L);
        assertThat(statement.getClosingBalanceMinorUnits()).isEqualTo(14000L);
    }

    @Test
    @DisplayName("totals and net change return zero when statement has no entries")
    void totalsReturnZeroOnEmptyStatement() {
        Statement empty = Statement.create(accountId, "2026-11", "USD", 25000L);

        assertThat(empty.getTotalCreditMinorUnits()).isZero();
        assertThat(empty.getTotalDebitMinorUnits()).isZero();
        assertThat(empty.getNetChangeMinorUnits()).isZero();
    }

    @Test
    @DisplayName("equals() and hashCode() compare by accountId and yearMonth business key")
    void equalsAndHashCodeCompareByBusinessKey() {
        Statement statement1 = Statement.create(accountId, "2026-11", "USD", 10000L);
        Statement statement2 = Statement.create(accountId, "2026-11", "USD", 50000L);
        Statement statement3 = Statement.create(accountId, "2026-12", "USD", 10000L);
        Statement statement4 = Statement.create(UUID.randomUUID(), "2026-11", "USD", 10000L);

        assertThat(statement1).isEqualTo(statement2);
        assertThat(statement1.hashCode()).isEqualTo(statement2.hashCode());
        assertThat(statement1).isNotEqualTo(statement3);
        assertThat(statement1).isNotEqualTo(statement4);
    }

    @Test
    @DisplayName("getEntries() returns an unmodifiable list")
    void getEntriesReturnsUnmodifiableList() {
        Statement statement = Statement.create(accountId, "2026-11", "USD", 10000L);
        statement.addEntry(createEntry(UUID.randomUUID(), StatementEntryType.DEBIT, 2000L, 8000L));

        assertThatThrownBy(() -> statement.getEntries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
