package io.github.ahmedmmordi.ledgerly.ledger.transfer.dto;

import io.github.ahmedmmordi.ledgerly.ledger.transfer.EntryType;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.LedgerEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record LedgerEntryResponse(UUID id, UUID transferId, UUID accountId, EntryType type, long amountInMinorUnits,
        BigDecimal amount, long balanceAfterInMinorUnits, BigDecimal balanceAfter, String currency, Instant createdAt) {

    public static LedgerEntryResponse from(LedgerEntry entry) {
        return from(entry, null);
    }

    public static LedgerEntryResponse from(LedgerEntry entry, String currency) {
        Objects.requireNonNull(entry, "LedgerEntry must not be null");
        BigDecimal formattedAmount = null;
        BigDecimal formattedBalanceAfter = null;
        String normalizedCurrency = null;
        if (currency != null && !currency.trim().isEmpty()) {
            Currency curr = Currency.getInstance(currency.trim().toUpperCase());
            formattedAmount = entry.getAmount(curr).toBigDecimal();
            formattedBalanceAfter = entry.getBalanceAfter(curr).toBigDecimal();
            normalizedCurrency = curr.getCurrencyCode();
        }
        return new LedgerEntryResponse(entry.getId(), entry.getTransferId(), entry.getAccountId(), entry.getType(),
                entry.getAmount(), formattedAmount, entry.getBalanceAfter(), formattedBalanceAfter, normalizedCurrency,
                entry.getCreatedAt());
    }
}
