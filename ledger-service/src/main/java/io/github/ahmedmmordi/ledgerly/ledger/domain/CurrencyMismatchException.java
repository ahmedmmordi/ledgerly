package io.github.ahmedmmordi.ledgerly.ledger.domain;

import java.util.Currency;

public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: expected %s but was %s".formatted(expected.getCurrencyCode(),
                actual.getCurrencyCode()));
    }

    public CurrencyMismatchException(String message) {
        super(message);
    }
}
