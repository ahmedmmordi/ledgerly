package io.github.ahmedmmordi.ledgerly.statement.statement;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

// Embedded entry within a monthly account statement
@NullMarked
public record StatementEntry(UUID eventId, UUID transferId, StatementEntryType type, long amountMinorUnits,
        long balanceAfterMinorUnits, Instant occurredAt) {

    public StatementEntry {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("amountMinorUnits must be strictly positive");
        }
    }
}
