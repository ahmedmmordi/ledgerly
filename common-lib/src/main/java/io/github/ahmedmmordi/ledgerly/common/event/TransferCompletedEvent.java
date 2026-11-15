package io.github.ahmedmmordi.ledgerly.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Shared event record emitted upon successful completion of a monetary transfer.
 */
@NullMarked
public record TransferCompletedEvent(UUID eventId, UUID transferId, UUID fromAccountId, UUID toAccountId,
        long amountInMinorUnits, String currency, Instant occurredAt, int version) {

    public static final String AGGREGATE_TYPE = "TRANSFER";
    public static final String EVENT_TYPE = "TransferCompletedEvent";
    public static final int CURRENT_VERSION = 1;

    public TransferCompletedEvent {
        Objects.requireNonNull(eventId, "Event ID must not be null");
        Objects.requireNonNull(transferId, "Transfer ID must not be null");
        Objects.requireNonNull(fromAccountId, "From account ID must not be null");
        Objects.requireNonNull(toAccountId, "To account ID must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt timestamp must not be null");

        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("From account and to account must be distinct");
        }
        if (amountInMinorUnits <= 0) {
            throw new IllegalArgumentException("Amount in minor units must be strictly positive");
        }
        String trimmedCurrency = currency.trim().toUpperCase();
        if (trimmedCurrency.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO code");
        }
        currency = trimmedCurrency;
        if (version < 1) {
            throw new IllegalArgumentException("Event schema version must be at least 1");
        }
    }

    public static TransferCompletedEvent of(UUID transferId, UUID fromAccountId, UUID toAccountId,
            long amountInMinorUnits, String currency) {
        return new TransferCompletedEvent(UUID.randomUUID(), transferId, fromAccountId, toAccountId, amountInMinorUnits,
                currency, Instant.now(), CURRENT_VERSION);
    }
}
