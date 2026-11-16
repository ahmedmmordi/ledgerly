package io.github.ahmedmmordi.ledgerly.statement.statement;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

// Monthly statement document for an account with running balance and entries
@NullMarked
@Getter
@ToString(exclude = "entries")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Document(collection = "statements")
@CompoundIndex(name = "idx_account_year_month", def = "{'accountId': 1, 'yearMonth': 1}", unique = true)
public class Statement {

    @Id
    private @Nullable String id;

    @EqualsAndHashCode.Include
    private final UUID accountId;

    @EqualsAndHashCode.Include
    private final String yearMonth;

    private final String currency;

    private final long openingBalanceMinorUnits;

    private long closingBalanceMinorUnits;

    @Getter(AccessLevel.NONE)
    private final List<StatementEntry> entries;

    @CreatedDate
    private final Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Version
    private @Nullable Long version;

    @PersistenceCreator
    Statement(@Nullable String id, UUID accountId, String yearMonth, String currency, long openingBalanceMinorUnits,
            long closingBalanceMinorUnits, @Nullable List<StatementEntry> entries, Instant createdAt, Instant updatedAt,
            @Nullable Long version) {
        this.id = id;
        this.accountId = accountId;
        this.yearMonth = yearMonth;
        this.currency = currency;
        this.openingBalanceMinorUnits = openingBalanceMinorUnits;
        this.closingBalanceMinorUnits = closingBalanceMinorUnits;
        this.entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Statement create(UUID accountId, String yearMonth, String currency, long openingBalanceMinorUnits) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(yearMonth, "yearMonth must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        validateYearMonth(yearMonth);
        validateCurrency(currency);

        Instant now = Instant.now();
        return new Statement(null, accountId, yearMonth, currency.trim().toUpperCase(), openingBalanceMinorUnits,
                openingBalanceMinorUnits, new ArrayList<>(), now, now, null);
    }

    private static void validateYearMonth(String yearMonth) {
        try {
            YearMonth.parse(yearMonth);
        } catch (Exception e) {
            throw new IllegalArgumentException("yearMonth must follow YYYY-MM format, was: " + yearMonth, e);
        }
    }

    private static void validateCurrency(String currency) {
        String trimmed = currency.trim().toUpperCase();
        try {
            Currency.getInstance(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("currency must be a valid 3-letter ISO-4217 code, was: " + currency, e);
        }
    }

    public boolean containsEvent(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        for (StatementEntry entry : entries) {
            if (entry.eventId().equals(eventId)) {
                return true;
            }
        }
        return false;
    }

    public boolean addEntry(StatementEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (containsEvent(entry.eventId())) {
            return false;
        }
        this.entries.add(entry);
        this.closingBalanceMinorUnits = entry.balanceAfterMinorUnits();
        this.updatedAt = Instant.now();
        return true;
    }

    public long getTotalCreditMinorUnits() {
        return entries.stream().filter(e -> e.type() == StatementEntryType.CREDIT)
                .mapToLong(StatementEntry::amountMinorUnits).sum();
    }

    public long getTotalDebitMinorUnits() {
        return entries.stream().filter(e -> e.type() == StatementEntryType.DEBIT)
                .mapToLong(StatementEntry::amountMinorUnits).sum();
    }

    public long getNetChangeMinorUnits() {
        return closingBalanceMinorUnits - openingBalanceMinorUnits;
    }

    public List<StatementEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
