package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountRepository;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountService;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.domain.TransferNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.rule.TransferContext;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.rule.TransferRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountService accountService;
    private final List<TransferRule> rules;

    public TransferService(AccountRepository accountRepository, TransferRepository transferRepository,
            LedgerEntryRepository ledgerEntryRepository, AccountService accountService, List<TransferRule> rules) {
        this.accountRepository = Objects.requireNonNull(accountRepository, "AccountRepository must not be null");
        this.transferRepository = Objects.requireNonNull(transferRepository, "TransferRepository must not be null");
        this.ledgerEntryRepository = Objects.requireNonNull(ledgerEntryRepository,
                "LedgerEntryRepository must not be null");
        this.accountService = Objects.requireNonNull(accountService, "AccountService must not be null");
        Objects.requireNonNull(rules, "Transfer rules must not be null");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Transfer rules must not be empty");
        }
        for (TransferRule rule : rules) {
            Objects.requireNonNull(rule, "Transfer rule must not be null");
        }
        List<TransferRule> sortedRules = new ArrayList<>(rules);
        AnnotationAwareOrderComparator.sort(sortedRules);
        this.rules = List.copyOf(sortedRules);
    }

    @Transactional
    public Transfer transfer(UUID fromAccountId, UUID toAccountId, Money amount, String description,
            UUID initiatedByUserId) {
        Objects.requireNonNull(fromAccountId, "From account ID must not be null");
        Objects.requireNonNull(toAccountId, "To account ID must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("From account and to account must be distinct");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Transfer amount must be strictly positive");
        }

        UUID firstId = fromAccountId.compareTo(toAccountId) < 0 ? fromAccountId : toAccountId;
        UUID secondId = fromAccountId.compareTo(toAccountId) < 0 ? toAccountId : fromAccountId;

        Account firstLocked = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new AccountNotFoundException(firstId));
        Account secondLocked = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new AccountNotFoundException(secondId));

        Account fromAccount = fromAccountId.equals(firstId) ? firstLocked : secondLocked;
        Account toAccount = toAccountId.equals(firstId) ? firstLocked : secondLocked;

        TransferContext context = new TransferContext(fromAccount, toAccount, amount);
        for (TransferRule rule : rules) {
            rule.validate(context);
        }

        fromAccount.debit(amount);
        toAccount.credit(amount);
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        Transfer transfer = Transfer.createCompleted(fromAccountId, toAccountId, amount, description,
                initiatedByUserId);
        Transfer savedTransfer = transferRepository.save(transfer);

        LedgerEntry debitEntry = LedgerEntry.createDebit(savedTransfer.getId(), fromAccountId, amount,
                fromAccount.getBalance());
        LedgerEntry creditEntry = LedgerEntry.createCredit(savedTransfer.getId(), toAccountId, amount,
                toAccount.getBalance());
        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        log.info("Completed transfer {} of {} minor units from account {} to account {}", savedTransfer.getId(),
                amount.amountInMinorUnits(), fromAccountId, toAccountId);
        return savedTransfer;
    }

    @Transactional
    public Transfer deposit(UUID accountId, Money amount, String description, UUID initiatedByUserId) {
        Objects.requireNonNull(accountId, "Account ID must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        Objects.requireNonNull(initiatedByUserId, "Initiated by user ID must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Transfer amount must be strictly positive");
        }

        String currencyCode = amount.currency().getCurrencyCode();
        Account systemAccount = accountService.getOrCreateSystemAccount(initiatedByUserId, currencyCode);

        String depositDescription = (description != null && !description.trim().isEmpty()) ? description.trim()
                : "Cash Deposit";

        return transfer(systemAccount.getId(), accountId, amount, depositDescription, initiatedByUserId);
    }

    public Transfer getTransfer(UUID transferId) {
        Objects.requireNonNull(transferId, "Transfer ID must not be null");
        return transferRepository.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
    }

    public Page<Transfer> getTransfersByAccountId(UUID accountId, Pageable pageable) {
        Objects.requireNonNull(accountId, "Account ID must not be null");
        Objects.requireNonNull(pageable, "Pageable must not be null");
        return transferRepository.findByAccountId(accountId, pageable);
    }

    public Page<Transfer> getTransfersByInitiatedUserId(UUID userId, Pageable pageable) {
        Objects.requireNonNull(userId, "User ID must not be null");
        Objects.requireNonNull(pageable, "Pageable must not be null");
        return transferRepository.findByInitiatedByUserId(userId, pageable);
    }

    public List<LedgerEntry> getLedgerEntriesByTransferId(UUID transferId) {
        Objects.requireNonNull(transferId, "Transfer ID must not be null");
        return ledgerEntryRepository.findByTransferIdOrderByTypeAsc(transferId);
    }

    public Page<LedgerEntry> getLedgerEntriesByAccountId(UUID accountId, Pageable pageable) {
        Objects.requireNonNull(accountId, "Account ID must not be null");
        Objects.requireNonNull(pageable, "Pageable must not be null");
        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable);
    }
}
