package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountRepository;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.outbox.OutboxEventRepository;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.LedgerEntryResponse;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferResponse;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@NullMarked
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@DisplayName("QueryCount and N+1 Prevention Tests")
@SuppressWarnings("null")
class QueryCountTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("ledger.outbox.poller.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private RestClient restClient;
    private Statistics statistics;

    private UUID userId;
    private UUID fromAccountId;
    private UUID toAccountId;
    private UUID sampleTransferId;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PageResponse<T>(List<T> content, int totalPages, long totalElements, int size, int number) {
    }

    private static String generateUniqueAccountNumber() {
        return "ACC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    @BeforeEach
    void setUp() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);

        ledgerEntryRepository.deleteAll();
        outboxEventRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();

        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();

        userId = UUID.randomUUID();
        Account fromAccount = accountRepository
                .save(Account.createCustomer(userId, generateUniqueAccountNumber(), "USD"));
        Account toAccount = accountRepository
                .save(Account.createCustomer(userId, generateUniqueAccountNumber(), "USD"));
        fromAccountId = fromAccount.getId();
        toAccountId = toAccount.getId();

        transferService.deposit(fromAccountId, Money.ofMinor(100_000L, "USD"), "Initial funding", userId);

        Transfer firstTransfer = null;
        for (int i = 0; i < 10; i++) {
            Transfer t = transferService.transfer(fromAccountId, toAccountId, Money.ofMinor(1000L, "USD"),
                    "Transfer " + i, userId);
            if (firstTransfer == null) {
                firstTransfer = t;
            }
        }
        sampleTransferId = firstTransfer.getId();

        statistics.clear();
    }

    @Test
    @DisplayName("Hibernate default_batch_fetch_size is configured to 20")
    void defaultBatchFetchSizeIsConfigured() {
        Object batchSize = entityManagerFactory.getProperties().get("hibernate.default_batch_fetch_size");
        assertThat(String.valueOf(batchSize)).isEqualTo("20");
    }

    @Test
    @DisplayName("Paginated transfers by accountId executes constant queries (1 count + 1 select)")
    void paginatedTransfersByAccountIdExecutesConstantQueries() {
        statistics.clear();

        PageResponse<TransferResponse> response = restClient.get()
                .uri("/api/v1/transfers?accountId=" + fromAccountId + "&page=0&size=5")
                .accept(MediaType.APPLICATION_JSON).exchange((_, res) -> {
                    assertThat(res.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
                    return res.bodyTo(new ParameterizedTypeReference<>() {
                    });
                });

        assertThat(response).isNotNull();
        assertThat(response.content()).hasSize(5);
        assertThat(response.totalElements()).isEqualTo(11);

        assertThat(statistics.getPrepareStatementCount())
                .as("Paginated query should execute exactly 2 statements: 1 count and 1 page select").isEqualTo(2);
    }

    @Test
    @DisplayName("Paginated transfers by userId executes constant queries (1 count + 1 select)")
    void paginatedTransfersByUserIdExecutesConstantQueries() {
        statistics.clear();

        PageResponse<TransferResponse> response = restClient.get()
                .uri("/api/v1/transfers?userId=" + userId + "&page=0&size=5").accept(MediaType.APPLICATION_JSON)
                .exchange((_, res) -> {
                    assertThat(res.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
                    return res.bodyTo(new ParameterizedTypeReference<>() {
                    });
                });

        assertThat(response).isNotNull();
        assertThat(response.content()).hasSize(5);
        assertThat(response.totalElements()).isEqualTo(11);

        assertThat(statistics.getPrepareStatementCount())
                .as("Paginated query by userId should execute exactly 2 statements: 1 count and 1 page select")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Single transfer retrieval by ID executes exactly 1 query")
    void singleTransferByIdExecutesExactlyOneQuery() {
        statistics.clear();

        TransferResponse response = restClient.get().uri("/api/v1/transfers/" + sampleTransferId)
                .accept(MediaType.APPLICATION_JSON).exchange((_, res) -> {
                    assertThat(res.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
                    return res.bodyTo(TransferResponse.class);
                });

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(sampleTransferId);

        assertThat(statistics.getPrepareStatementCount()).as("Single entity lookup should execute exactly 1 statement")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Transfer ledger entries endpoint executes exactly 2 queries without N+1 loading")
    void transferLedgerEntriesExecutesExactlyTwoQueries() {
        statistics.clear();

        List<LedgerEntryResponse> entries = restClient.get().uri("/api/v1/transfers/" + sampleTransferId + "/entries")
                .accept(MediaType.APPLICATION_JSON).exchange((_, res) -> {
                    assertThat(res.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
                    return res.bodyTo(new ParameterizedTypeReference<>() {
                    });
                });

        assertThat(entries).isNotNull().hasSize(2);

        assertThat(statistics.getPrepareStatementCount())
                .as("Retrieving transfer and its 2 ledger entries should execute exactly 2 statements").isEqualTo(2);
    }

    @Test
    @DisplayName("Paginated transfers for recipient account executes constant queries (1 count + 1 select)")
    void paginatedTransfersForRecipientAccountExecutesConstantQueries() {
        statistics.clear();

        PageResponse<TransferResponse> response = restClient.get()
                .uri("/api/v1/transfers?accountId=" + toAccountId + "&page=0&size=5").accept(MediaType.APPLICATION_JSON)
                .exchange((_, res) -> {
                    assertThat(res.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
                    return res.bodyTo(new ParameterizedTypeReference<>() {
                    });
                });

        assertThat(response).isNotNull();
        assertThat(response.content()).hasSize(5);
        assertThat(response.totalElements()).isEqualTo(10);

        assertThat(statistics.getPrepareStatementCount())
                .as("Recipient account query should execute exactly 2 statements: 1 count and 1 page select")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Account with zero transfers executes at most 1 count query and returns empty page")
    void accountWithZeroTransfersExecutesAtMostOneQuery() {
        Account emptyAccount = accountRepository
                .save(Account.createCustomer(userId, generateUniqueAccountNumber(), "USD"));
        statistics.clear();

        PageResponse<TransferResponse> response = restClient.get()
                .uri("/api/v1/transfers?accountId=" + emptyAccount.getId() + "&page=0&size=20")
                .accept(MediaType.APPLICATION_JSON).exchange((_, res) -> {
                    assertThat(res.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
                    return res.bodyTo(new ParameterizedTypeReference<>() {
                    });
                });

        assertThat(response).isNotNull();
        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();

        assertThat(statistics.getPrepareStatementCount())
                .as("Empty account count query should not trigger unnecessary page select queries")
                .isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Missing filter parameters fails fast with 400 Bad Request and zero database queries")
    void missingFilterParametersFailsFastWithZeroQueries() {
        statistics.clear();

        restClient.get().uri("/api/v1/transfers").accept(MediaType.APPLICATION_JSON).exchange((_, res) -> {
            assertThat(res.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400));
            return null;
        });

        assertThat(statistics.getPrepareStatementCount())
                .as("Validation failure at controller layer should execute zero database queries").isZero();
    }
}
