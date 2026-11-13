package io.github.ahmedmmordi.ledgerly.ledger.transfer.rule;

import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import java.util.Objects;

public record TransferContext(Account fromAccount, Account toAccount, Money amount) {

    public TransferContext {
        Objects.requireNonNull(fromAccount, "fromAccount must not be null");
        Objects.requireNonNull(toAccount, "toAccount must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
