package io.github.ahmedmmordi.ledgerly.ledger.domain;

import java.util.UUID;
import lombok.Getter;

@Getter
public class TransferNotFoundException extends RuntimeException {

    private final UUID transferId;

    public TransferNotFoundException(UUID transferId) {
        super("Transfer not found with ID: " + transferId);
        this.transferId = transferId;
    }
}
