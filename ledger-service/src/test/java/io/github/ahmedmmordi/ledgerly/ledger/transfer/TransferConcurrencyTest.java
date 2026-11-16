package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.stream.Collectors.groupingBy;

import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountRepository;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountService;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotActiveException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.InsufficientFundsException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@DisplayName("Deadlock-Free Transfer Concurrency & Stress Tests")
class TransferConcurrencyTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("DB_POOL_MAX", () -> 30);
        registry.add("DB_POOL_MIN", () -> 5);
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("ledger.outbox.poller.enabled", () -> "false");
    }

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private static final UUID USER_A = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final UUID USER_B = UUID.fromString("0193297a-9a90-7888-8123-000000000002");
    private static final UUID USER_C = UUID.fromString("0193297a-9a90-7888-8123-000000000003");
    private static final UUID USER_D = UUID.fromString("0193297a-9a90-7888-8123-000000000004");

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @FunctionalInterface
    private interface ConcurrentTask {

        void execute(int taskIndex) throws Exception;
    }

    private record ConcurrencyResult(int successCount, List<Throwable> errors) {
    }

    private ConcurrencyResult runConcurrently(int taskCount, ConcurrentTask task) throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(taskCount);
        AtomicInteger successCounter = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errorQueue = new ConcurrentLinkedQueue<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        task.execute(index);
                        successCounter.incrementAndGet();
                    } catch (Throwable t) {
                        errorQueue.add(t);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean completed = finishGate.await(30, TimeUnit.SECONDS);
            assertThat(completed).as("All %d concurrent tasks should complete within 30s", taskCount).isTrue();
        }

        return new ConcurrencyResult(successCounter.get(), List.copyOf(errorQueue));
    }

    private void assertDoubleEntryLedgerInvariants(long expectedTransferCount) {
        assertThat(transferRepository.count()).as("Transfer repository count").isEqualTo(expectedTransferCount);

        List<Transfer> transfers = transferRepository.findAll();
        List<LedgerEntry> allEntries = ledgerEntryRepository.findAll();
        assertThat(allEntries).as("Ledger entries must be exactly 2 per transfer (1 debit, 1 credit)")
                .hasSize((int) expectedTransferCount * 2);

        long totalDebits = allEntries.stream().filter(e -> e.getType() == EntryType.DEBIT)
                .mapToLong(LedgerEntry::getAmount).sum();
        long totalCredits = allEntries.stream().filter(e -> e.getType() == EntryType.CREDIT)
                .mapToLong(LedgerEntry::getAmount).sum();

        assertThat(totalDebits).as("Total debits must equal total credits in double-entry ledger")
                .isEqualTo(totalCredits);

        // Deep verification: each transfer has exactly 1 DEBIT and 1 CREDIT entry of matching amount
        Map<UUID, List<LedgerEntry>> entriesByTransfer = allEntries.stream()
                .collect(groupingBy(LedgerEntry::getTransferId));

        for (Transfer transfer : transfers) {
            List<LedgerEntry> entries = entriesByTransfer.get(transfer.getId());
            assertThat(entries).as("Each transfer must have exactly 2 ledger entries").hasSize(2);

            LedgerEntry debit = entries.stream().filter(e -> e.getType() == EntryType.DEBIT).findFirst().orElseThrow();
            LedgerEntry credit = entries.stream().filter(e -> e.getType() == EntryType.CREDIT).findFirst()
                    .orElseThrow();

            assertThat(debit.getAccountId()).as("Debit entry must point to fromAccount")
                    .isEqualTo(transfer.getFromAccountId());
            assertThat(credit.getAccountId()).as("Credit entry must point to toAccount")
                    .isEqualTo(transfer.getToAccountId());
            assertThat(debit.getAmount()).as("Debit amount must match transfer amount")
                    .isEqualTo(transfer.getAmount().amountInMinorUnits());
            assertThat(credit.getAmount()).as("Credit amount must match transfer amount")
                    .isEqualTo(transfer.getAmount().amountInMinorUnits());
        }
    }

    @Test
    @DisplayName("Should execute 100 concurrent bidirectional transfers without deadlocks or balance drift")
    void shouldExecuteConcurrentBidirectionalTransfersWithoutDeadlock() throws InterruptedException {
        // Given: Account A and Account B each pre-funded with $10,000.00 USD
        Account accountA = accountService.createCustomerAccount(USER_A, "USD");
        Account accountB = accountService.createCustomerAccount(USER_B, "USD");

        transferService.deposit(accountA.getId(), Money.ofMinor(1_000_000L, "USD"), "Initial funding A", USER_A);
        transferService.deposit(accountB.getId(), Money.ofMinor(1_000_000L, "USD"), "Initial funding B", USER_B);

        long initialTotalMinor = 2_000_000L;
        int totalTransfers = 100;
        Money transferAmount = Money.ofMinor(1_000L, "USD"); // $10.00 each

        // When: 50 threads transfer A -> B, 50 threads transfer B -> A concurrently
        ConcurrencyResult result = runConcurrently(totalTransfers, index -> {
            boolean aToB = (index % 2 == 0);
            UUID fromId = aToB ? accountA.getId() : accountB.getId();
            UUID toId = aToB ? accountB.getId() : accountA.getId();
            UUID initiatedBy = aToB ? USER_A : USER_B;

            transferService.transfer(fromId, toId, transferAmount, "Concurrent transfer #" + index, initiatedBy);
        });

        // Then: 0 deadlocks, exactly 100 successful transfers
        assertThat(result.errors()).as("No exceptions or database deadlocks should occur").isEmpty();
        assertThat(result.successCount()).isEqualTo(100);

        // Balance conservation: Initial A (10k) - 50*10 + 50*10 = 10k, Initial B (10k) + 50*10 - 50*10 = 10k
        Account reloadedA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account reloadedB = accountRepository.findById(accountB.getId()).orElseThrow();

        assertThat(reloadedA.getBalance().amountInMinorUnits()).isEqualTo(1_000_000L);
        assertThat(reloadedB.getBalance().amountInMinorUnits()).isEqualTo(1_000_000L);
        assertThat(reloadedA.getBalance().amountInMinorUnits() + reloadedB.getBalance().amountInMinorUnits())
                .as("Total money in customer accounts must be strictly conserved").isEqualTo(initialTotalMinor);

        // Exactly 102 transfers (2 deposits + 100 transfers) with balanced ledger entries
        assertDoubleEntryLedgerInvariants(102);
    }

    @Test
    @DisplayName("Should execute 90 concurrent cyclic transfers (A->B, B->C, C->A) without circular wait deadlock")
    void shouldExecuteConcurrentCyclicTransfersWithoutDeadlock() throws InterruptedException {
        // Given: 3 accounts forming a potential circular wait graph (A -> B -> C -> A)
        Account accountA = accountService.createCustomerAccount(USER_A, "USD");
        Account accountB = accountService.createCustomerAccount(USER_B, "USD");
        Account accountC = accountService.createCustomerAccount(USER_C, "USD");

        transferService.deposit(accountA.getId(), Money.ofMinor(500_000L, "USD"), "Initial A", USER_A);
        transferService.deposit(accountB.getId(), Money.ofMinor(500_000L, "USD"), "Initial B", USER_B);
        transferService.deposit(accountC.getId(), Money.ofMinor(500_000L, "USD"), "Initial C", USER_C);

        long initialSum = 1_500_000L;
        int totalTransfers = 90; // 30 of each leg
        Money transferAmount = Money.ofMinor(1_000L, "USD");

        record TransferLeg(UUID fromId, UUID toId, UUID user) {
        }
        TransferLeg[] legs = { new TransferLeg(accountA.getId(), accountB.getId(), USER_A),
                new TransferLeg(accountB.getId(), accountC.getId(), USER_B),
                new TransferLeg(accountC.getId(), accountA.getId(), USER_C) };

        // When: 30 threads A->B, 30 threads B->C, 30 threads C->A executed simultaneously
        ConcurrencyResult result = runConcurrently(totalTransfers, index -> {
            TransferLeg leg = legs[index % 3];
            transferService.transfer(leg.fromId(), leg.toId(), transferAmount, "Cyclic leg " + (index % 3), leg.user());
        });

        // Then: 0 deadlocks, exactly 90 successful transfers
        assertThat(result.errors()).isEmpty();
        assertThat(result.successCount()).isEqualTo(90);

        Account reloadedA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account reloadedB = accountRepository.findById(accountB.getId()).orElseThrow();
        Account reloadedC = accountRepository.findById(accountC.getId()).orElseThrow();

        // Net change for each account is 0 (30 inflows of $10, 30 outflows of $10)
        assertThat(reloadedA.getBalance().amountInMinorUnits()).isEqualTo(500_000L);
        assertThat(reloadedB.getBalance().amountInMinorUnits()).isEqualTo(500_000L);
        assertThat(reloadedC.getBalance().amountInMinorUnits()).isEqualTo(500_000L);
        assertThat(reloadedA.getBalance().amountInMinorUnits() + reloadedB.getBalance().amountInMinorUnits()
                + reloadedC.getBalance().amountInMinorUnits()).isEqualTo(initialSum);

        // Exactly 93 transfers (3 deposits + 90 cyclic transfers)
        assertDoubleEntryLedgerInvariants(93);
    }

    @Test
    @DisplayName("Should strictly prevent race condition overdrawing and maintain non-negative balance invariant")
    void shouldPreventConcurrentOverdrawAndMaintainBalanceInvariant() throws InterruptedException {
        // Given: Account A has exactly $100.00 (10,000 cents). Account B has $0.00.
        Account accountA = accountService.createCustomerAccount(USER_A, "USD");
        Account accountB = accountService.createCustomerAccount(USER_B, "USD");

        transferService.deposit(accountA.getId(), Money.ofMinor(10_000L, "USD"), "Initial $100", USER_A);

        // 20 concurrent threads each try to withdraw $10.00 (1,000 cents).
        // Exactly 10 must succeed ($100 total); exactly 10 must fail with InsufficientFundsException.
        int totalAttempts = 20;
        Money transferAmount = Money.ofMinor(1_000L, "USD");

        ConcurrencyResult result = runConcurrently(totalAttempts, index -> {
            transferService.transfer(accountA.getId(), accountB.getId(), transferAmount,
                    "Contention withdraw #" + index, USER_A);
        });

        // Then: Exactly 10 transfers succeed, exactly 10 fail with InsufficientFundsException
        assertThat(result.successCount()).as("Exactly 10 transfers should succeed").isEqualTo(10);
        assertThat(result.errors()).as("Exactly 10 transfers should fail due to insufficient funds").hasSize(10)
                .allMatch(t -> t instanceof InsufficientFundsException
                        && t.getMessage().contains("has insufficient funds"));

        // Final balance of Account A must be exactly 0, never negative
        Account reloadedA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account reloadedB = accountRepository.findById(accountB.getId()).orElseThrow();

        assertThat(reloadedA.getBalance().amountInMinorUnits()).isEqualTo(0L);
        assertThat(reloadedB.getBalance().amountInMinorUnits()).isEqualTo(10_000L);

        // Exactly 11 transfers in DB (1 deposit + 10 successful transfers)
        assertDoubleEntryLedgerInvariants(11);
    }

    @Test
    @DisplayName("Should serialize concurrent transfers to the same account with zero lost updates")
    void shouldHandleConcurrentTransfersToSameAccountWithoutLostUpdates() throws InterruptedException {
        // Given: Account A starting with $0.00 and Account B pre-funded with $1,000.00 (100,000 cents)
        Account accountA = accountService.createCustomerAccount(USER_A, "USD");
        Account accountB = accountService.createCustomerAccount(USER_B, "USD");
        transferService.deposit(accountB.getId(), Money.ofMinor(100_000L, "USD"), "Initial funding B", USER_B);

        // 50 concurrent threads each transferring $20.00 (2,000 cents) from B to A
        int transferCount = 50;
        Money transferAmount = Money.ofMinor(2_000L, "USD");
        long expectedTotalMinor = 100_000L; // $1,000.00

        ConcurrencyResult result = runConcurrently(transferCount, index -> {
            transferService.transfer(accountB.getId(), accountA.getId(), transferAmount,
                    "Concurrent transfer #" + index, USER_B);
        });

        // Then: 50 transfers succeeded without deadlock or lost updates
        assertThat(result.errors()).isEmpty();
        assertThat(result.successCount()).isEqualTo(50);

        Account reloadedA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account reloadedB = accountRepository.findById(accountB.getId()).orElseThrow();

        assertThat(reloadedA.getBalance().amountInMinorUnits())
                .as("Account A balance must equal exactly the sum of all 50 concurrent transfers")
                .isEqualTo(expectedTotalMinor);
        assertThat(reloadedB.getBalance().amountInMinorUnits()).as("Account B balance must be 0").isEqualTo(0L);
        assertThat(reloadedA.getVersion()).as("Account A version must increment 50 times with zero lost updates")
                .isEqualTo(50L);
        assertThat(reloadedB.getVersion()).as("Account B version must increment 51 times (1 deposit + 50 debits)")
                .isEqualTo(51L);

        // Exactly 51 transfers in DB (1 deposit + 50 transfers)
        assertDoubleEntryLedgerInvariants(51);
    }

    @Test
    @DisplayName("Should preserve total balance conservation under 100 multi-account random concurrent transfers")
    void shouldConserveMoneyAcrossRandomMultiAccountTransfers() throws InterruptedException {
        // Given: 4 accounts pre-funded with $10,000.00 each (Total: $40,000.00 = 4,000,000 cents)
        List<Account> accounts = new ArrayList<>();
        List<UUID> users = List.of(USER_A, USER_B, USER_C, USER_D);

        for (UUID user : users) {
            Account acc = accountService.createCustomerAccount(user, "USD");
            transferService.deposit(acc.getId(), Money.ofMinor(1_000_000L, "USD"), "Initial funding", user);
            accounts.add(acc);
        }

        long initialTotalSum = 4_000_000L;
        int transferCount = 100;
        Random random = new Random(42);

        record PlannedTransfer(UUID fromId, UUID toId, UUID user, Money amount) {
        }
        List<PlannedTransfer> plannedTransfers = new ArrayList<>();

        for (int i = 0; i < transferCount; i++) {
            int fromIdx = random.nextInt(accounts.size());
            int toIdx;
            do {
                toIdx = random.nextInt(accounts.size());
            } while (toIdx == fromIdx);

            UUID fromId = accounts.get(fromIdx).getId();
            UUID toId = accounts.get(toIdx).getId();
            UUID user = users.get(fromIdx);
            Money amount = Money.ofMinor((random.nextInt(50) + 1) * 100L, "USD"); // $1.00 to $50.00

            plannedTransfers.add(new PlannedTransfer(fromId, toId, user, amount));
        }

        // When: Execute 100 random transfers concurrently
        ConcurrencyResult result = runConcurrently(transferCount, index -> {
            PlannedTransfer plan = plannedTransfers.get(index);
            transferService.transfer(plan.fromId(), plan.toId(), plan.amount(), "Random transfer #" + index,
                    plan.user());
        });

        // Then: 0 deadlocks occurred, all 100 transfers succeeded
        assertThat(result.errors()).isEmpty();
        assertThat(result.successCount()).isEqualTo(100);

        // Strict conservation of money across all 4 customer accounts
        long finalTotalSum = accounts.stream().mapToLong(a -> {
            Account reloaded = accountRepository.findById(a.getId()).orElseThrow();
            return reloaded.getBalance().amountInMinorUnits();
        }).sum();

        assertThat(finalTotalSum).as("Sum of money across all accounts must remain perfectly conserved")
                .isEqualTo(initialTotalSum);

        // Exactly 104 transfers (4 deposits + 100 random transfers)
        assertDoubleEntryLedgerInvariants(104);
    }

    @Test
    @DisplayName("Should safely reject all concurrent transfers when source account is frozen without deadlocks")
    void shouldRejectAllConcurrentTransfersWhenSourceAccountIsFrozen() throws InterruptedException {
        // Given: Account A pre-funded with $1,000.00 (100,000 cents), then FROZEN
        Account accountA = accountService.createCustomerAccount(USER_A, "USD");
        Account accountB = accountService.createCustomerAccount(USER_B, "USD");
        transferService.deposit(accountA.getId(), Money.ofMinor(100_000L, "USD"), "Initial funding A", USER_A);
        accountService.freezeAccount(accountA.getId());

        int totalAttempts = 30;
        Money transferAmount = Money.ofMinor(1_000L, "USD");

        // When: 30 threads concurrently attempt transfers from frozen Account A
        ConcurrencyResult result = runConcurrently(totalAttempts, index -> {
            transferService.transfer(accountA.getId(), accountB.getId(), transferAmount, "Transfer attempt #" + index,
                    USER_A);
        });

        // Then: Zero deadlocks, exactly 0 successful transfers, all 30 failed with AccountNotActiveException
        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.errors()).hasSize(30).allMatch(t -> t instanceof AccountNotActiveException);

        // Balances remain completely untouched
        Account reloadedA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account reloadedB = accountRepository.findById(accountB.getId()).orElseThrow();

        assertThat(reloadedA.getBalance().amountInMinorUnits()).isEqualTo(100_000L);
        assertThat(reloadedB.getBalance().amountInMinorUnits()).isEqualTo(0L);

        // Only the initial deposit transfer exists in DB
        assertDoubleEntryLedgerInvariants(1);
    }

    @Test
    @DisplayName("Should reject concurrent self-transfers fail-fast without acquiring locks or causing deadlocks")
    void shouldRejectConcurrentSelfTransfersFailFastWithoutDeadlock() throws InterruptedException {
        // Given: Account A pre-funded with $1,000.00
        Account accountA = accountService.createCustomerAccount(USER_A, "USD");
        transferService.deposit(accountA.getId(), Money.ofMinor(100_000L, "USD"), "Initial funding", USER_A);

        int totalAttempts = 20;
        Money transferAmount = Money.ofMinor(1_000L, "USD");

        // When: 20 threads attempt concurrent self-transfers (from A to A)
        ConcurrencyResult result = runConcurrently(totalAttempts, index -> {
            transferService.transfer(accountA.getId(), accountA.getId(), transferAmount, "Self-transfer #" + index,
                    USER_A);
        });

        // Then: All 20 fail fast with IllegalArgumentException
        assertThat(result.successCount()).isEqualTo(0);
        assertThat(result.errors()).hasSize(20).allMatch(t -> t instanceof IllegalArgumentException
                && "From account and to account must be distinct".equals(t.getMessage()));

        // Balance untouched
        Account reloadedA = accountRepository.findById(accountA.getId()).orElseThrow();
        assertThat(reloadedA.getBalance().amountInMinorUnits()).isEqualTo(100_000L);

        // Only the initial deposit transfer exists
        assertDoubleEntryLedgerInvariants(1);
    }
}
