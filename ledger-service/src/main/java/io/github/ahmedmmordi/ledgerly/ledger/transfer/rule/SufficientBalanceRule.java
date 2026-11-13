package io.github.ahmedmmordi.ledgerly.ledger.transfer.rule;

import io.github.ahmedmmordi.ledgerly.ledger.domain.InsufficientFundsException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(5)
public class SufficientBalanceRule implements TransferRule {

    @Override
    public void validate(TransferContext context) {
        if (!context.fromAccount().hasSufficientBalance(context.amount())) {
            throw new InsufficientFundsException(context.fromAccount().getId(), context.amount(),
                    context.fromAccount().getBalance());
        }
    }
}
