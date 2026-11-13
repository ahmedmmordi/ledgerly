package io.github.ahmedmmordi.ledgerly.ledger.transfer.rule;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class PositiveAmountRule implements TransferRule {

    @Override
    public void validate(TransferContext context) {
        if (!context.amount().isPositive()) {
            throw new IllegalArgumentException("Transfer amount must be strictly positive");
        }
    }
}
