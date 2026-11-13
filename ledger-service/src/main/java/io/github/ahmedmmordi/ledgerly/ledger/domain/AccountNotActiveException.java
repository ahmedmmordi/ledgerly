package io.github.ahmedmmordi.ledgerly.ledger.domain;

import java.util.UUID;
import lombok.Getter;

@Getter
public class AccountNotActiveException extends RuntimeException {

    private final UUID accountId;
    private final String status;

    public AccountNotActiveException(UUID accountId, String status) {
        super("Account %s is not active (status: %s)".formatted(accountId, status));
        this.accountId = accountId;
        this.status = status;
    }
}
