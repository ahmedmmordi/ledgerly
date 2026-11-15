package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedmmordi.ledgerly.ledger.account.AccountRepository;
import io.github.ahmedmmordi.ledgerly.ledger.account.dto.AccountResponse;
import io.github.ahmedmmordi.ledgerly.ledger.account.dto.CreateAccountRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.LedgerEntry;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.LedgerEntryRepository;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.Transfer;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.TransferRepository;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.TransferStatus;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.DepositRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferResponse;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.jspecify.annotations.Nullable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Idempotency Concurrency, Race Condition, & Lifecycle Integration Tests")
class IdempotencyConcurrencyTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("DB_POOL_MAX", () -> 30);
        registry.add("DB_POOL_MIN", () -> 5);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RestClient restClient;

    private static final UUID USER_1_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final UUID USER_2_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000002");
    private static final UUID USER_3_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000003");

    @BeforeEach
    void setUp() {
        idempotencyKeyRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    record HttpResponse<T>(HttpStatusCode status, HttpHeaders headers, @Nullable T body,
            @Nullable ProblemDetail problem) {

        boolean is2xx() {
            return status != null && status.is2xxSuccessful();
        }
    }

    private <T> HttpResponse<T> postJson(String uri, Object body, Class<T> responseType,
            @Nullable String idempotencyKey) {
        RestClient.RequestBodySpec spec = restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body);
        if (idempotencyKey != null) {
            spec.header(IdempotencyService.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return spec.exchange((_, response) -> {
            HttpStatusCode status = response.getStatusCode();
            HttpHeaders headers = response.getHeaders();
            if (status.is2xxSuccessful()) {
                return new HttpResponse<>(status, headers, response.bodyTo(responseType), null);
            } else {
                return new HttpResponse<>(status, headers, null, response.bodyTo(ProblemDetail.class));
            }
        });
    }

    private <T> HttpResponse<T> postJson(String uri, Object body, Class<T> responseType) {
        return postJson(uri, body, responseType, null);
    }

    private HttpResponse<AccountResponse> getAccount(UUID accountId) {
        return restClient.get().uri("/api/v1/accounts/" + accountId).exchange((_, response) -> {
            HttpStatusCode status = response.getStatusCode();
            HttpHeaders headers = response.getHeaders();
            if (status.is2xxSuccessful()) {
                return new HttpResponse<>(status, headers, response.bodyTo(AccountResponse.class), null);
            } else {
                return new HttpResponse<>(status, headers, null, response.bodyTo(ProblemDetail.class));
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private void runConcurrently(int taskCount, ThrowingRunnable task) throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(taskCount);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        task.run();
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }
            startGate.countDown();
            boolean completed = finishGate.await(20, TimeUnit.SECONDS);
            assertThat(completed).as("All %d concurrent tasks must complete within 20s", taskCount).isTrue();
        }

        assertThat(errors).as("No unexpected exceptions should be thrown by concurrent tasks").isEmpty();
    }

    private AccountResponse createCustomerAccount(UUID userId) {
        HttpResponse<AccountResponse> response = postJson("/api/v1/accounts", new CreateAccountRequest(userId, "USD"),
                AccountResponse.class);
        assertThat(response.is2xx()).isTrue();
        assertThat(response.body()).isNotNull();
        return response.body();
    }

    private void depositFunds(UUID accountId, long amountInMinorUnits, UUID initiatedByUserId) {
        DepositRequest request = new DepositRequest(accountId, amountInMinorUnits, "USD", "Test deposit",
                initiatedByUserId);
        HttpResponse<TransferResponse> response = postJson("/api/v1/transfers/deposit", request,
                TransferResponse.class);
        assertThat(response.is2xx()).isTrue();
    }

    private void assertAccountBalance(UUID accountId, long expectedMinorUnits) {
        HttpResponse<AccountResponse> response = getAccount(accountId);
        assertThat(response.is2xx()).isTrue();
        assertThat(response.body()).isNotNull();
        assertThat(response.body().balanceInMinorUnits()).isEqualTo(expectedMinorUnits);
    }

    @Test
    @DisplayName("executes exactly once when 20 parallel requests send the identical idempotency key")
    void shouldExecuteTransferExactlyOnceUnderHighConcurrentDuplicateRequests() throws Exception {
        AccountResponse accountA = createCustomerAccount(USER_1_ID);
        AccountResponse accountB = createCustomerAccount(USER_2_ID);
        depositFunds(accountA.id(), 100_000L, USER_1_ID);

        String idempotencyKey = "idem-concurrent-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(accountA.id(), accountB.id(), 10_000L, "USD", "Split Dinner",
                USER_1_ID);

        ConcurrentLinkedQueue<HttpResponse<TransferResponse>> responses = new ConcurrentLinkedQueue<>();
        runConcurrently(20,
                () -> responses.add(postJson("/api/v1/transfers", request, TransferResponse.class, idempotencyKey)));

        assertThat(responses).hasSize(20);

        List<HttpResponse<TransferResponse>> createdResponses = responses.stream()
                .filter(r -> r.status() == HttpStatus.CREATED).toList();
        List<HttpResponse<TransferResponse>> conflictResponses = responses.stream()
                .filter(r -> r.status() == HttpStatus.CONFLICT).toList();

        assertThat(createdResponses.size() + conflictResponses.size()).isEqualTo(20);
        assertThat(createdResponses).isNotEmpty();

        assertThat(conflictResponses).allSatisfy(conflict -> {
            ProblemDetail problem = conflict.problem();
            assertThat(problem).isNotNull();
            assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:idempotency-conflict"));
            assertThat(problem.getTitle()).isEqualTo("Idempotency Conflict");
            assertThat(problem.getProperties()).containsEntry("idempotencyKey", idempotencyKey);
        });

        TransferResponse canonicalTransfer = createdResponses.getFirst().body();
        assertThat(canonicalTransfer).isNotNull();
        assertThat(canonicalTransfer.amountInMinorUnits()).isEqualTo(10_000L);
        assertThat(canonicalTransfer.fromAccountId()).isEqualTo(accountA.id());
        assertThat(canonicalTransfer.toAccountId()).isEqualTo(accountB.id());
        assertThat(canonicalTransfer.status()).isEqualTo(TransferStatus.COMPLETED);

        assertThat(createdResponses).allSatisfy(created -> {
            TransferResponse body = created.body();
            assertThat(body).isNotNull();
            assertThat(body.id()).isEqualTo(canonicalTransfer.id());
            assertThat(body.amountInMinorUnits()).isEqualTo(canonicalTransfer.amountInMinorUnits());
        });

        long nonCachedCount = createdResponses.stream().filter(r -> r.headers().getFirst("X-Served-From-Cache") == null)
                .count();
        long cachedCount = createdResponses.stream()
                .filter(r -> "true".equalsIgnoreCase(r.headers().getFirst("X-Served-From-Cache"))).count();
        assertThat(nonCachedCount).as("Exactly 1 request must be the original non-cached execution").isEqualTo(1);
        assertThat(nonCachedCount + cachedCount).isEqualTo(createdResponses.size());

        // Database Invariants: exactly 1 customer transfer
        List<Transfer> allTransfers = transferRepository.findAll();
        List<Transfer> customerTransfers = allTransfers.stream().filter(t -> t.getFromAccountId().equals(accountA.id()))
                .toList();
        assertThat(customerTransfers).hasSize(1);
        Transfer savedTransfer = customerTransfers.getFirst();
        assertThat(savedTransfer.getId()).isEqualTo(canonicalTransfer.id());
        assertThat(savedTransfer.getAmount().amountInMinorUnits()).isEqualTo(10_000L);
        assertThat(savedTransfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);

        // Balance assertions: debited and credited exactly once
        assertAccountBalance(accountA.id(), 90_000L);
        assertAccountBalance(accountB.id(), 10_000L);

        // Ledger entries: exactly 2 entries (1 DEBIT, 1 CREDIT) with deep invariant checks
        List<LedgerEntry> transferEntries = ledgerEntryRepository.findByTransferIdOrderByTypeAsc(savedTransfer.getId());
        assertThat(transferEntries).hasSize(2);
        LedgerEntry debit = transferEntries.stream().filter(LedgerEntry::isDebit).findFirst().orElseThrow();
        LedgerEntry credit = transferEntries.stream().filter(LedgerEntry::isCredit).findFirst().orElseThrow();
        assertThat(debit.getAccountId()).isEqualTo(accountA.id());
        assertThat(debit.getAmount()).isEqualTo(10_000L);
        assertThat(debit.getBalanceAfter()).isEqualTo(90_000L);
        assertThat(credit.getAccountId()).isEqualTo(accountB.id());
        assertThat(credit.getAmount()).isEqualTo(10_000L);
        assertThat(credit.getBalanceAfter()).isEqualTo(10_000L);

        // Idempotency key stored in DB with COMPLETED status
        IdempotencyKey keyRecord = idempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_1_ID, idempotencyKey)
                .orElseThrow();
        assertThat(keyRecord.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(keyRecord.getResponseStatus()).isEqualTo(201);
        assertThat(keyRecord.getResponseBody()).isNotBlank();
    }

    @Test
    @DisplayName("returns cached response with X-Served-From-Cache header on sequential replay")
    void shouldReturnCachedResponseOnSequentialReplay() {
        AccountResponse accountA = createCustomerAccount(USER_1_ID);
        AccountResponse accountB = createCustomerAccount(USER_2_ID);
        depositFunds(accountA.id(), 50_000L, USER_1_ID);

        String idempotencyKey = "idem-seq-replay-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(accountA.id(), accountB.id(), 5_000L, "USD",
                "Coffee Reimbursement", USER_1_ID);

        // Initial execution
        HttpResponse<TransferResponse> initialResponse = postJson("/api/v1/transfers", request, TransferResponse.class,
                idempotencyKey);
        assertThat(initialResponse.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(initialResponse.headers().getFirst("X-Served-From-Cache")).isNull();
        assertThat(initialResponse.headers().getLocation()).isNotNull();
        TransferResponse initialBody = initialResponse.body();
        assertThat(initialBody).isNotNull();

        // Sequential replay with an identical key and payload
        HttpResponse<TransferResponse> replayedResponse = postJson("/api/v1/transfers", request, TransferResponse.class,
                idempotencyKey);
        assertThat(replayedResponse.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayedResponse.headers().getFirst("X-Served-From-Cache")).isEqualTo("true");
        assertThat(replayedResponse.headers().getLocation()).isEqualTo(initialResponse.headers().getLocation());
        TransferResponse replayedBody = replayedResponse.body();
        assertThat(replayedBody).isNotNull();
        assertThat(replayedBody.id()).isEqualTo(initialBody.id());
        assertThat(replayedBody.amountInMinorUnits()).isEqualTo(initialBody.amountInMinorUnits());
        assertThat(replayedBody.status()).isEqualTo(initialBody.status());

        // Invariants: balances reflect single transfer, no duplicate entries
        assertAccountBalance(accountA.id(), 45_000L);
        assertAccountBalance(accountB.id(), 5_000L);

        List<Transfer> transfers = transferRepository.findAll().stream()
                .filter(t -> t.getFromAccountId().equals(accountA.id())).toList();
        assertThat(transfers).hasSize(1);
    }

    @Test
    @DisplayName("rejects modified payload with 422 Unprocessable Content when idempotency key is reused")
    void shouldRejectTamperedPayloadWithUnprocessableContent() {
        AccountResponse accountA = createCustomerAccount(USER_1_ID);
        AccountResponse accountB = createCustomerAccount(USER_2_ID);
        AccountResponse accountC = createCustomerAccount(USER_3_ID);
        depositFunds(accountA.id(), 50_000L, USER_1_ID);

        String idempotencyKey = "idem-tamper-" + UUID.randomUUID();
        TransferRequest originalRequest = new TransferRequest(accountA.id(), accountB.id(), 5_000L, "USD",
                "Invoice 101", USER_1_ID);

        // Initial transfer succeeds
        HttpResponse<TransferResponse> initialRes = postJson("/api/v1/transfers", originalRequest,
                TransferResponse.class, idempotencyKey);
        assertThat(initialRes.status()).isEqualTo(HttpStatus.CREATED);

        // Tamper 1: Altered amount (10,000 minor units instead of 5,000)
        TransferRequest alteredAmountRequest = new TransferRequest(accountA.id(), accountB.id(), 10_000L, "USD",
                "Invoice 101", USER_1_ID);
        HttpResponse<TransferResponse> alteredAmountRes = postJson("/api/v1/transfers", alteredAmountRequest,
                TransferResponse.class, idempotencyKey);

        assertThat(alteredAmountRes.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ProblemDetail problem1 = alteredAmountRes.problem();
        assertThat(problem1).isNotNull();
        assertThat(problem1.getType()).isEqualTo(URI.create("urn:problem-type:idempotency-payload-mismatch"));
        assertThat(problem1.getTitle()).isEqualTo("Idempotency Payload Mismatch");
        assertThat(problem1.getDetail()).contains("Payload hash mismatch for idempotency key");
        assertThat(problem1.getProperties()).containsEntry("idempotencyKey", idempotencyKey);

        // Tamper 2: Altered recipient account (accountC instead of accountB)
        TransferRequest alteredRecipientRequest = new TransferRequest(accountA.id(), accountC.id(), 5_000L, "USD",
                "Invoice 101", USER_1_ID);
        HttpResponse<TransferResponse> alteredRecipientRes = postJson("/api/v1/transfers", alteredRecipientRequest,
                TransferResponse.class, idempotencyKey);

        assertThat(alteredRecipientRes.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ProblemDetail problem2 = alteredRecipientRes.problem();
        assertThat(problem2).isNotNull();
        assertThat(problem2.getType()).isEqualTo(URI.create("urn:problem-type:idempotency-payload-mismatch"));
        assertThat(problem2.getProperties()).containsEntry("idempotencyKey", idempotencyKey);

        // Database remains uncorrupted: only 1 transfer executed, balance debited by 5,000 only
        assertAccountBalance(accountA.id(), 45_000L);
        assertAccountBalance(accountB.id(), 5_000L);
        assertAccountBalance(accountC.id(), 0L);
    }

    @Test
    @DisplayName("executes deposit exactly once from system cash vault under concurrent duplicate requests")
    void shouldExecuteDepositExactlyOnceUnderConcurrentDuplicateRequests() throws Exception {
        AccountResponse customerAccount = createCustomerAccount(USER_1_ID);
        String idempotencyKey = "idem-deposit-" + UUID.randomUUID();

        DepositRequest request = new DepositRequest(customerAccount.id(), 25_000L, "USD", "Vault Deposit", USER_1_ID);

        ConcurrentLinkedQueue<HttpResponse<TransferResponse>> responses = new ConcurrentLinkedQueue<>();
        runConcurrently(15, () -> responses
                .add(postJson("/api/v1/transfers/deposit", request, TransferResponse.class, idempotencyKey)));

        assertThat(responses).hasSize(15);

        List<HttpResponse<TransferResponse>> createdResponses = responses.stream()
                .filter(r -> r.status() == HttpStatus.CREATED).toList();
        List<HttpResponse<TransferResponse>> conflictResponses = responses.stream()
                .filter(r -> r.status() == HttpStatus.CONFLICT).toList();

        assertThat(createdResponses.size() + conflictResponses.size()).isEqualTo(15);
        assertThat(createdResponses).isNotEmpty();

        assertThat(conflictResponses).allSatisfy(conflict -> {
            assertThat(conflict.problem()).isNotNull();
            assertThat(conflict.problem().getType()).isEqualTo(URI.create("urn:problem-type:idempotency-conflict"));
        });

        TransferResponse canonicalDeposit = createdResponses.getFirst().body();
        assertThat(canonicalDeposit).isNotNull();
        assertThat(canonicalDeposit.amountInMinorUnits()).isEqualTo(25_000L);
        assertThat(canonicalDeposit.toAccountId()).isEqualTo(customerAccount.id());
        assertThat(canonicalDeposit.status()).isEqualTo(TransferStatus.COMPLETED);

        assertThat(createdResponses).allSatisfy(created -> {
            TransferResponse body = created.body();
            assertThat(body).isNotNull();
            assertThat(body.id()).isEqualTo(canonicalDeposit.id());
            assertThat(body.amountInMinorUnits()).isEqualTo(canonicalDeposit.amountInMinorUnits());
        });

        long nonCachedCount = createdResponses.stream().filter(r -> r.headers().getFirst("X-Served-From-Cache") == null)
                .count();
        long cachedCount = createdResponses.stream()
                .filter(r -> "true".equalsIgnoreCase(r.headers().getFirst("X-Served-From-Cache"))).count();
        assertThat(nonCachedCount).as("Exactly 1 deposit must be the original execution").isEqualTo(1);
        assertThat(nonCachedCount + cachedCount).isEqualTo(createdResponses.size());

        // Account balance is credited exactly once: $250.00
        assertAccountBalance(customerAccount.id(), 25_000L);

        // Exactly 1 deposit transfer in the repository
        List<Transfer> transfers = transferRepository.findAll().stream()
                .filter(t -> t.getToAccountId().equals(customerAccount.id())).toList();
        assertThat(transfers).hasSize(1);

        // Key is marked COMPLETED
        IdempotencyKey keyRecord = idempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_1_ID, idempotencyKey)
                .orElseThrow();
        assertThat(keyRecord.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("allows safe retry with same idempotency key after domain validation failure")
    void shouldAllowSafeRetryAfterDomainFailure() {
        AccountResponse accountA = createCustomerAccount(USER_1_ID);
        AccountResponse accountB = createCustomerAccount(USER_2_ID);
        depositFunds(accountA.id(), 1_000L, USER_1_ID); // Balance: $10.00

        String idempotencyKey = "idem-retry-fail-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(accountA.id(), accountB.id(), 5_000L, "USD", "Attempt Overdraw",
                USER_1_ID);

        // Step 1: An initial attempt fails with 422 Insufficient Funds
        HttpResponse<TransferResponse> failedRes = postJson("/api/v1/transfers", request, TransferResponse.class,
                idempotencyKey);
        assertThat(failedRes.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(failedRes.problem()).isNotNull();
        assertThat(failedRes.problem().getTitle()).isEqualTo("Insufficient Funds");

        // Key in DB is transitioned to FAILED status
        IdempotencyKey failedKey = idempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_1_ID, idempotencyKey)
                .orElseThrow();
        assertThat(failedKey.getStatus()).isEqualTo(IdempotencyStatus.FAILED);

        // Step 2: Fund Account A with $100.00 (10,000 minor units)
        depositFunds(accountA.id(), 10_000L, USER_1_ID);

        // Step 3: Retry with the EXACT SAME idempotency key and payload
        HttpResponse<TransferResponse> retryRes = postJson("/api/v1/transfers", request, TransferResponse.class,
                idempotencyKey);
        assertThat(retryRes.status()).isEqualTo(HttpStatus.CREATED);
        TransferResponse retryBody = retryRes.body();
        assertThat(retryBody).isNotNull();
        assertThat(retryBody.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(retryBody.amountInMinorUnits()).isEqualTo(5_000L);

        // Key in DB is now COMPLETED
        IdempotencyKey completedKey = idempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_1_ID, idempotencyKey)
                .orElseThrow();
        assertThat(completedKey.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(completedKey.getResponseStatus()).isEqualTo(201);

        // Final balances: Account A ($10 + $100 - $50 = $60.00), Account B ($50.00)
        assertAccountBalance(accountA.id(), 6_000L);
        assertAccountBalance(accountB.id(), 5_000L);
    }

    @Test
    @DisplayName("allows re-execution after key expiration threshold has passed")
    void shouldAllowReExecutionAfterKeyExpiration() {
        AccountResponse accountA = createCustomerAccount(USER_1_ID);
        AccountResponse accountB = createCustomerAccount(USER_2_ID);
        depositFunds(accountA.id(), 100_000L, USER_1_ID);

        String idempotencyKey = "idem-expired-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(accountA.id(), accountB.id(), 3_000L, "USD", "Recurring Fee",
                USER_1_ID);

        // Execution 1: The first transfer succeeds
        HttpResponse<TransferResponse> firstRes = postJson("/api/v1/transfers", request, TransferResponse.class,
                idempotencyKey);
        assertThat(firstRes.status()).isEqualTo(HttpStatus.CREATED);
        TransferResponse firstBody = firstRes.body();
        assertThat(firstBody).isNotNull();

        // Age key artificially to 2 hours in the past
        Instant pastExpiration = Instant.now().minus(2, ChronoUnit.HOURS);
        int rowsUpdated = jdbcTemplate.update(
                "UPDATE idempotency_keys SET expires_at = ? WHERE user_id = ? AND idempotency_key = ?",
                Timestamp.from(pastExpiration), USER_1_ID, idempotencyKey);
        assertThat(rowsUpdated).isEqualTo(1);

        // Execution 2: Re-send with an identical key after expiration
        HttpResponse<TransferResponse> secondRes = postJson("/api/v1/transfers", request, TransferResponse.class,
                idempotencyKey);
        assertThat(secondRes.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(secondRes.headers().getFirst("X-Served-From-Cache")).isNull();
        TransferResponse secondBody = secondRes.body();
        assertThat(secondBody).isNotNull();
        assertThat(secondBody.id()).isNotEqualTo(firstBody.id());

        // Invariants: 2 distinct transfers exist, balance debited twice (6,000 total)
        List<Transfer> transfers = transferRepository.findAll().stream()
                .filter(t -> t.getFromAccountId().equals(accountA.id())).toList();
        assertThat(transfers).hasSize(2);

        assertAccountBalance(accountA.id(), 94_000L);

        // Key in DB has renewed future expiration
        IdempotencyKey renewedKey = idempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_1_ID, idempotencyKey)
                .orElseThrow();
        assertThat(renewedKey.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("allows independent execution when different users use the same idempotency key")
    void shouldAllowIndependentExecutionForDifferentUsersWithSameKey() {
        AccountResponse account1 = createCustomerAccount(USER_1_ID);
        AccountResponse account2 = createCustomerAccount(USER_2_ID);
        AccountResponse commonTarget = createCustomerAccount(USER_3_ID);

        depositFunds(account1.id(), 50_000L, USER_1_ID);
        depositFunds(account2.id(), 50_000L, USER_2_ID);

        String sharedKey = "shared-idempotency-key-" + UUID.randomUUID();

        // User 1 executes transfer with sharedKey
        TransferRequest request1 = new TransferRequest(account1.id(), commonTarget.id(), 4_000L, "USD", "From User 1",
                USER_1_ID);
        HttpResponse<TransferResponse> res1 = postJson("/api/v1/transfers", request1, TransferResponse.class,
                sharedKey);
        assertThat(res1.status()).isEqualTo(HttpStatus.CREATED);
        TransferResponse body1 = res1.body();
        assertThat(body1).isNotNull();

        // User 2 executes transfer with the EXACT SAME key name
        TransferRequest request2 = new TransferRequest(account2.id(), commonTarget.id(), 7_000L, "USD", "From User 2",
                USER_2_ID);
        HttpResponse<TransferResponse> res2 = postJson("/api/v1/transfers", request2, TransferResponse.class,
                sharedKey);
        assertThat(res2.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(res2.headers().getFirst("X-Served-From-Cache")).isNull();
        TransferResponse body2 = res2.body();
        assertThat(body2).isNotNull();
        assertThat(body2.id()).isNotEqualTo(body1.id());

        // Invariants: 2 keys exist in the database, 2 distinct transfers, the target receives both
        List<IdempotencyKey> allKeys = idempotencyKeyRepository.findAll().stream()
                .filter(k -> k.getIdempotencyKey().equals(sharedKey)).toList();
        assertThat(allKeys).hasSize(2);

        assertAccountBalance(commonTarget.id(), 11_000L);
    }

    @Test
    @DisplayName("bypasses idempotency engine completely when header is omitted or whitespace")
    void shouldBypassIdempotencyWhenHeaderIsOmittedOrWhitespace() {
        AccountResponse accountA = createCustomerAccount(USER_1_ID);
        AccountResponse accountB = createCustomerAccount(USER_2_ID);
        depositFunds(accountA.id(), 50_000L, USER_1_ID);

        TransferRequest request = new TransferRequest(accountA.id(), accountB.id(), 1_000L, "USD", "Bypass Test",
                USER_1_ID);

        // 1. Header omitted
        HttpResponse<TransferResponse> res1 = postJson("/api/v1/transfers", request, TransferResponse.class, null);
        assertThat(res1.status()).isEqualTo(HttpStatus.CREATED);
        TransferResponse body1 = res1.body();
        assertThat(body1).isNotNull();

        // 2. Header omitted again with an identical payload -> generates a new transfer
        HttpResponse<TransferResponse> res2 = postJson("/api/v1/transfers", request, TransferResponse.class, null);
        assertThat(res2.status()).isEqualTo(HttpStatus.CREATED);
        TransferResponse body2 = res2.body();
        assertThat(body2).isNotNull();
        assertThat(body2.id()).isNotEqualTo(body1.id());

        // 3. Header blank/whitespace -> generates a new transfer
        HttpResponse<TransferResponse> res3 = postJson("/api/v1/transfers", request, TransferResponse.class, "   ");
        assertThat(res3.status()).isEqualTo(HttpStatus.CREATED);
        TransferResponse body3 = res3.body();
        assertThat(body3).isNotNull();
        assertThat(body3.id()).isNotEqualTo(body2.id());

        // Invariants: 3 transfers executed, 0 idempotency keys stored
        List<Transfer> transfers = transferRepository.findAll().stream()
                .filter(t -> t.getFromAccountId().equals(accountA.id())).toList();
        assertThat(transfers).hasSize(3);

        List<IdempotencyKey> keys = idempotencyKeyRepository.findAll();
        assertThat(keys).isEmpty();
    }
}
