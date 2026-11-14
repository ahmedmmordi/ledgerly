package io.github.ahmedmmordi.ledgerly.ledger.account.dto;

import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountStatus;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountType;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountResponse(UUID id, UUID userId, String accountNumber, String currency, long balanceInMinorUnits,
        BigDecimal balance, AccountType type, AccountStatus status, Instant createdAt, Instant updatedAt) {

    public static AccountResponse from(Account account) {
        Objects.requireNonNull(account, "Account must not be null");
        Money moneyBalance = account.getBalance();
        return new AccountResponse(account.getId(), account.getUserId(), account.getAccountNumber(),
                account.getCurrency(), moneyBalance.amountInMinorUnits(), moneyBalance.toBigDecimal(),
                account.getType(), account.getStatus(), account.getCreatedAt(), account.getUpdatedAt());
    }
}
