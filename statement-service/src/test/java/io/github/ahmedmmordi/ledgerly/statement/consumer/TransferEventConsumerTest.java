package io.github.ahmedmmordi.ledgerly.statement.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.ahmedmmordi.ledgerly.common.event.TransferCompletedEvent;
import io.github.ahmedmmordi.ledgerly.statement.statement.Statement;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementEntry;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementEntryType;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

@NullMarked
@ExtendWith(MockitoExtension.class)
@DisplayName("TransferEventConsumer Unit Tests")
@SuppressWarnings({ "null", "ConstantConditions" })
class TransferEventConsumerTest {

    private static final UUID CASH_VAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String CURRENCY = "USD";

    @Mock
    private StatementRepository statementRepository;

    @Mock
    private Acknowledgment acknowledgment;

    @Captor
    private ArgumentCaptor<Statement> statementCaptor;

    private TransferEventConsumer consumer;

    private final UUID fromAccountId = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private final UUID toAccountId = UUID.fromString("0193297a-9a90-7888-8123-000000000002");
    private final Instant occurredAt = Instant.parse("2026-11-17T10:00:00Z");

    @BeforeEach
    void setUp() {
        consumer = new TransferEventConsumer(statementRepository);
    }

    private TransferCompletedEvent createEvent(UUID fromId, UUID toId, long amountMinorUnits) {
        return new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), fromId, toId, amountMinorUnits,
                CURRENCY, occurredAt, 1);
    }

    @Test
    @DisplayName("consume() normal transfer records DEBIT on sender and CREDIT on recipient, then acknowledges offset")
    void consume_normalTransfer_recordsDebitAndCreditAndAcknowledgesOffset() {
        TransferCompletedEvent event = createEvent(fromAccountId, toAccountId, 100_000L);
        Statement fromStatement = Statement.create(fromAccountId, "2026-11", CURRENCY, 500_000L);
        Statement toStatement = Statement.create(toAccountId, "2026-11", CURRENCY, 200_000L);

        when(statementRepository.findByAccountIdAndYearMonth(fromAccountId, "2026-11"))
                .thenReturn(Optional.of(fromStatement));
        when(statementRepository.findByAccountIdAndYearMonth(toAccountId, "2026-11"))
                .thenReturn(Optional.of(toStatement));

        consumer.consume(event, acknowledgment);

        verify(statementRepository, times(2)).save(statementCaptor.capture());
        List<Statement> savedStatements = statementCaptor.getAllValues();

        Statement savedFrom = savedStatements.getFirst();
        assertThat(savedFrom.getAccountId()).isEqualTo(fromAccountId);
        assertThat(savedFrom.getClosingBalanceMinorUnits()).isEqualTo(400_000L);
        assertThat(savedFrom.getEntries()).hasSize(1);
        StatementEntry debitEntry = savedFrom.getEntries().getFirst();
        assertThat(debitEntry.type()).isEqualTo(StatementEntryType.DEBIT);
        assertThat(debitEntry.amountMinorUnits()).isEqualTo(100_000L);
        assertThat(debitEntry.balanceAfterMinorUnits()).isEqualTo(400_000L);
        assertThat(debitEntry.eventId()).isEqualTo(event.eventId());
        assertThat(debitEntry.transferId()).isEqualTo(event.transferId());
        assertThat(debitEntry.occurredAt()).isEqualTo(occurredAt);

        Statement savedTo = savedStatements.getLast();
        assertThat(savedTo.getAccountId()).isEqualTo(toAccountId);
        assertThat(savedTo.getClosingBalanceMinorUnits()).isEqualTo(300_000L);
        assertThat(savedTo.getEntries()).hasSize(1);
        StatementEntry creditEntry = savedTo.getEntries().getFirst();
        assertThat(creditEntry.type()).isEqualTo(StatementEntryType.CREDIT);
        assertThat(creditEntry.amountMinorUnits()).isEqualTo(100_000L);
        assertThat(creditEntry.balanceAfterMinorUnits()).isEqualTo(300_000L);
        assertThat(creditEntry.eventId()).isEqualTo(event.eventId());
        assertThat(creditEntry.transferId()).isEqualTo(event.transferId());
        assertThat(creditEntry.occurredAt()).isEqualTo(occurredAt);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consume() cash deposit from system vault ignores vault account and records CREDIT only on customer")
    void consume_cashDepositFromVault_recordsCreditOnlyAndSkipsVaultDebit() {
        TransferCompletedEvent depositEvent = createEvent(CASH_VAULT_ID, toAccountId, 250_000L);
        Statement customerStatement = Statement.create(toAccountId, "2026-11", CURRENCY, 100_000L);

        when(statementRepository.findByAccountIdAndYearMonth(toAccountId, "2026-11"))
                .thenReturn(Optional.of(customerStatement));

        consumer.consume(depositEvent, acknowledgment);

        verify(statementRepository, never()).findByAccountIdAndYearMonth(CASH_VAULT_ID, "2026-11");
        verify(statementRepository, times(1)).save(statementCaptor.capture());

        Statement saved = statementCaptor.getValue();
        assertThat(saved.getAccountId()).isEqualTo(toAccountId);
        assertThat(saved.getClosingBalanceMinorUnits()).isEqualTo(350_000L);
        assertThat(saved.getEntries()).hasSize(1);
        StatementEntry entry = saved.getEntries().getFirst();
        assertThat(entry.type()).isEqualTo(StatementEntryType.CREDIT);
        assertThat(entry.amountMinorUnits()).isEqualTo(250_000L);
        assertThat(entry.balanceAfterMinorUnits()).isEqualTo(350_000L);
        assertThat(entry.eventId()).isEqualTo(depositEvent.eventId());
        assertThat(entry.transferId()).isEqualTo(depositEvent.transferId());
        assertThat(entry.occurredAt()).isEqualTo(occurredAt);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consume() duplicate eventId is skipped without mutating balances or saving, but acknowledges offset")
    void consume_duplicateEventId_skipsProcessingAndAcknowledgesOffset() {
        TransferCompletedEvent event = createEvent(fromAccountId, toAccountId, 50_000L);

        Statement fromStatement = Statement.create(fromAccountId, "2026-11", CURRENCY, 300_000L);
        StatementEntry existingDebit = new StatementEntry(event.eventId(), event.transferId(), StatementEntryType.DEBIT,
                50_000L, 250_000L, occurredAt);
        fromStatement.addEntry(existingDebit);

        Statement toStatement = Statement.create(toAccountId, "2026-11", CURRENCY, 100_000L);
        StatementEntry existingCredit = new StatementEntry(event.eventId(), event.transferId(),
                StatementEntryType.CREDIT, 50_000L, 150_000L, occurredAt);
        toStatement.addEntry(existingCredit);

        when(statementRepository.findByAccountIdAndYearMonth(fromAccountId, "2026-11"))
                .thenReturn(Optional.of(fromStatement));
        when(statementRepository.findByAccountIdAndYearMonth(toAccountId, "2026-11"))
                .thenReturn(Optional.of(toStatement));

        consumer.consume(event, acknowledgment);

        verify(statementRepository, never()).save(any(Statement.class));
        assertThat(fromStatement.getClosingBalanceMinorUnits()).isEqualTo(250_000L);
        assertThat(fromStatement.getEntries()).hasSize(1);
        assertThat(toStatement.getClosingBalanceMinorUnits()).isEqualTo(150_000L);
        assertThat(toStatement.getEntries()).hasSize(1);

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consume() rollover carries forward prior month's closing balance as opening balance")
    void consume_monthRollover_carriesForwardPriorClosingBalance() {
        TransferCompletedEvent event = createEvent(CASH_VAULT_ID, toAccountId, 75_000L);
        Statement priorMonthStatement = Statement.create(toAccountId, "2026-10", CURRENCY, 400_000L);
        StatementEntry priorEntry = new StatementEntry(UUID.randomUUID(), UUID.randomUUID(), StatementEntryType.CREDIT,
                50_000L, 450_000L, occurredAt);
        priorMonthStatement.addEntry(priorEntry);

        when(statementRepository.findByAccountIdAndYearMonth(toAccountId, "2026-11")).thenReturn(Optional.empty());
        when(statementRepository.findFirstByAccountIdOrderByYearMonthDesc(toAccountId))
                .thenReturn(Optional.of(priorMonthStatement));

        consumer.consume(event, acknowledgment);

        verify(statementRepository).save(statementCaptor.capture());
        Statement created = statementCaptor.getValue();

        assertThat(created.getAccountId()).isEqualTo(toAccountId);
        assertThat(created.getYearMonth()).isEqualTo("2026-11");
        assertThat(created.getOpeningBalanceMinorUnits()).isEqualTo(450_000L);
        assertThat(created.getClosingBalanceMinorUnits()).isEqualTo(525_000L);
        assertThat(created.getEntries()).hasSize(1);
        StatementEntry entry = created.getEntries().getFirst();
        assertThat(entry.type()).isEqualTo(StatementEntryType.CREDIT);
        assertThat(entry.amountMinorUnits()).isEqualTo(75_000L);
        assertThat(entry.balanceAfterMinorUnits()).isEqualTo(525_000L);
        assertThat(entry.eventId()).isEqualTo(event.eventId());
        assertThat(entry.transferId()).isEqualTo(event.transferId());
        assertThat(entry.occurredAt()).isEqualTo(occurredAt);

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consume() first statement for an account initializes with zero opening balance")
    void consume_firstStatementForAccount_initializesOpeningBalanceToZero() {
        TransferCompletedEvent event = createEvent(CASH_VAULT_ID, toAccountId, 120_000L);

        when(statementRepository.findByAccountIdAndYearMonth(toAccountId, "2026-11")).thenReturn(Optional.empty());
        when(statementRepository.findFirstByAccountIdOrderByYearMonthDesc(toAccountId)).thenReturn(Optional.empty());

        consumer.consume(event, acknowledgment);

        verify(statementRepository).save(statementCaptor.capture());
        Statement created = statementCaptor.getValue();

        assertThat(created.getAccountId()).isEqualTo(toAccountId);
        assertThat(created.getYearMonth()).isEqualTo("2026-11");
        assertThat(created.getOpeningBalanceMinorUnits()).isZero();
        assertThat(created.getClosingBalanceMinorUnits()).isEqualTo(120_000L);
        assertThat(created.getEntries()).hasSize(1);
        StatementEntry entry = created.getEntries().getFirst();
        assertThat(entry.type()).isEqualTo(StatementEntryType.CREDIT);
        assertThat(entry.amountMinorUnits()).isEqualTo(120_000L);
        assertThat(entry.balanceAfterMinorUnits()).isEqualTo(120_000L);
        assertThat(entry.eventId()).isEqualTo(event.eventId());
        assertThat(entry.transferId()).isEqualTo(event.transferId());
        assertThat(entry.occurredAt()).isEqualTo(occurredAt);

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consume() across year-end rollover correctly carries forward closing balance")
    void consume_yearEndRollover_carriesForwardClosingBalance() {
        Instant newYearInstant = Instant.parse("2027-01-05T12:00:00Z");
        TransferCompletedEvent event = new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), CASH_VAULT_ID,
                toAccountId, 30_000L, CURRENCY, newYearInstant, 1);

        Statement decemberStatement = Statement.create(toAccountId, "2026-12", CURRENCY, 800_000L);

        when(statementRepository.findByAccountIdAndYearMonth(toAccountId, "2027-01")).thenReturn(Optional.empty());
        when(statementRepository.findFirstByAccountIdOrderByYearMonthDesc(toAccountId))
                .thenReturn(Optional.of(decemberStatement));

        consumer.consume(event, acknowledgment);

        verify(statementRepository).save(statementCaptor.capture());
        Statement created = statementCaptor.getValue();

        assertThat(created.getAccountId()).isEqualTo(toAccountId);
        assertThat(created.getYearMonth()).isEqualTo("2027-01");
        assertThat(created.getOpeningBalanceMinorUnits()).isEqualTo(800_000L);
        assertThat(created.getClosingBalanceMinorUnits()).isEqualTo(830_000L);
        assertThat(created.getEntries()).hasSize(1);
        StatementEntry entry = created.getEntries().getFirst();
        assertThat(entry.type()).isEqualTo(StatementEntryType.CREDIT);
        assertThat(entry.amountMinorUnits()).isEqualTo(30_000L);
        assertThat(entry.balanceAfterMinorUnits()).isEqualTo(830_000L);
        assertThat(entry.eventId()).isEqualTo(event.eventId());
        assertThat(entry.transferId()).isEqualTo(event.transferId());
        assertThat(entry.occurredAt()).isEqualTo(newYearInstant);

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consume() does not acknowledge offset if repository save fails")
    void consume_repositoryFailure_doesNotAcknowledgeOffset() {
        TransferCompletedEvent event = createEvent(CASH_VAULT_ID, toAccountId, 100_000L);
        Statement statement = Statement.create(toAccountId, "2026-11", CURRENCY, 500_000L);

        when(statementRepository.findByAccountIdAndYearMonth(toAccountId, "2026-11"))
                .thenReturn(Optional.of(statement));
        when(statementRepository.save(any(Statement.class))).thenThrow(new RuntimeException("MongoDB write failed"));

        assertThatThrownBy(() -> consumer.consume(event, acknowledgment)).isInstanceOf(RuntimeException.class)
                .hasMessage("MongoDB write failed");

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("consume() subsequent transfer in same month appends entry and updates closing balance from prior entries")
    void consume_subsequentTransferInSameMonth_accumulatesOnExistingEntriesAndClosingBalance() {
        TransferCompletedEvent firstEvent = createEvent(CASH_VAULT_ID, toAccountId, 100_000L);
        Statement existingStatement = Statement.create(toAccountId, "2026-11", CURRENCY, 200_000L);
        StatementEntry firstEntry = new StatementEntry(firstEvent.eventId(), firstEvent.transferId(),
                StatementEntryType.CREDIT, 100_000L, 300_000L, occurredAt);
        existingStatement.addEntry(firstEntry);

        TransferCompletedEvent secondEvent = createEvent(fromAccountId, toAccountId, 50_000L);
        Statement fromStatement = Statement.create(fromAccountId, "2026-11", CURRENCY, 400_000L);

        when(statementRepository.findByAccountIdAndYearMonth(fromAccountId, "2026-11"))
                .thenReturn(Optional.of(fromStatement));
        when(statementRepository.findByAccountIdAndYearMonth(toAccountId, "2026-11"))
                .thenReturn(Optional.of(existingStatement));

        consumer.consume(secondEvent, acknowledgment);

        verify(statementRepository, times(2)).save(statementCaptor.capture());
        List<Statement> saved = statementCaptor.getAllValues();

        Statement savedTo = saved.getLast();
        assertThat(savedTo.getAccountId()).isEqualTo(toAccountId);
        assertThat(savedTo.getOpeningBalanceMinorUnits()).isEqualTo(200_000L);
        assertThat(savedTo.getClosingBalanceMinorUnits()).isEqualTo(350_000L);
        assertThat(savedTo.getEntries()).hasSize(2);

        StatementEntry secondEntry = savedTo.getEntries().getLast();
        assertThat(secondEntry.type()).isEqualTo(StatementEntryType.CREDIT);
        assertThat(secondEntry.amountMinorUnits()).isEqualTo(50_000L);
        assertThat(secondEntry.balanceAfterMinorUnits()).isEqualTo(350_000L);
        assertThat(secondEntry.eventId()).isEqualTo(secondEvent.eventId());
        assertThat(secondEntry.transferId()).isEqualTo(secondEvent.transferId());
        assertThat(secondEntry.occurredAt()).isEqualTo(occurredAt);

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consume() skips only the account that already processed the eventId, continuing for the other")
    void consume_partiallyProcessedTransfer_skipsOnlyProcessedAccount() {
        TransferCompletedEvent event = createEvent(fromAccountId, toAccountId, 50_000L);

        Statement fromStatement = Statement.create(fromAccountId, "2026-11", CURRENCY, 300_000L);
        StatementEntry existingDebit = new StatementEntry(event.eventId(), event.transferId(), StatementEntryType.DEBIT,
                50_000L, 250_000L, occurredAt);
        fromStatement.addEntry(existingDebit);

        Statement toStatement = Statement.create(toAccountId, "2026-11", CURRENCY, 100_000L);

        when(statementRepository.findByAccountIdAndYearMonth(fromAccountId, "2026-11"))
                .thenReturn(Optional.of(fromStatement));
        when(statementRepository.findByAccountIdAndYearMonth(toAccountId, "2026-11"))
                .thenReturn(Optional.of(toStatement));

        consumer.consume(event, acknowledgment);

        verify(statementRepository, times(1)).save(statementCaptor.capture());
        Statement savedRecipient = statementCaptor.getValue();
        assertThat(savedRecipient.getAccountId()).isEqualTo(toAccountId);
        assertThat(savedRecipient.getClosingBalanceMinorUnits()).isEqualTo(150_000L);
        assertThat(savedRecipient.getEntries()).hasSize(1);

        verify(acknowledgment).acknowledge();
    }
}
