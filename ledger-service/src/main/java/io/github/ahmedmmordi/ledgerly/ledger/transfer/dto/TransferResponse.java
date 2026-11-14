package io.github.ahmedmmordi.ledgerly.ledger.transfer.dto;

import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.Transfer;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransferResponse(UUID id, UUID fromAccountId, UUID toAccountId, long amountInMinorUnits,
        BigDecimal amount, String currency, TransferStatus status, String description, String failureReason,
        UUID initiatedByUserId, Instant createdAt, Instant updatedAt) {

    public static TransferResponse from(Transfer transfer) {
        Objects.requireNonNull(transfer, "Transfer must not be null");
        Money money = transfer.getAmount();
        return new TransferResponse(transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                money.amountInMinorUnits(), money.toBigDecimal(), transfer.getCurrency(), transfer.getStatus(),
                transfer.getDescription(), transfer.getFailureReason(), transfer.getInitiatedByUserId(),
                transfer.getCreatedAt(), transfer.getUpdatedAt());
    }
}
