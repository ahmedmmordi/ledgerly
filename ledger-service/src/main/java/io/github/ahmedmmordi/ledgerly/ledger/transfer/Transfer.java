package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import io.github.ahmedmmordi.ledgerly.common.entity.BaseEntity;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "transfers")
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Transfer extends BaseEntity {

    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private UUID toAccountId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "initiated_by_user_id")
    private UUID initiatedByUserId;

    public static Transfer createPending(UUID fromAccountId, UUID toAccountId, Money amount, String description,
            UUID initiatedByUserId) {
        return create(fromAccountId, toAccountId, amount, description, initiatedByUserId, TransferStatus.PENDING);
    }

    public static Transfer createCompleted(UUID fromAccountId, UUID toAccountId, Money amount, String description,
            UUID initiatedByUserId) {
        return create(fromAccountId, toAccountId, amount, description, initiatedByUserId, TransferStatus.COMPLETED);
    }

    private static Transfer create(UUID fromAccountId, UUID toAccountId, Money amount, String description,
            UUID initiatedByUserId, TransferStatus status) {
        Objects.requireNonNull(fromAccountId, "From account ID must not be null");
        Objects.requireNonNull(toAccountId, "To account ID must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("From account and to account must be distinct");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Transfer amount must be strictly positive");
        }

        return Transfer.builder().fromAccountId(fromAccountId).toAccountId(toAccountId)
                .amount(amount.amountInMinorUnits()).currency(amount.currency().getCurrencyCode()).status(status)
                .description(description).initiatedByUserId(initiatedByUserId).build();
    }

    public Money getAmount() {
        return Money.ofMinor(this.amount, this.currency);
    }

    public void markCompleted() {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException("Only PENDING transfers can be marked COMPLETED, current: " + this.status);
        }
        this.status = TransferStatus.COMPLETED;
    }

    public void markFailed(String reason) {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException("Only PENDING transfers can be marked FAILED, current: " + this.status);
        }
        this.status = TransferStatus.FAILED;
        if (reason != null && reason.length() > 255) {
            this.failureReason = reason.substring(0, 252) + "...";
        } else {
            this.failureReason = reason;
        }
    }
}
