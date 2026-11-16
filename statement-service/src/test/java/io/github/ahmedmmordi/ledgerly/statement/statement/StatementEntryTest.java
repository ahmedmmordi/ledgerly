package io.github.ahmedmmordi.ledgerly.statement.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StatementEntry Record Unit Tests")
@SuppressWarnings({ "null", "DataFlowIssue", "ConstantConditions" })
class StatementEntryTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @Test
    @DisplayName("valid inputs instantiate StatementEntry successfully with all properties accessible")
    void validEntryInstantiatesAllFields() {
        StatementEntry entry = new StatementEntry(eventId, transferId, StatementEntryType.CREDIT, 1000L, 5000L, now);

        assertThat(entry.eventId()).isEqualTo(eventId);
        assertThat(entry.transferId()).isEqualTo(transferId);
        assertThat(entry.type()).isEqualTo(StatementEntryType.CREDIT);
        assertThat(entry.amountMinorUnits()).isEqualTo(1000L);
        assertThat(entry.balanceAfterMinorUnits()).isEqualTo(5000L);
        assertThat(entry.occurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("accepts zero or negative balanceAfterMinorUnits for zeroed or overdraft accounts")
    void acceptsZeroOrNegativeBalanceAfter() {
        StatementEntry zeroBalance = new StatementEntry(eventId, transferId, StatementEntryType.DEBIT, 1000L, 0L, now);
        StatementEntry overdraft = new StatementEntry(UUID.randomUUID(), transferId, StatementEntryType.DEBIT, 1000L,
                -5000L, now);

        assertThat(zeroBalance.balanceAfterMinorUnits()).isZero();
        assertThat(overdraft.balanceAfterMinorUnits()).isEqualTo(-5000L);
    }

    @Test
    @DisplayName("rejects null eventId, transferId, type, or occurredAt")
    void rejectsNullRequiredFields() {
        assertThatThrownBy(() -> new StatementEntry(null, transferId, StatementEntryType.CREDIT, 1000L, 1000L, now))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("eventId");

        assertThatThrownBy(() -> new StatementEntry(eventId, null, StatementEntryType.CREDIT, 1000L, 1000L, now))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("transferId");

        assertThatThrownBy(() -> new StatementEntry(eventId, transferId, null, 1000L, 1000L, now))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("type");

        assertThatThrownBy(() -> new StatementEntry(eventId, transferId, StatementEntryType.CREDIT, 1000L, 1000L, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("occurredAt");
    }

    @Test
    @DisplayName("rejects zero or negative amountMinorUnits")
    void rejectsZeroOrNegativeAmount() {
        assertThatThrownBy(() -> new StatementEntry(eventId, transferId, StatementEntryType.CREDIT, 0L, 1000L, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");

        assertThatThrownBy(() -> new StatementEntry(eventId, transferId, StatementEntryType.CREDIT, -100L, 1000L, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }
}
