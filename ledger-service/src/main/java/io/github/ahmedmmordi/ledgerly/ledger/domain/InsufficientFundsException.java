package io.github.ahmedmmordi.ledgerly.ledger.domain;

import java.util.UUID;
import lombok.Getter;

@Getter
public class InsufficientFundsException extends RuntimeException {

    private final UUID accountId;
    private final Money requestedAmount;
    private final Money currentBalance;

    public InsufficientFundsException(UUID accountId, Money requestedAmount, Money currentBalance) {
        super("Account %s has insufficient funds: balance %s is less than requested %s".formatted(accountId,
                currentBalance, requestedAmount));
        this.accountId = accountId;
        this.requestedAmount = requestedAmount;
        this.currentBalance = currentBalance;
    }
}
