package io.github.ahmedmmordi.ledgerly.ledger.domain;

import java.util.UUID;
import lombok.Getter;

@Getter
public class AccountNotFoundException extends RuntimeException {

    private final UUID accountId;
    private final String accountNumber;

    public AccountNotFoundException(UUID accountId) {
        super("Account not found with ID: " + accountId);
        this.accountId = accountId;
        this.accountNumber = null;
    }

    public AccountNotFoundException(String accountNumber) {
        super("Account not found with account number: " + accountNumber);
        this.accountId = null;
        this.accountNumber = accountNumber;
    }
}
