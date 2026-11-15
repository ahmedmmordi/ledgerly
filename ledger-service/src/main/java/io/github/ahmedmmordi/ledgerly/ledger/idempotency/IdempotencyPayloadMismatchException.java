package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import lombok.Getter;

@Getter
public class IdempotencyPayloadMismatchException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyPayloadMismatchException(String idempotencyKey) {
        this(idempotencyKey,
                "Payload for idempotency key '%s' does not match the original request".formatted(idempotencyKey));
    }

    public IdempotencyPayloadMismatchException(String idempotencyKey, String message) {
        super(message);
        this.idempotencyKey = idempotencyKey;
    }
}
