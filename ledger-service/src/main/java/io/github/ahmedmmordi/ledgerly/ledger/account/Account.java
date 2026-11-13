package io.github.ahmedmmordi.ledgerly.ledger.account;

import io.github.ahmedmmordi.ledgerly.common.entity.BaseEntity;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotActiveException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.CurrencyMismatchException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.InsufficientFundsException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "accounts")
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Account extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_number", nullable = false, unique = true, length = 34)
    private String accountNumber;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    public static Account createCustomer(UUID userId, String accountNumber, String currencyCode) {
        return create(userId, accountNumber, currencyCode, AccountType.CUSTOMER);
    }

    public static Account createSystem(UUID userId, String accountNumber, String currencyCode) {
        return create(userId, accountNumber, currencyCode, AccountType.SYSTEM);
    }

    private static Account create(UUID userId, String accountNumber, String currencyCode, AccountType type) {
        Objects.requireNonNull(userId, "User ID must not be null");
        Objects.requireNonNull(accountNumber, "Account number must not be null");
        Objects.requireNonNull(currencyCode, "Currency must not be null");

        String normalizedAccountNumber = accountNumber.trim().toUpperCase();
        if (normalizedAccountNumber.isEmpty()) {
            throw new IllegalArgumentException("Account number must not be blank");
        }

        String normalizedCurrency = currencyCode.trim().toUpperCase();
        Currency.getInstance(normalizedCurrency); // Validates ISO 4217

        return Account.builder().userId(userId).accountNumber(normalizedAccountNumber).currency(normalizedCurrency)
                .balance(0L).type(type).status(AccountStatus.ACTIVE).build();
    }

    public Money getBalance() {
        return Money.ofMinor(this.balance, this.currency);
    }

    public void debit(Money amount) {
        assertActive();
        assertMatchingCurrency(amount);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Debit amount must be strictly positive");
        }
        if (this.type == AccountType.CUSTOMER && this.balance < amount.amountInMinorUnits()) {
            throw new InsufficientFundsException(getId(), amount, getBalance());
        }
        this.balance = Math.subtractExact(this.balance, amount.amountInMinorUnits());
    }

    public void credit(Money amount) {
        assertActive();
        assertMatchingCurrency(amount);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Credit amount must be strictly positive");
        }
        this.balance = Math.addExact(this.balance, amount.amountInMinorUnits());
    }

    public boolean isActive() {
        return this.status == AccountStatus.ACTIVE;
    }

    public boolean hasSufficientBalance(Money amount) {
        assertMatchingCurrency(amount);
        if (this.type == AccountType.SYSTEM) {
            return true;
        }
        return this.balance >= amount.amountInMinorUnits();
    }

    public void freeze() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot freeze a closed account");
        }
        this.status = AccountStatus.FROZEN;
    }

    public void activate() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot activate a closed account");
        }
        this.status = AccountStatus.ACTIVE;
    }

    public void close() {
        if (this.balance != 0L) {
            throw new IllegalStateException("Cannot close account with non-zero balance: " + this.balance);
        }
        this.status = AccountStatus.CLOSED;
    }

    private void assertActive() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(getId(), this.status.name());
        }
    }

    private void assertMatchingCurrency(Money amount) {
        Objects.requireNonNull(amount, "Amount must not be null");
        if (!amount.currency().getCurrencyCode().equals(this.currency)) {
            throw new CurrencyMismatchException(Currency.getInstance(this.currency), amount.currency());
        }
    }
}
