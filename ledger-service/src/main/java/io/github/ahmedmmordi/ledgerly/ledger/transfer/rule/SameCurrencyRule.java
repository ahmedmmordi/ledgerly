package io.github.ahmedmmordi.ledgerly.ledger.transfer.rule;

import io.github.ahmedmmordi.ledgerly.ledger.domain.CurrencyMismatchException;
import java.util.Currency;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class SameCurrencyRule implements TransferRule {

    @Override
    public void validate(TransferContext context) {
        String fromCurrency = context.fromAccount().getCurrency();
        String toCurrency = context.toAccount().getCurrency();
        String transferCurrency = context.amount().currency().getCurrencyCode();

        if (!fromCurrency.equals(toCurrency)) {
            throw new CurrencyMismatchException(Currency.getInstance(fromCurrency), Currency.getInstance(toCurrency));
        }
        if (!fromCurrency.equals(transferCurrency)) {
            throw new CurrencyMismatchException(Currency.getInstance(fromCurrency), context.amount().currency());
        }
    }
}
