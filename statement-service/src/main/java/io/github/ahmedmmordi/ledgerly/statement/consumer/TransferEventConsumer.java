package io.github.ahmedmmordi.ledgerly.statement.consumer;

import io.github.ahmedmmordi.ledgerly.common.event.TransferCompletedEvent;
import io.github.ahmedmmordi.ledgerly.statement.statement.Statement;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementEntry;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementEntryType;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementRepository;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@NullMarked
@Slf4j
@Component
public class TransferEventConsumer {

    private static final UUID CASH_VAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final StatementRepository statementRepository;

    public TransferEventConsumer(StatementRepository statementRepository) {
        this.statementRepository = statementRepository;
    }

    @KafkaListener(topics = "${statement.kafka.topics.transfers:ledger.transfers.v1}", groupId = "${statement.kafka.consumer.group-id:statement-service}")
    public void consume(TransferCompletedEvent event, Acknowledgment acknowledgment) {
        log.debug("Received TransferCompletedEvent: eventId={}, transferId={}", event.eventId(), event.transferId());

        String yearMonth = YearMonth.from(event.occurredAt().atZone(ZoneOffset.UTC)).toString();

        if (!CASH_VAULT_ID.equals(event.fromAccountId())) {
            processEntry(event, event.fromAccountId(), StatementEntryType.DEBIT, -event.amountInMinorUnits(),
                    yearMonth);
        }

        processEntry(event, event.toAccountId(), StatementEntryType.CREDIT, event.amountInMinorUnits(), yearMonth);

        acknowledgment.acknowledge();
        log.debug("Acknowledged offset for eventId={}", event.eventId());
    }

    private void processEntry(TransferCompletedEvent event, UUID accountId, StatementEntryType type, long delta,
            String yearMonth) {

        Statement statement = statementRepository.findByAccountIdAndYearMonth(accountId, yearMonth)
                .orElseGet(() -> createNewStatement(accountId, yearMonth, event.currency()));

        if (statement.containsEvent(event.eventId())) {
            log.info("Duplicate eventId={} for accountId={} yearMonth={} — skipping", event.eventId(), accountId,
                    yearMonth);
            return;
        }

        long newBalance = statement.getClosingBalanceMinorUnits() + delta;
        StatementEntry entry = new StatementEntry(event.eventId(), event.transferId(), type, Math.abs(delta),
                newBalance, event.occurredAt());

        statement.addEntry(entry);
        statementRepository.save(statement);

        log.debug("Saved {} entry: accountId={}, yearMonth={}, delta={}, newBalance={}", type, accountId, yearMonth,
                delta, newBalance);
    }

    private Statement createNewStatement(UUID accountId, String yearMonth, String currency) {
        long openingBalance = statementRepository.findFirstByAccountIdOrderByYearMonthDesc(accountId)
                .map(Statement::getClosingBalanceMinorUnits).orElse(0L);

        log.debug("Creating new statement: accountId={}, yearMonth={}, openingBalance={}", accountId, yearMonth,
                openingBalance);
        return Statement.create(accountId, yearMonth, currency, openingBalance);
    }
}
