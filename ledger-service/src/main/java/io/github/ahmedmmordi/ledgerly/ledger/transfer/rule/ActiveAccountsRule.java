package io.github.ahmedmmordi.ledgerly.ledger.transfer.rule;

import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotActiveException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(4)
public class ActiveAccountsRule implements TransferRule {

    @Override
    public void validate(TransferContext context) {
        assertActive(context.fromAccount());
        assertActive(context.toAccount());
    }

    private void assertActive(Account account) {
        if (!account.isActive()) {
            throw new AccountNotActiveException(account.getId(), account.getStatus().name());
        }
    }
}
