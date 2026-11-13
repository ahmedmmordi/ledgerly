package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "ledger_entries")
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class LedgerEntry {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private EntryType type;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static LedgerEntry createDebit(UUID transferId, UUID accountId, Money amount, Money balanceAfter) {
        return create(transferId, accountId, EntryType.DEBIT, amount, balanceAfter);
    }

    public static LedgerEntry createCredit(UUID transferId, UUID accountId, Money amount, Money balanceAfter) {
        return create(transferId, accountId, EntryType.CREDIT, amount, balanceAfter);
    }

    private static LedgerEntry create(UUID transferId, UUID accountId, EntryType type, Money amount,
            Money balanceAfter) {
        Objects.requireNonNull(transferId, "Transfer ID must not be null");
        Objects.requireNonNull(accountId, "Account ID must not be null");
        Objects.requireNonNull(type, "Entry type must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        Objects.requireNonNull(balanceAfter, "Balance after must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Ledger entry amount must be strictly positive");
        }

        return LedgerEntry.builder().transferId(transferId).accountId(accountId).type(type)
                .amount(amount.amountInMinorUnits()).balanceAfter(balanceAfter.amountInMinorUnits()).build();
    }

    public boolean isDebit() {
        return this.type == EntryType.DEBIT;
    }

    public boolean isCredit() {
        return this.type == EntryType.CREDIT;
    }

    public Money getAmount(Currency currency) {
        return Money.ofMinor(this.amount, currency);
    }

    public Money getBalanceAfter(Currency currency) {
        return Money.ofMinor(this.balanceAfter, currency);
    }
}
