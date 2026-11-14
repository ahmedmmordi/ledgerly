package io.github.ahmedmmordi.ledgerly.ledger.config;

import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountRepository;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountService;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.TransferService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class LedgerDataSeeder implements CommandLineRunner {

    public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    public static final UUID AHMED_USER_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    public static final UUID BOB_USER_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000002");
    public static final UUID CHARLIE_USER_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000003");

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final TransferService transferService;

    @Override
    public void run(String... args) {
        if (accountRepository.count() > 0) {
            log.info("Accounts table is not empty, skipping dev ledger seeding.");
            return;
        }

        log.info("Seeding initial dev ledger accounts and cash deposits...");

        Account systemAccount = accountService.getOrCreateSystemAccount(SYSTEM_USER_ID, "USD");
        log.info("System cash vault initialized: {} (ID: {})", systemAccount.getAccountNumber(), systemAccount.getId());

        Account ahmedAccount = accountService.createCustomerAccount(AHMED_USER_ID, "USD");
        Account bobAccount = accountService.createCustomerAccount(BOB_USER_ID, "USD");
        Account charlieAccount = accountService.createCustomerAccount(CHARLIE_USER_ID, "USD");

        transferService.deposit(ahmedAccount.getId(), Money.ofMinor(1000000L, "USD"), "Initial dev deposit - Ahmed",
                AHMED_USER_ID);
        transferService.deposit(bobAccount.getId(), Money.ofMinor(500000L, "USD"), "Initial dev deposit - Bob",
                BOB_USER_ID);
        transferService.deposit(charlieAccount.getId(), Money.ofMinor(250000L, "USD"), "Initial dev deposit - Charlie",
                CHARLIE_USER_ID);

        log.info("Successfully seeded 3 dev customer accounts with initial cash deposits.");
    }
}
