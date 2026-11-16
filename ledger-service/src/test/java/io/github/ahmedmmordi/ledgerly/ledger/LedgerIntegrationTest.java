package io.github.ahmedmmordi.ledgerly.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountRepository;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountStatus;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountType;
import io.github.ahmedmmordi.ledgerly.ledger.account.dto.AccountResponse;
import io.github.ahmedmmordi.ledgerly.ledger.account.dto.CreateAccountRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.EntryType;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.LedgerEntry;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.LedgerEntryRepository;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.Transfer;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.TransferRepository;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.TransferStatus;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.DepositRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.LedgerEntryResponse;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Ledger Service Integration Tests")
class LedgerIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("ledger.outbox.poller.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private RestClient restClient;

    private static final UUID USER_1_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final UUID USER_2_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000002");

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    record HttpResponse<T>(HttpStatusCode status, T body, ProblemDetail problem) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PageResponse<T>(List<T> content, int totalPages, long totalElements, int size, int number) {
    }

    private <T> HttpResponse<T> postJson(String uri, Object body, Class<T> responseType) {
        return restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return new HttpResponse<>(status, response.bodyTo(responseType), null);
                    } else {
                        return new HttpResponse<>(status, null, response.bodyTo(ProblemDetail.class));
                    }
                });
    }

    private <T> HttpResponse<T> getJson(String uri, Class<T> responseType) {
        return restClient.get().uri(uri).exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            if (status.is2xxSuccessful()) {
                return new HttpResponse<>(status, response.bodyTo(responseType), null);
            } else {
                return new HttpResponse<>(status, null, response.bodyTo(ProblemDetail.class));
            }
        });
    }

    private <T> HttpResponse<T> getJson(String uri, ParameterizedTypeReference<T> responseType) {
        return restClient.get().uri(uri).exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            if (status.is2xxSuccessful()) {
                return new HttpResponse<>(status, response.bodyTo(responseType), null);
            } else {
                return new HttpResponse<>(status, null, response.bodyTo(ProblemDetail.class));
            }
        });
    }

    private <T> HttpResponse<T> putJson(String uri, Class<T> responseType) {
        return restClient.put().uri(uri).exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            if (status.is2xxSuccessful()) {
                return new HttpResponse<>(status, response.bodyTo(responseType), null);
            } else {
                return new HttpResponse<>(status, null, response.bodyTo(ProblemDetail.class));
            }
        });
    }

    private HttpResponse<Void> delete(String uri) {
        return restClient.delete().uri(uri).exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            if (status.is2xxSuccessful()) {
                return new HttpResponse<>(status, null, null);
            } else {
                return new HttpResponse<>(status, null, response.bodyTo(ProblemDetail.class));
            }
        });
    }

    @Test
    @DisplayName("executes complete transfer flow and asserts balanced double-entry ledger invariants")
    void shouldCompleteFullTransferWorkflowWithBalancedDoubleEntryLedger() {
        // 1. Create Account A for User 1
        HttpResponse<AccountResponse> accountARes = postJson("/api/v1/accounts",
                new CreateAccountRequest(USER_1_ID, "USD"), AccountResponse.class);
        assertThat(accountARes.status()).isEqualTo(HttpStatus.CREATED);
        AccountResponse accountA = accountARes.body();
        assertThat(accountA).isNotNull();
        assertThat(accountA.id()).isNotNull();
        assertThat(accountA.userId()).isEqualTo(USER_1_ID);
        assertThat(accountA.currency()).isEqualTo("USD");
        assertThat(accountA.balanceInMinorUnits()).isZero();
        assertThat(accountA.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(accountA.type()).isEqualTo(AccountType.CUSTOMER);
        assertThat(accountA.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(accountA.accountNumber()).startsWith("ACC");
        assertThat(accountA.createdAt()).isNotNull();
        assertThat(accountA.updatedAt()).isNotNull();

        // 2. Create Account B for User 2
        HttpResponse<AccountResponse> accountBRes = postJson("/api/v1/accounts",
                new CreateAccountRequest(USER_2_ID, "USD"), AccountResponse.class);
        assertThat(accountBRes.status()).isEqualTo(HttpStatus.CREATED);
        AccountResponse accountB = accountBRes.body();
        assertThat(accountB).isNotNull();
        assertThat(accountB.id()).isNotNull();
        assertThat(accountB.accountNumber()).startsWith("ACC").isNotEqualTo(accountA.accountNumber());

        // 3. Deposit $1,000.00 (100,000 cents) into Account A
        DepositRequest depositReq = new DepositRequest(accountA.id(), 100000L, "USD", "Initial payroll deposit",
                USER_1_ID);
        HttpResponse<TransferResponse> depositRes = postJson("/api/v1/transfers/deposit", depositReq,
                TransferResponse.class);
        assertThat(depositRes.status()).isEqualTo(HttpStatus.CREATED);
        TransferResponse deposit = depositRes.body();
        assertThat(deposit).isNotNull();
        assertThat(deposit.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(deposit.amountInMinorUnits()).isEqualTo(100000L);
        assertThat(deposit.amount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(deposit.currency()).isEqualTo("USD");
        assertThat(deposit.toAccountId()).isEqualTo(accountA.id());
        assertThat(deposit.description()).isEqualTo("Initial payroll deposit");

        // Verify Account A balance updated to $1,000.00
        HttpResponse<AccountResponse> updatedARes = getJson("/api/v1/accounts/" + accountA.id(), AccountResponse.class);
        assertThat(updatedARes.status()).isEqualTo(HttpStatus.OK);
        assertThat(updatedARes.body().balanceInMinorUnits()).isEqualTo(100000L);
        assertThat(updatedARes.body().balance()).isEqualByComparingTo(new BigDecimal("1000.00"));

        // 4. Transfer $400.00 (40,000 cents) from Account A to Account B
        TransferRequest transferReq = new TransferRequest(accountA.id(), accountB.id(), 40000L, "USD",
                "Rent reimbursement", USER_1_ID);
        HttpResponse<TransferResponse> transferRes = postJson("/api/v1/transfers", transferReq, TransferResponse.class);
        assertThat(transferRes.status()).isEqualTo(HttpStatus.CREATED);
        TransferResponse transfer = transferRes.body();
        assertThat(transfer).isNotNull();
        assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.amountInMinorUnits()).isEqualTo(40000L);
        assertThat(transfer.amount()).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(transfer.currency()).isEqualTo("USD");
        assertThat(transfer.fromAccountId()).isEqualTo(accountA.id());
        assertThat(transfer.toAccountId()).isEqualTo(accountB.id());
        assertThat(transfer.failureReason()).isNull();

        // 5. Verify single transfer lookup via GET /api/v1/transfers/{id}
        HttpResponse<TransferResponse> getTransferRes = getJson("/api/v1/transfers/" + transfer.id(),
                TransferResponse.class);
        assertThat(getTransferRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(getTransferRes.body()).isNotNull();
        assertThat(getTransferRes.body().id()).isEqualTo(transfer.id());
        assertThat(getTransferRes.body().status()).isEqualTo(TransferStatus.COMPLETED);

        // 6. Verify updated balances across both customer accounts
        HttpResponse<AccountResponse> finalARes = getJson("/api/v1/accounts/" + accountA.id(), AccountResponse.class);
        assertThat(finalARes.body().balanceInMinorUnits()).isEqualTo(60000L);
        assertThat(finalARes.body().balance()).isEqualByComparingTo(new BigDecimal("600.00"));

        HttpResponse<AccountResponse> finalBRes = getJson("/api/v1/accounts/" + accountB.id(), AccountResponse.class);
        assertThat(finalBRes.body().balanceInMinorUnits()).isEqualTo(40000L);
        assertThat(finalBRes.body().balance()).isEqualByComparingTo(new BigDecimal("400.00"));

        // 7. Assert Double-Entry Ledger Invariants via GET /api/v1/transfers/{id}/entries
        HttpResponse<List<LedgerEntryResponse>> entriesRes = getJson("/api/v1/transfers/" + transfer.id() + "/entries",
                new ParameterizedTypeReference<List<LedgerEntryResponse>>() {
                });
        assertThat(entriesRes.status()).isEqualTo(HttpStatus.OK);
        List<LedgerEntryResponse> entries = entriesRes.body();
        assertThat(entries).isNotNull().hasSize(2);

        LedgerEntryResponse debitEntry = entries.stream().filter(e -> e.type() == EntryType.DEBIT).findFirst()
                .orElseThrow();
        assertThat(debitEntry.id()).isNotNull();
        assertThat(debitEntry.transferId()).isEqualTo(transfer.id());
        assertThat(debitEntry.accountId()).isEqualTo(accountA.id());
        assertThat(debitEntry.amountInMinorUnits()).isEqualTo(40000L);
        assertThat(debitEntry.amount()).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(debitEntry.balanceAfterInMinorUnits()).isEqualTo(60000L);
        assertThat(debitEntry.balanceAfter()).isEqualByComparingTo(new BigDecimal("600.00"));
        assertThat(debitEntry.currency()).isEqualTo("USD");

        LedgerEntryResponse creditEntry = entries.stream().filter(e -> e.type() == EntryType.CREDIT).findFirst()
                .orElseThrow();
        assertThat(creditEntry.id()).isNotNull();
        assertThat(creditEntry.transferId()).isEqualTo(transfer.id());
        assertThat(creditEntry.accountId()).isEqualTo(accountB.id());
        assertThat(creditEntry.amountInMinorUnits()).isEqualTo(40000L);
        assertThat(creditEntry.amount()).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(creditEntry.balanceAfterInMinorUnits()).isEqualTo(40000L);
        assertThat(creditEntry.balanceAfter()).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(creditEntry.currency()).isEqualTo("USD");

        // Invariant 1: sum(debits) == sum(credits)
        long sumDebits = entries.stream().filter(e -> e.type() == EntryType.DEBIT)
                .mapToLong(LedgerEntryResponse::amountInMinorUnits).sum();
        long sumCredits = entries.stream().filter(e -> e.type() == EntryType.CREDIT)
                .mapToLong(LedgerEntryResponse::amountInMinorUnits).sum();
        assertThat(sumDebits).isEqualTo(sumCredits).isEqualTo(40000L);

        // Invariant 2: Total conservation of money across all accounts (Customer + System Vault) is zero
        long totalSystemBalance = accountRepository.findAll().stream()
                .mapToLong(acc -> acc.getBalance().amountInMinorUnits()).sum();
        assertThat(totalSystemBalance).isZero();

        // 8. Verify persistent database state directly via repositories
        Account dbAccountA = accountRepository.findById(accountA.id()).orElseThrow();
        Account dbAccountB = accountRepository.findById(accountB.id()).orElseThrow();
        assertThat(dbAccountA.getBalance().amountInMinorUnits()).isEqualTo(60000L);
        assertThat(dbAccountB.getBalance().amountInMinorUnits()).isEqualTo(40000L);

        Transfer dbTransfer = transferRepository.findById(transfer.id()).orElseThrow();
        assertThat(dbTransfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);

        List<LedgerEntry> dbEntries = ledgerEntryRepository.findByTransferId(transfer.id());
        assertThat(dbEntries).hasSize(2);
    }

    @Test
    @DisplayName("retrieves paginated customer accounts by user ID")
    void shouldRetrievePaginatedAccountsByUserId() {
        postJson("/api/v1/accounts", new CreateAccountRequest(USER_1_ID, "USD"), AccountResponse.class);
        postJson("/api/v1/accounts", new CreateAccountRequest(USER_1_ID, "EUR"), AccountResponse.class);
        postJson("/api/v1/accounts", new CreateAccountRequest(USER_2_ID, "USD"), AccountResponse.class);

        HttpResponse<PageResponse<AccountResponse>> user1Accounts = getJson(
                "/api/v1/accounts?userId=" + USER_1_ID + "&page=0&size=10",
                new ParameterizedTypeReference<PageResponse<AccountResponse>>() {
                });
        assertThat(user1Accounts.status()).isEqualTo(HttpStatus.OK);
        assertThat(user1Accounts.body()).isNotNull();
        assertThat(user1Accounts.body().content()).hasSize(2);
        assertThat(user1Accounts.body().totalElements()).isEqualTo(2L);

        HttpResponse<PageResponse<AccountResponse>> user2Accounts = getJson(
                "/api/v1/accounts?userId=" + USER_2_ID + "&page=0&size=10",
                new ParameterizedTypeReference<PageResponse<AccountResponse>>() {
                });
        assertThat(user2Accounts.status()).isEqualTo(HttpStatus.OK);
        assertThat(user2Accounts.body()).isNotNull();
        assertThat(user2Accounts.body().content()).hasSize(1);
        assertThat(user2Accounts.body().totalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("rejects transfer when source account has insufficient funds and keeps balances intact")
    void shouldRejectTransferWhenFundsAreInsufficient() {
        AccountResponse accountA = postJson("/api/v1/accounts", new CreateAccountRequest(USER_1_ID, "USD"),
                AccountResponse.class).body();
        AccountResponse accountB = postJson("/api/v1/accounts", new CreateAccountRequest(USER_2_ID, "USD"),
                AccountResponse.class).body();

        postJson("/api/v1/transfers/deposit",
                new DepositRequest(accountA.id(), 10000L, "USD", "Initial deposit", USER_1_ID), TransferResponse.class);

        TransferRequest overdrawReq = new TransferRequest(accountA.id(), accountB.id(), 25000L, "USD", "Overdraft test",
                USER_1_ID);
        HttpResponse<TransferResponse> failedRes = postJson("/api/v1/transfers", overdrawReq, TransferResponse.class);

        assertThat(failedRes.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(failedRes.problem()).isNotNull();
        assertThat(failedRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:insufficient-funds"));
        assertThat(failedRes.problem().getTitle()).isEqualTo("Insufficient Funds");
        assertThat(failedRes.problem().getProperties()).containsEntry("accountId", accountA.id().toString());
        assertThat(failedRes.problem().getProperties()).containsEntry("requestedAmount", "250.00 USD");
        assertThat(failedRes.problem().getProperties()).containsEntry("currentBalance", "100.00 USD");

        // Assert balances are unchanged
        AccountResponse checkA = getJson("/api/v1/accounts/" + accountA.id(), AccountResponse.class).body();
        AccountResponse checkB = getJson("/api/v1/accounts/" + accountB.id(), AccountResponse.class).body();
        assertThat(checkA.balanceInMinorUnits()).isEqualTo(10000L);
        assertThat(checkB.balanceInMinorUnits()).isZero();
    }

    @Test
    @DisplayName("rejects transfer when source and destination accounts are the same")
    void shouldRejectTransferBetweenIdenticalAccounts() {
        AccountResponse account = postJson("/api/v1/accounts", new CreateAccountRequest(USER_1_ID, "USD"),
                AccountResponse.class).body();

        TransferRequest sameAccountReq = new TransferRequest(account.id(), account.id(), 1000L, "USD", "Self transfer",
                USER_1_ID);
        HttpResponse<TransferResponse> res = postJson("/api/v1/transfers", sameAccountReq, TransferResponse.class);

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.problem()).isNotNull();
        assertThat(res.problem().getType()).isEqualTo(URI.create("urn:problem-type:bad-request"));
        assertThat(res.problem().getTitle()).isEqualTo("Invalid Argument");
        assertThat(res.problem().getDetail()).contains("distinct");
    }

    @Test
    @DisplayName("rejects transfer when source and destination currencies do not match")
    void shouldRejectTransferWhenCurrenciesMismatch() {
        AccountResponse accountUSD = postJson("/api/v1/accounts", new CreateAccountRequest(USER_1_ID, "USD"),
                AccountResponse.class).body();
        AccountResponse accountEUR = postJson("/api/v1/accounts", new CreateAccountRequest(USER_2_ID, "EUR"),
                AccountResponse.class).body();

        postJson("/api/v1/transfers/deposit",
                new DepositRequest(accountUSD.id(), 50000L, "USD", "Deposit USD", USER_1_ID), TransferResponse.class);

        TransferRequest mismatchReq = new TransferRequest(accountUSD.id(), accountEUR.id(), 2000L, "USD",
                "Cross-currency", USER_1_ID);
        HttpResponse<TransferResponse> res = postJson("/api/v1/transfers", mismatchReq, TransferResponse.class);

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.problem()).isNotNull();
        assertThat(res.problem().getType()).isEqualTo(URI.create("urn:problem-type:currency-mismatch"));
        assertThat(res.problem().getTitle()).isEqualTo("Currency Mismatch");
    }

    @Test
    @DisplayName("blocks transfers on frozen accounts and permits transfers after reactivation")
    void shouldHandleFrozenAccountLifecycleTransitions() {
        AccountResponse accountA = postJson("/api/v1/accounts", new CreateAccountRequest(USER_1_ID, "USD"),
                AccountResponse.class).body();
        AccountResponse accountB = postJson("/api/v1/accounts", new CreateAccountRequest(USER_2_ID, "USD"),
                AccountResponse.class).body();

        postJson("/api/v1/transfers/deposit",
                new DepositRequest(accountA.id(), 50000L, "USD", "Initial deposit", USER_1_ID), TransferResponse.class);

        // Freeze Account A
        HttpResponse<AccountResponse> freezeRes = putJson("/api/v1/accounts/" + accountA.id() + "/freeze",
                AccountResponse.class);
        assertThat(freezeRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(freezeRes.body().status()).isEqualTo(AccountStatus.FROZEN);

        // Transfer from frozen Account A should fail with 409 Conflict
        TransferRequest transferFromFrozen = new TransferRequest(accountA.id(), accountB.id(), 10000L, "USD",
                "Blocked transfer", USER_1_ID);
        HttpResponse<TransferResponse> blockedRes = postJson("/api/v1/transfers", transferFromFrozen,
                TransferResponse.class);

        assertThat(blockedRes.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blockedRes.problem()).isNotNull();
        assertThat(blockedRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:account-not-active"));
        assertThat(blockedRes.problem().getTitle()).isEqualTo("Account Not Active");
        assertThat(blockedRes.problem().getProperties()).containsEntry("accountStatus", "FROZEN");

        // Reactivate Account A
        HttpResponse<AccountResponse> activateRes = putJson("/api/v1/accounts/" + accountA.id() + "/activate",
                AccountResponse.class);
        assertThat(activateRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(activateRes.body().status()).isEqualTo(AccountStatus.ACTIVE);

        // Now transfer should succeed
        HttpResponse<TransferResponse> retryRes = postJson("/api/v1/transfers", transferFromFrozen,
                TransferResponse.class);
        assertThat(retryRes.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(retryRes.body().status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    @DisplayName("enforces account closing invariants and terminal lifecycle behavior")
    void shouldEnforceAccountClosingInvariantsAndTerminalLifecycle() {
        // Funded account cannot be closed
        AccountResponse fundedAccount = postJson("/api/v1/accounts", new CreateAccountRequest(USER_1_ID, "USD"),
                AccountResponse.class).body();
        postJson("/api/v1/transfers/deposit",
                new DepositRequest(fundedAccount.id(), 20000L, "USD", "Deposit", USER_1_ID), TransferResponse.class);

        HttpResponse<Void> failCloseRes = delete("/api/v1/accounts/" + fundedAccount.id());
        assertThat(failCloseRes.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failCloseRes.problem()).isNotNull();
        assertThat(failCloseRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:invalid-state"));
        assertThat(failCloseRes.problem().getTitle()).isEqualTo("Invalid State");
        assertThat(failCloseRes.problem().getDetail()).contains("non-zero balance");

        // Zero-balance account can be closed
        AccountResponse emptyAccount = postJson("/api/v1/accounts", new CreateAccountRequest(USER_2_ID, "USD"),
                AccountResponse.class).body();
        HttpResponse<Void> successCloseRes = delete("/api/v1/accounts/" + emptyAccount.id());
        assertThat(successCloseRes.status()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify account status is now CLOSED
        AccountResponse closedAccount = getJson("/api/v1/accounts/" + emptyAccount.id(), AccountResponse.class).body();
        assertThat(closedAccount.status()).isEqualTo(AccountStatus.CLOSED);

        // Terminal state: Cannot freeze a closed account
        HttpResponse<AccountResponse> freezeClosedRes = putJson("/api/v1/accounts/" + emptyAccount.id() + "/freeze",
                AccountResponse.class);
        assertThat(freezeClosedRes.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(freezeClosedRes.problem()).isNotNull();
        assertThat(freezeClosedRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:invalid-state"));
        assertThat(freezeClosedRes.problem().getDetail()).contains("Cannot freeze a closed account");

        // Terminal state: Cannot activate a closed account
        HttpResponse<AccountResponse> activateClosedRes = putJson("/api/v1/accounts/" + emptyAccount.id() + "/activate",
                AccountResponse.class);
        assertThat(activateClosedRes.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(activateClosedRes.problem()).isNotNull();
        assertThat(activateClosedRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:invalid-state"));
        assertThat(activateClosedRes.problem().getDetail()).contains("Cannot activate a closed account");
    }

    @Test
    @DisplayName("retrieves paginated transfers filtered by account ID and by user ID")
    void shouldQueryTransfersPaginatedByAccountAndByUser() {
        AccountResponse accountA = postJson("/api/v1/accounts", new CreateAccountRequest(USER_1_ID, "USD"),
                AccountResponse.class).body();
        AccountResponse accountB = postJson("/api/v1/accounts", new CreateAccountRequest(USER_2_ID, "USD"),
                AccountResponse.class).body();

        postJson("/api/v1/transfers/deposit",
                new DepositRequest(accountA.id(), 100000L, "USD", "Initial deposit", USER_1_ID),
                TransferResponse.class);
        postJson("/api/v1/transfers",
                new TransferRequest(accountA.id(), accountB.id(), 10000L, "USD", "Payment 1", USER_1_ID),
                TransferResponse.class);
        postJson("/api/v1/transfers",
                new TransferRequest(accountA.id(), accountB.id(), 20000L, "USD", "Payment 2", USER_1_ID),
                TransferResponse.class);

        // Query transfers by account A
        HttpResponse<PageResponse<TransferResponse>> accountQuery = getJson(
                "/api/v1/transfers?accountId=" + accountA.id() + "&page=0&size=10",
                new ParameterizedTypeReference<PageResponse<TransferResponse>>() {
                });
        assertThat(accountQuery.status()).isEqualTo(HttpStatus.OK);
        PageResponse<TransferResponse> accountPage = accountQuery.body();
        assertThat(accountPage).isNotNull();
        assertThat(accountPage.content()).hasSize(3);
        assertThat(accountPage.totalElements()).isEqualTo(3L);

        // Query transfers by user ID
        HttpResponse<PageResponse<TransferResponse>> userQuery = getJson(
                "/api/v1/transfers?userId=" + USER_1_ID + "&page=0&size=10",
                new ParameterizedTypeReference<PageResponse<TransferResponse>>() {
                });
        assertThat(userQuery.status()).isEqualTo(HttpStatus.OK);
        PageResponse<TransferResponse> userPage = userQuery.body();
        assertThat(userPage).isNotNull();
        assertThat(userPage.content()).hasSize(3);
        assertThat(userPage.totalElements()).isEqualTo(3L);

        // Querying with both accountId and userId should fail with 400 Bad Request
        HttpResponse<PageResponse<TransferResponse>> conflictQuery = getJson(
                "/api/v1/transfers?accountId=" + accountA.id() + "&userId=" + USER_1_ID,
                new ParameterizedTypeReference<PageResponse<TransferResponse>>() {
                });
        assertThat(conflictQuery.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(conflictQuery.problem()).isNotNull();
        assertThat(conflictQuery.problem().getType()).isEqualTo(URI.create("urn:problem-type:bad-request"));

        // Querying without any filter parameter should fail with 400 Bad Request
        HttpResponse<PageResponse<TransferResponse>> emptyFilterQuery = getJson("/api/v1/transfers",
                new ParameterizedTypeReference<PageResponse<TransferResponse>>() {
                });
        assertThat(emptyFilterQuery.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("returns 404 Not Found for non-existent account and transfer entities")
    void shouldReturn404NotFoundForNonExistentEntities() {
        UUID nonExistentId = UUID.randomUUID();

        // 1. Account by ID
        HttpResponse<AccountResponse> accountByIdRes = getJson("/api/v1/accounts/" + nonExistentId,
                AccountResponse.class);
        assertThat(accountByIdRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(accountByIdRes.problem()).isNotNull();
        assertThat(accountByIdRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:account-not-found"));
        assertThat(accountByIdRes.problem().getTitle()).isEqualTo("Account Not Found");
        assertThat(accountByIdRes.problem().getProperties()).containsEntry("accountId", nonExistentId.toString());

        // 2. Account by number
        HttpResponse<AccountResponse> accountByNumRes = getJson("/api/v1/accounts/number/ACC9999999999",
                AccountResponse.class);
        assertThat(accountByNumRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(accountByNumRes.problem()).isNotNull();
        assertThat(accountByNumRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:account-not-found"));
        assertThat(accountByNumRes.problem().getProperties()).containsEntry("accountNumber", "ACC9999999999");

        // 3. Transfer by ID
        HttpResponse<TransferResponse> transferByIdRes = getJson("/api/v1/transfers/" + nonExistentId,
                TransferResponse.class);
        assertThat(transferByIdRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(transferByIdRes.problem()).isNotNull();
        assertThat(transferByIdRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:transfer-not-found"));
        assertThat(transferByIdRes.problem().getTitle()).isEqualTo("Transfer Not Found");
        assertThat(transferByIdRes.problem().getProperties()).containsEntry("transferId", nonExistentId.toString());

        // 4. Ledger entries by non-existent transfer ID
        HttpResponse<List<LedgerEntryResponse>> entriesRes = getJson("/api/v1/transfers/" + nonExistentId + "/entries",
                new ParameterizedTypeReference<List<LedgerEntryResponse>>() {
                });
        assertThat(entriesRes.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(entriesRes.problem()).isNotNull();
        assertThat(entriesRes.problem().getType()).isEqualTo(URI.create("urn:problem-type:transfer-not-found"));
    }

    @Test
    @DisplayName("rejects malformed transfer payloads with 400 Bad Request and validation problem detail")
    void shouldRejectInvalidTransferPayloads() {
        Map<String, Object> invalidPayload = Map.of("currency", "INVALID", "amount", -500);

        HttpResponse<TransferResponse> res = postJson("/api/v1/transfers", invalidPayload, TransferResponse.class);
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.problem()).isNotNull();
        assertThat(res.problem().getType()).isEqualTo(URI.create("urn:problem-type:validation-error"));
        assertThat(res.problem().getTitle()).isEqualTo("Validation Failed");
        assertThat(res.problem().getDetail()).contains("fromAccountId", "toAccountId");
    }

    @Test
    @DisplayName("looks up account by generated unique account number")
    void shouldLookupAccountByAccountNumber() {
        AccountResponse created = postJson("/api/v1/accounts", new CreateAccountRequest(USER_1_ID, "USD"),
                AccountResponse.class).body();

        HttpResponse<AccountResponse> lookup = getJson("/api/v1/accounts/number/" + created.accountNumber(),
                AccountResponse.class);
        assertThat(lookup.status()).isEqualTo(HttpStatus.OK);
        assertThat(lookup.body()).isNotNull();
        assertThat(lookup.body().id()).isEqualTo(created.id());
        assertThat(lookup.body().accountNumber()).isEqualTo(created.accountNumber());
        assertThat(lookup.body().currency()).isEqualTo("USD");
        assertThat(lookup.body().type()).isEqualTo(AccountType.CUSTOMER);
        assertThat(lookup.body().status()).isEqualTo(AccountStatus.ACTIVE);
    }
}
