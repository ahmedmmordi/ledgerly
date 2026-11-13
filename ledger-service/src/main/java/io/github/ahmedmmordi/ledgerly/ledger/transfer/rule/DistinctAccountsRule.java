package io.github.ahmedmmordi.ledgerly.ledger.transfer.rule;

import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class DistinctAccountsRule implements TransferRule {

    @Override
    public void validate(TransferContext context) {
        if (context.fromAccount() == context.toAccount()) {
            throw new IllegalArgumentException("From account and to account must be distinct");
        }
        if (context.fromAccount().getId() != null
                && Objects.equals(context.fromAccount().getId(), context.toAccount().getId())) {
            throw new IllegalArgumentException("From account and to account must be distinct");
        }
        if (context.fromAccount().getAccountNumber() != null
                && Objects.equals(context.fromAccount().getAccountNumber(), context.toAccount().getAccountNumber())) {
            throw new IllegalArgumentException("From account and to account must be distinct");
        }
    }
}
