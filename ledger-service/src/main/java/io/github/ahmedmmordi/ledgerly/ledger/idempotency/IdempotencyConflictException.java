package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import lombok.Getter;

@Getter
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key '%s' is currently being processed by another request".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }
}
