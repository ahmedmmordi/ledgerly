package io.github.ahmedmmordi.ledgerly.statement.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.ahmedmmordi.ledgerly.statement.statement.dto.StatementEntryResponse;
import io.github.ahmedmmordi.ledgerly.statement.statement.dto.StatementResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@NullMarked
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementService Unit Tests")
@SuppressWarnings({ "null", "ConstantConditions" })
class StatementServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final YearMonth FROM = YearMonth.of(2026, 1);
    private static final YearMonth TO = YearMonth.of(2026, 3);
    private static final Pageable PAGEABLE = PageRequest.of(0, 10);
    private static final String CURRENCY_USD = "USD";
    private static final Instant TIMESTAMP_1 = Instant.parse("2026-01-10T10:00:00Z");
    private static final Instant TIMESTAMP_2 = Instant.parse("2026-02-15T12:30:00Z");

    @Mock
    private StatementRepository statementRepository;

    private StatementService statementService;

    @BeforeEach
    void setUp() {
        statementService = new StatementService(statementRepository);
    }

    private StatementEntry createEntry(StatementEntryType type, long amountMinorUnits, long balanceAfterMinorUnits,
            Instant occurredAt) {
        return new StatementEntry(UUID.randomUUID(), UUID.randomUUID(), type, amountMinorUnits, balanceAfterMinorUnits,
                occurredAt);
    }

    @Test
    @DisplayName("getStatements() with valid date range returns mapped paged responses with exact BigDecimal balances")
    void getStatements_validRange_returnsMappedPagedResponses() {
        Statement statementJan = Statement.create(ACCOUNT_ID, "2026-01", CURRENCY_USD, 100_000L);
        StatementEntry janEntry = createEntry(StatementEntryType.CREDIT, 50_000L, 150_000L, TIMESTAMP_1);
        statementJan.addEntry(janEntry);

        Statement statementFeb = Statement.create(ACCOUNT_ID, "2026-02", CURRENCY_USD, 150_000L);
        StatementEntry febEntry = createEntry(StatementEntryType.DEBIT, 20_000L, 130_000L, TIMESTAMP_2);
        statementFeb.addEntry(febEntry);

        Page<Statement> repoPage = new PageImpl<>(List.of(statementFeb, statementJan), PAGEABLE, 2);
        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(ACCOUNT_ID, "2026-01",
                "2026-03", PAGEABLE)).thenReturn(repoPage);

        Page<StatementResponse> result = statementService.getStatements(ACCOUNT_ID, FROM, TO, PAGEABLE);

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(2);

        StatementResponse first = result.getContent().getFirst();
        assertThat(first.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(first.yearMonth()).isEqualTo("2026-02");
        assertThat(first.currency()).isEqualTo(CURRENCY_USD);
        assertThat(first.openingBalanceMajorUnits()).isEqualTo(BigDecimal.valueOf(150_000L, 2));
        assertThat(first.openingBalanceMajorUnits().scale()).isEqualTo(2);
        assertThat(first.closingBalanceMajorUnits()).isEqualTo(BigDecimal.valueOf(130_000L, 2));
        assertThat(first.entries()).hasSize(1);
        StatementEntryResponse febEntryResponse = first.entries().getFirst();
        assertThat(febEntryResponse.type()).isEqualTo(StatementEntryType.DEBIT);
        assertThat(febEntryResponse.amountMajorUnits()).isEqualTo(BigDecimal.valueOf(20_000L, 2));
        assertThat(febEntryResponse.balanceAfterMajorUnits()).isEqualTo(BigDecimal.valueOf(130_000L, 2));
        assertThat(febEntryResponse.occurredAt()).isEqualTo(TIMESTAMP_2);

        StatementResponse second = result.getContent().get(1);
        assertThat(second.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(second.yearMonth()).isEqualTo("2026-01");
        assertThat(second.currency()).isEqualTo(CURRENCY_USD);
        assertThat(second.openingBalanceMajorUnits()).isEqualTo(BigDecimal.valueOf(100_000L, 2));
        assertThat(second.openingBalanceMajorUnits().scale()).isEqualTo(2);
        assertThat(second.closingBalanceMajorUnits()).isEqualTo(BigDecimal.valueOf(150_000L, 2));
        assertThat(second.entries()).hasSize(1);
        StatementEntryResponse janEntryResponse = second.entries().getFirst();
        assertThat(janEntryResponse.type()).isEqualTo(StatementEntryType.CREDIT);
        assertThat(janEntryResponse.amountMajorUnits()).isEqualTo(BigDecimal.valueOf(50_000L, 2));
        assertThat(janEntryResponse.balanceAfterMajorUnits()).isEqualTo(BigDecimal.valueOf(150_000L, 2));
        assertThat(janEntryResponse.occurredAt()).isEqualTo(TIMESTAMP_1);

        verify(statementRepository).findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(ACCOUNT_ID, "2026-01",
                "2026-03", PAGEABLE);
    }

    @Test
    @DisplayName("getStatements() returns empty page when no statements exist in requested date range")
    void getStatements_noStatementsInRange_returnsEmptyPage() {
        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(ACCOUNT_ID, "2026-01",
                "2026-03", PAGEABLE)).thenReturn(Page.empty(PAGEABLE));

        Page<StatementResponse> result = statementService.getStatements(ACCOUNT_ID, FROM, TO, PAGEABLE);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.getTotalElements()).isZero();
        verify(statementRepository).findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(ACCOUNT_ID, "2026-01",
                "2026-03", PAGEABLE);
    }

    @Test
    @DisplayName("getStatements() throws IllegalArgumentException when from date is after to date")
    void getStatements_fromAfterTo_throwsIllegalArgumentException() {
        YearMonth invalidFrom = YearMonth.of(2026, 12);
        YearMonth invalidTo = YearMonth.of(2026, 1);

        assertThatThrownBy(() -> statementService.getStatements(ACCOUNT_ID, invalidFrom, invalidTo, PAGEABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("From date (2026-12) must not be after to date (2026-01)");

        verify(statementRepository, never()).findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("getStatements() throws NullPointerException when arguments are null")
    void getStatements_nullArguments_throwNullPointerException() {
        assertThatThrownBy(() -> statementService.getStatements(null, FROM, TO, PAGEABLE))
                .isInstanceOf(NullPointerException.class).hasMessage("accountId must not be null");

        assertThatThrownBy(() -> statementService.getStatements(ACCOUNT_ID, null, TO, PAGEABLE))
                .isInstanceOf(NullPointerException.class).hasMessage("from must not be null");

        assertThatThrownBy(() -> statementService.getStatements(ACCOUNT_ID, FROM, null, PAGEABLE))
                .isInstanceOf(NullPointerException.class).hasMessage("to must not be null");

        assertThatThrownBy(() -> statementService.getStatements(ACCOUNT_ID, FROM, TO, null))
                .isInstanceOf(NullPointerException.class).hasMessage("pageable must not be null");
    }

    @Test
    @DisplayName("streamCsvEntries() writes standard header and chronological entries across multiple months")
    void streamCsvEntries_validRange_writesHeaderAndChronologicalRows() throws IOException {
        Statement statementJan = Statement.create(ACCOUNT_ID, "2026-01", CURRENCY_USD, 100_000L);
        StatementEntry janEntry = createEntry(StatementEntryType.DEBIT, 25_000L, 75_000L, TIMESTAMP_1);
        statementJan.addEntry(janEntry);

        Statement statementFeb = Statement.create(ACCOUNT_ID, "2026-02", CURRENCY_USD, 75_000L);
        StatementEntry febEntry = createEntry(StatementEntryType.CREDIT, 50_000L, 125_000L, TIMESTAMP_2);
        statementFeb.addEntry(febEntry);

        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(ACCOUNT_ID, "2026-01",
                "2026-03")).thenReturn(List.of(statementJan, statementFeb));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        statementService.streamCsvEntries(ACCOUNT_ID, FROM, TO, out);

        String csvOutput = out.toString(StandardCharsets.UTF_8);
        String expectedCsv = """
                date,type,amount,currency,balanceAfter
                2026-01-10T10:00:00Z,DEBIT,250.00,USD,750.00
                2026-02-15T12:30:00Z,CREDIT,500.00,USD,1250.00
                """;

        assertThat(csvOutput).isEqualTo(expectedCsv);
        verify(statementRepository).findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(ACCOUNT_ID, "2026-01",
                "2026-03");
    }

    @Test
    @DisplayName("streamCsvEntries() writes only the CSV header when no statements exist in range")
    void streamCsvEntries_noStatementsInRange_writesOnlyCsvHeader() throws IOException {
        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(ACCOUNT_ID, "2026-01",
                "2026-03")).thenReturn(List.of());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        statementService.streamCsvEntries(ACCOUNT_ID, FROM, TO, out);

        String csvOutput = out.toString(StandardCharsets.UTF_8);
        assertThat(csvOutput).isEqualTo("date,type,amount,currency,balanceAfter\n");
    }

    @Test
    @DisplayName("streamCsvEntries() writes only the CSV header when statement has no entries")
    void streamCsvEntries_statementWithNoEntries_writesOnlyCsvHeader() throws IOException {
        Statement emptyStatement = Statement.create(ACCOUNT_ID, "2026-01", CURRENCY_USD, 100_000L);
        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(ACCOUNT_ID, "2026-01",
                "2026-03")).thenReturn(List.of(emptyStatement));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        statementService.streamCsvEntries(ACCOUNT_ID, FROM, TO, out);

        String csvOutput = out.toString(StandardCharsets.UTF_8);
        assertThat(csvOutput).isEqualTo("date,type,amount,currency,balanceAfter\n");
    }

    @Test
    @DisplayName("streamCsvEntries() throws IllegalArgumentException when from date is after to date")
    void streamCsvEntries_fromAfterTo_throwsIllegalArgumentException() {
        YearMonth invalidFrom = YearMonth.of(2026, 12);
        YearMonth invalidTo = YearMonth.of(2026, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatThrownBy(() -> statementService.streamCsvEntries(ACCOUNT_ID, invalidFrom, invalidTo, out))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("From date (2026-12) must not be after to date (2026-01)");

        verify(statementRepository, never()).findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(any(), any(), any());
    }

    @Test
    @DisplayName("streamCsvEntries() throws NullPointerException when arguments are null")
    void streamCsvEntries_nullArguments_throwNullPointerException() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatThrownBy(() -> statementService.streamCsvEntries(null, FROM, TO, out))
                .isInstanceOf(NullPointerException.class).hasMessage("accountId must not be null");

        assertThatThrownBy(() -> statementService.streamCsvEntries(ACCOUNT_ID, null, TO, out))
                .isInstanceOf(NullPointerException.class).hasMessage("from must not be null");

        assertThatThrownBy(() -> statementService.streamCsvEntries(ACCOUNT_ID, FROM, null, out))
                .isInstanceOf(NullPointerException.class).hasMessage("to must not be null");

        assertThatThrownBy(() -> statementService.streamCsvEntries(ACCOUNT_ID, FROM, TO, null))
                .isInstanceOf(NullPointerException.class).hasMessage("outputStream must not be null");
    }

    @Test
    @DisplayName("getStatements() with same from and to month queries single month boundary")
    void getStatements_singleMonthRange_succeeds() {
        YearMonth singleMonth = YearMonth.of(2026, 1);
        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(ACCOUNT_ID, "2026-01",
                "2026-01", PAGEABLE)).thenReturn(Page.empty(PAGEABLE));

        Page<StatementResponse> result = statementService.getStatements(ACCOUNT_ID, singleMonth, singleMonth, PAGEABLE);

        assertThat(result.isEmpty()).isTrue();
        verify(statementRepository).findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(ACCOUNT_ID, "2026-01",
                "2026-01", PAGEABLE);
    }

    @Test
    @DisplayName("getStatements() with zero-fraction currency (JPY) formats major units with scale 0")
    void getStatements_currencyWithZeroFractionDigits_correctlyFormatsScaleAndValues() {
        Statement statementJpy = Statement.create(ACCOUNT_ID, "2026-01", "JPY", 10_000L);
        StatementEntry entry = createEntry(StatementEntryType.CREDIT, 5_000L, 15_000L, TIMESTAMP_1);
        statementJpy.addEntry(entry);

        Page<Statement> repoPage = new PageImpl<>(List.of(statementJpy), PAGEABLE, 1);
        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthDesc(ACCOUNT_ID, "2026-01",
                "2026-01", PAGEABLE)).thenReturn(repoPage);

        YearMonth jan = YearMonth.of(2026, 1);
        Page<StatementResponse> result = statementService.getStatements(ACCOUNT_ID, jan, jan, PAGEABLE);

        StatementResponse response = result.getContent().getFirst();
        assertThat(response.currency()).isEqualTo("JPY");
        assertThat(response.openingBalanceMajorUnits()).isEqualTo(BigDecimal.valueOf(10_000L, 0));
        assertThat(response.openingBalanceMajorUnits().scale()).isZero();
        assertThat(response.closingBalanceMajorUnits()).isEqualTo(BigDecimal.valueOf(15_000L, 0));
        assertThat(response.closingBalanceMajorUnits().scale()).isZero();
        assertThat(response.entries().getFirst().amountMajorUnits()).isEqualTo(BigDecimal.valueOf(5_000L, 0));
        assertThat(response.entries().getFirst().amountMajorUnits().scale()).isZero();
    }

    @Test
    @DisplayName("streamCsvEntries() with multiple entries in single month writes all in chronological sequence")
    void streamCsvEntries_multipleEntriesInSingleStatement_streamsAllEntriesChronologically() throws IOException {
        Statement statementJan = Statement.create(ACCOUNT_ID, "2026-01", CURRENCY_USD, 100_000L);
        StatementEntry janEntry1 = createEntry(StatementEntryType.DEBIT, 25_000L, 75_000L, TIMESTAMP_1);
        StatementEntry janEntry2 = createEntry(StatementEntryType.CREDIT, 10_000L, 85_000L, TIMESTAMP_2);
        statementJan.addEntry(janEntry1);
        statementJan.addEntry(janEntry2);

        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(ACCOUNT_ID, "2026-01",
                "2026-01")).thenReturn(List.of(statementJan));

        YearMonth jan = YearMonth.of(2026, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        statementService.streamCsvEntries(ACCOUNT_ID, jan, jan, out);

        String csvOutput = out.toString(StandardCharsets.UTF_8);
        String expectedCsv = """
                date,type,amount,currency,balanceAfter
                2026-01-10T10:00:00Z,DEBIT,250.00,USD,750.00
                2026-02-15T12:30:00Z,CREDIT,100.00,USD,850.00
                """;

        assertThat(csvOutput).isEqualTo(expectedCsv);
    }

    @Test
    @DisplayName("streamCsvEntries() with zero-fraction currency (JPY) outputs integer numbers without decimals")
    void streamCsvEntries_currencyWithZeroFractionDigits_formatsWithoutDecimals() throws IOException {
        Statement statementJpy = Statement.create(ACCOUNT_ID, "2026-01", "JPY", 10_000L);
        StatementEntry entry = createEntry(StatementEntryType.CREDIT, 5_000L, 15_000L, TIMESTAMP_1);
        statementJpy.addEntry(entry);

        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(ACCOUNT_ID, "2026-01",
                "2026-01")).thenReturn(List.of(statementJpy));

        YearMonth jan = YearMonth.of(2026, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        statementService.streamCsvEntries(ACCOUNT_ID, jan, jan, out);

        String csvOutput = out.toString(StandardCharsets.UTF_8);
        String expectedCsv = """
                date,type,amount,currency,balanceAfter
                2026-01-10T10:00:00Z,CREDIT,5000,JPY,15000
                """;

        assertThat(csvOutput).isEqualTo(expectedCsv);
    }

    @Test
    @DisplayName("streamCsvEntries() falls back to 2 fraction digits when currency code is unrecognized")
    void streamCsvEntries_unrecognizedCurrencyCode_fallsBackToTwoFractionDigits() throws IOException {
        Statement statement = new Statement(null, ACCOUNT_ID, "2026-01", "XYZ", 100_000L, 100_000L, null, TIMESTAMP_1,
                TIMESTAMP_1, null);
        StatementEntry entry = createEntry(StatementEntryType.CREDIT, 50_000L, 150_000L, TIMESTAMP_1);
        statement.addEntry(entry);

        when(statementRepository.findByAccountIdAndYearMonthBetweenOrderByYearMonthAsc(ACCOUNT_ID, "2026-01",
                "2026-01")).thenReturn(List.of(statement));

        YearMonth jan = YearMonth.of(2026, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        statementService.streamCsvEntries(ACCOUNT_ID, jan, jan, out);

        String csvOutput = out.toString(StandardCharsets.UTF_8);
        String expectedCsv = """
                date,type,amount,currency,balanceAfter
                2026-01-10T10:00:00Z,CREDIT,500.00,XYZ,1500.00
                """;

        assertThat(csvOutput).isEqualTo(expectedCsv);
    }
}
