package io.github.ahmedmmordi.ledgerly.statement.statement.dto;

import io.github.ahmedmmordi.ledgerly.statement.statement.Statement;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record StatementResponse(UUID accountId, String yearMonth, String currency, BigDecimal openingBalanceMajorUnits,
        BigDecimal closingBalanceMajorUnits, List<StatementEntryResponse> entries) {

    private static final int DEFAULT_FRACTION_DIGITS = 2;

    public StatementResponse {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(yearMonth, "yearMonth must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(openingBalanceMajorUnits, "openingBalanceMajorUnits must not be null");
        Objects.requireNonNull(closingBalanceMajorUnits, "closingBalanceMajorUnits must not be null");
        Objects.requireNonNull(entries, "entries must not be null");
        entries = List.copyOf(entries);
    }

    public static StatementResponse from(Statement statement) {
        Objects.requireNonNull(statement, "statement must not be null");
        int fractionDigits = resolveFractionDigits(statement.getCurrency());

        BigDecimal opening = BigDecimal.valueOf(statement.getOpeningBalanceMinorUnits(), fractionDigits);
        BigDecimal closing = BigDecimal.valueOf(statement.getClosingBalanceMinorUnits(), fractionDigits);
        List<StatementEntryResponse> entryResponses = statement.getEntries().stream()
                .map(entry -> StatementEntryResponse.from(entry, statement.getCurrency())).toList();

        return new StatementResponse(statement.getAccountId(), statement.getYearMonth(), statement.getCurrency(),
                opening, closing, entryResponses);
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
