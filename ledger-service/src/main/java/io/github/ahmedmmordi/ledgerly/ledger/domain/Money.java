package io.github.ahmedmmordi.ledgerly.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary value object stored in discrete minor units (e.g. cents, fils).
 */
public record Money(long amountInMinorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "Currency must not be null");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency %s does not support minor units (fraction digits: %d)"
                    .formatted(currency.getCurrencyCode(), currency.getDefaultFractionDigits()));
        }
    }

    public long amountInCents() {
        return amountInMinorUnits;
    }

    public static Money ofMinor(long amountInMinorUnits, Currency currency) {
        return new Money(amountInMinorUnits, currency);
    }

    public static Money ofMinor(long amountInMinorUnits, String currencyCode) {
        return new Money(amountInMinorUnits, parseCurrency(currencyCode));
    }

    public static Money ofCents(long amountInCents, Currency currency) {
        return ofMinor(amountInCents, currency);
    }

    public static Money ofCents(long amountInCents, String currencyCode) {
        return ofMinor(amountInCents, currencyCode);
    }

    /**
     * Creates a Money instance from BigDecimal, requiring exact decimal precision.
     */
    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "Amount must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits < 0) {
            throw new IllegalArgumentException(
                    "Currency %s does not support minor units".formatted(currency.getCurrencyCode()));
        }
        BigDecimal scaled = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
        long minorUnits = scaled.movePointRight(fractionDigits).longValueExact();
        return new Money(minorUnits, currency);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return of(amount, parseCurrency(currencyCode));
    }

    public static Money zero(Currency currency) {
        return ofMinor(0L, currency);
    }

    public static Money zero(String currencyCode) {
        return ofMinor(0L, currencyCode);
    }

    /**
     * Converts minor units to a scaled BigDecimal matching the currency's fraction digits.
     */
    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(amountInMinorUnits, currency.getDefaultFractionDigits());
    }

    public Money plus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.addExact(this.amountInMinorUnits, other.amountInMinorUnits), this.currency);
    }

    public Money minus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.subtractExact(this.amountInMinorUnits, other.amountInMinorUnits), this.currency);
    }

    public Money multipliedBy(long factor) {
        return new Money(Math.multiplyExact(this.amountInMinorUnits, factor), this.currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(this.amountInMinorUnits), this.currency);
    }

    public Money abs() {
        return this.amountInMinorUnits >= 0L ? this : negated();
    }

    public static Money parse(String text) {
        Objects.requireNonNull(text, "Text must not be null");
        String trimmed = text.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace <= 0 || lastSpace >= trimmed.length() - 1) {
            throw new IllegalArgumentException(
                    "Invalid money format: '%s'. Expected format: '<amount> <currency>', e.g. '10.50 USD'"
                            .formatted(text));
        }
        String amountPart = trimmed.substring(0, lastSpace).trim();
        String currencyPart = trimmed.substring(lastSpace + 1).trim();
        return Money.of(new BigDecimal(amountPart), currencyPart);
    }

    @Override
    public String toString() {
        return "%s %s".formatted(toBigDecimal().toPlainString(), currency.getCurrencyCode());
    }

    public boolean isPositive() {
        return this.amountInMinorUnits > 0L;
    }

    public boolean isZeroOrPositive() {
        return this.amountInMinorUnits >= 0L;
    }

    public boolean isZero() {
        return this.amountInMinorUnits == 0L;
    }

    public boolean isNegative() {
        return this.amountInMinorUnits < 0L;
    }

    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return Long.compare(this.amountInMinorUnits, other.amountInMinorUnits);
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public boolean isLessThanOrEqualTo(Money other) {
        return compareTo(other) <= 0;
    }

    private void assertSameCurrency(Money other) {
        Objects.requireNonNull(other, "Money operand must not be null");
        if (!this.currency.equals(other.currency)) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    private static Currency parseCurrency(String currencyCode) {
        Objects.requireNonNull(currencyCode, "Currency code must not be null");
        String trimmed = currencyCode.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Currency code must not be blank");
        }
        return Currency.getInstance(trimmed.toUpperCase());
    }
}
