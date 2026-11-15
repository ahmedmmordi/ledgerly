package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import java.util.Objects;

public record ReservationResult(IdempotencyKey key, boolean isCached) {

    public ReservationResult {
        Objects.requireNonNull(key, "Idempotency key must not be null");
    }

    public static ReservationResult reserved(IdempotencyKey key) {
        return new ReservationResult(key, false);
    }

    public static ReservationResult cached(IdempotencyKey key) {
        return new ReservationResult(key, true);
    }
}
