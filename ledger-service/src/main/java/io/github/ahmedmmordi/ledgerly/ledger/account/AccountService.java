package io.github.ahmedmmordi.ledgerly.ledger.account;

import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import java.security.SecureRandom;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private static final int MAX_GENERATION_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;

    @Transactional
    public Account createCustomerAccount(UUID userId, String currencyCode) {
        Objects.requireNonNull(userId, "User ID must not be null");
        String normalizedCurrency = validateAndNormalizeCurrency(currencyCode);

        String accountNumber = generateUniqueAccountNumber();
        Account account = Account.createCustomer(userId, accountNumber, normalizedCurrency);
        Account saved = accountRepository.save(account);
        log.info("Created customer account with ID {} for user {}", saved.getId(), userId);
        return saved;
    }

    @Transactional
    public Account createSystemAccount(UUID userId, String currencyCode) {
        Objects.requireNonNull(userId, "User ID must not be null");
        String normalizedCurrency = validateAndNormalizeCurrency(currencyCode);

        String accountNumber = "SYS" + normalizedCurrency + "00000001";
        if (accountRepository.existsByAccountNumber(accountNumber)) {
            throw new IllegalStateException("System account already exists for currency " + normalizedCurrency);
        }

        Account account = Account.createSystem(userId, accountNumber, normalizedCurrency);
        Account saved = accountRepository.save(account);
        log.info("Created system account with ID {} for currency {}", saved.getId(), normalizedCurrency);
        return saved;
    }

    @Transactional
    public Account getOrCreateSystemAccount(UUID userId, String currencyCode) {
        Objects.requireNonNull(userId, "User ID must not be null");
        String normalizedCurrency = validateAndNormalizeCurrency(currencyCode);

        return accountRepository.findSystemAccount(normalizedCurrency).orElseGet(() -> {
            String accountNumber = "SYS" + normalizedCurrency + "00000001";
            Account account = Account.createSystem(userId, accountNumber, normalizedCurrency);
            Account saved = accountRepository.save(account);
            log.info("Auto-initialized system account with ID {} for currency {}", saved.getId(), normalizedCurrency);
            return saved;
        });
    }

    public Account getAccount(UUID accountId) {
        Objects.requireNonNull(accountId, "Account ID must not be null");
        return accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public Account getAccountByNumber(String accountNumber) {
        Objects.requireNonNull(accountNumber, "Account number must not be null");
        String normalizedAccountNumber = accountNumber.trim().toUpperCase();
        if (normalizedAccountNumber.isEmpty()) {
            throw new IllegalArgumentException("Account number must not be blank");
        }
        return accountRepository.findByAccountNumber(normalizedAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException(normalizedAccountNumber));
    }

    public Page<Account> getAccountsByUserId(UUID userId, Pageable pageable) {
        Objects.requireNonNull(userId, "User ID must not be null");
        Objects.requireNonNull(pageable, "Pageable must not be null");
        return accountRepository.findByUserId(userId, pageable);
    }

    public boolean isAccountOwnedByUser(UUID accountId, UUID userId) {
        Objects.requireNonNull(accountId, "Account ID must not be null");
        Objects.requireNonNull(userId, "User ID must not be null");
        return accountRepository.existsByIdAndUserId(accountId, userId);
    }

    public Money getBalance(UUID accountId) {
        return getAccount(accountId).getBalance();
    }

    @Transactional
    public Account freezeAccount(UUID accountId) {
        Account account = getAccount(accountId);
        account.freeze();
        Account saved = accountRepository.save(account);
        log.info("Froze account {}", accountId);
        return saved;
    }

    @Transactional
    public Account activateAccount(UUID accountId) {
        Account account = getAccount(accountId);
        account.activate();
        Account saved = accountRepository.save(account);
        log.info("Activated account {}", accountId);
        return saved;
    }

    @Transactional
    public Account closeAccount(UUID accountId) {
        Account account = getAccount(accountId);
        account.close();
        Account saved = accountRepository.save(account);
        log.info("Closed account {}", accountId);
        return saved;
    }

    private String generateUniqueAccountNumber() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; ++attempt) {
            long number = RANDOM.nextLong(1_000_000_000L, 10_000_000_000L);
            String candidate = "ACC" + number;
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        log.error("Failed to generate unique account number after {} attempts", MAX_GENERATION_ATTEMPTS);
        throw new IllegalStateException(
                "Failed to generate unique account number after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    private String validateAndNormalizeCurrency(String currencyCode) {
        Objects.requireNonNull(currencyCode, "Currency code must not be null");
        String normalized = currencyCode.trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Currency code must not be blank");
        }
        Currency.getInstance(normalized);
        return normalized;
    }
}
