package io.github.ahmedmmordi.ledgerly.statement.statement;

import io.github.ahmedmmordi.ledgerly.statement.statement.dto.StatementResponse;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@NullMarked
public class StatementService {

    private static final int DEFAULT_FRACTION_DIGITS = 2;
    private static final String CSV_HEADER = "date,type,amount,currency,balanceAfter\n";

    private final StatementRepository statementRepository;

    public Page<StatementResponse> getStatements(UUID accountId, YearMonth from, YearMonth to, Pageable pageable) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(pageable, "pageable must not be null");

        validateDateRange(from, to);

        log.debug("Fetching statements for account {} between {} and {}", accountId, from, to);
        return statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(accountId, from.toString(),
                to.toString(), pageable).map(StatementResponse::from);
    }

    public void streamCsvEntries(UUID accountId, YearMonth from, YearMonth to, OutputStream outputStream)
            throws IOException {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(outputStream, "outputStream must not be null");

        validateDateRange(from, to);

        log.debug("Streaming statements CSV for account {} between {} and {}", accountId, from, to);
        List<Statement> statements = statementRepository
                .findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(accountId, from.toString(), to.toString());

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.write(CSV_HEADER);

        for (Statement statement : statements) {
            int fractionDigits = resolveFractionDigits(statement.getCurrency());
            for (StatementEntry entry : statement.getEntries()) {
                BigDecimal amount = BigDecimal.valueOf(entry.amountMinorUnits(), fractionDigits);
                BigDecimal balanceAfter = BigDecimal.valueOf(entry.balanceAfterMinorUnits(), fractionDigits);
                writer.write(entry.occurredAt().toString());
                writer.write(',');
                writer.write(entry.type().name());
                writer.write(',');
                writer.write(amount.toPlainString());
                writer.write(',');
                writer.write(statement.getCurrency());
                writer.write(',');
                writer.write(balanceAfter.toPlainString());
                writer.write('\n');
            }
        }

        writer.flush();
    }

    private void validateDateRange(YearMonth from, YearMonth to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("From date (%s) must not be after to date (%s)".formatted(from, to));
        }
    }

    private int resolveFractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            return digits >= 0 ? digits : DEFAULT_FRACTION_DIGITS;
        } catch (IllegalArgumentException e) {
            return DEFAULT_FRACTION_DIGITS;
        }
    }
}
