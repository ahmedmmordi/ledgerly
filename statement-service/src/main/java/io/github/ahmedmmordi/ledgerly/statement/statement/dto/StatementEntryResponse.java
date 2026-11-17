package io.github.ahmedmmordi.ledgerly.statement.statement.dto;

import io.github.ahmedmmordi.ledgerly.statement.statement.StatementEntry;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record StatementEntryResponse(StatementEntryType type, BigDecimal amountMajorUnits,
        BigDecimal balanceAfterMajorUnits, Instant occurredAt) {

    private static final int DEFAULT_FRACTION_DIGITS = 2;

    public StatementEntryResponse {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(amountMajorUnits, "amountMajorUnits must not be null");
        Objects.requireNonNull(balanceAfterMajorUnits, "balanceAfterMajorUnits must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public static StatementEntryResponse from(StatementEntry entry, String currencyCode) {
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        int fractionDigits = resolveFractionDigits(currencyCode);
        BigDecimal amount = BigDecimal.valueOf(entry.amountMinorUnits(), fractionDigits);
        BigDecimal balanceAfter = BigDecimal.valueOf(entry.balanceAfterMinorUnits(), fractionDigits);
        return new StatementEntryResponse(entry.type(), amount, balanceAfter, entry.occurredAt());
    }

    private static int resolveFractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode.trim().toUpperCase()).getDefaultFractionDigits();
            return digits >= 0 ? digits : DEFAULT_FRACTION_DIGITS;
        } catch (IllegalArgumentException e) {
            return DEFAULT_FRACTION_DIGITS;
        }
    }
}
