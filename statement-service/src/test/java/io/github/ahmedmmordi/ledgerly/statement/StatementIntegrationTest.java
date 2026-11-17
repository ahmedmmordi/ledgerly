package io.github.ahmedmmordi.ledgerly.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.ahmedmmordi.ledgerly.common.event.TransferCompletedEvent;
import io.github.ahmedmmordi.ledgerly.statement.statement.Statement;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementEntry;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementEntryType;
import io.github.ahmedmmordi.ledgerly.statement.statement.StatementRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

@NullMarked
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Statement Service End-to-End Integration Tests")
class StatementIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");

    private static @Nullable KafkaProducer<String, String> producer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> mongo.getConnectionString() + "/statement_db");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("statement.kafka.consumer.group-id", () -> "statement-it-" + UUID.randomUUID());
    }

    @BeforeAll
    static void initProducer() throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        try (AdminClient adminClient = AdminClient.create(props)) {
            adminClient.createTopics(List.of(new NewTopic("ledger.transfers.v1", 1, (short) 1),
                    new NewTopic("ledger.transfers.v1.DLT", 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }

        producer = new KafkaProducer<>(props);
    }

    @AfterAll
    static void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Value("${statement.kafka.topics.transfers:ledger.transfers.v1}")
    private String topic = "ledger.transfers.v1";

    @Autowired
    private StatementRepository statementRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        statementRepository.deleteAll();
    }

    private void publishTransferEvent(TransferCompletedEvent event) throws Exception {
        String payload = objectMapper.writeValueAsString(event);
        Objects.requireNonNull(producer, "producer must not be null")
                .send(new ProducerRecord<>(topic, event.transferId().toString(), payload)).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should consume TransferCompletedEvent and accumulate balances for sender and recipient")
    void shouldConsumeTransferEventAndAccumulateBalances() throws Exception {
        UUID fromAccount = UUID.randomUUID();
        UUID toAccount = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-15T10:00:00Z");

        TransferCompletedEvent event = new TransferCompletedEvent(UUID.randomUUID(), transferId, fromAccount, toAccount,
                5000L, "USD", now, 1);

        publishTransferEvent(event);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Optional<Statement> fromOpt = statementRepository.findByAccountIdAndYearMonth(fromAccount, "2026-03");
            Optional<Statement> toOpt = statementRepository.findByAccountIdAndYearMonth(toAccount, "2026-03");

            assertThat(fromOpt).isPresent();
            assertThat(toOpt).isPresent();

            Statement fromStatement = fromOpt.get();
            assertThat(fromStatement.getOpeningBalanceMinorUnits()).isEqualTo(0L);
            assertThat(fromStatement.getClosingBalanceMinorUnits()).isEqualTo(-5000L);
            assertThat(fromStatement.getCurrency()).isEqualTo("USD");
            assertThat(fromStatement.getEntries()).hasSize(1);
            StatementEntry debitEntry = fromStatement.getEntries().getFirst();
            assertThat(debitEntry.type()).isEqualTo(StatementEntryType.DEBIT);
            assertThat(debitEntry.amountMinorUnits()).isEqualTo(5000L);
            assertThat(debitEntry.balanceAfterMinorUnits()).isEqualTo(-5000L);
            assertThat(debitEntry.transferId()).isEqualTo(transferId);

            Statement toStatement = toOpt.get();
            assertThat(toStatement.getOpeningBalanceMinorUnits()).isEqualTo(0L);
            assertThat(toStatement.getClosingBalanceMinorUnits()).isEqualTo(5000L);
            assertThat(toStatement.getCurrency()).isEqualTo("USD");
            assertThat(toStatement.getEntries()).hasSize(1);
            StatementEntry creditEntry = toStatement.getEntries().getFirst();
            assertThat(creditEntry.type()).isEqualTo(StatementEntryType.CREDIT);
            assertThat(creditEntry.amountMinorUnits()).isEqualTo(5000L);
            assertThat(creditEntry.balanceAfterMinorUnits()).isEqualTo(5000L);
            assertThat(creditEntry.transferId()).isEqualTo(transferId);
        });
    }

    @Test
    @DisplayName("Should skip duplicate event processing when same eventId is replayed")
    void shouldGuaranteeIdempotencyWhenEventIsReplayed() throws Exception {
        UUID fromAccount = UUID.randomUUID();
        UUID toAccount = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-15T12:00:00Z");

        TransferCompletedEvent event = new TransferCompletedEvent(eventId, transferId, fromAccount, toAccount, 2500L,
                "USD", now, 1);

        publishTransferEvent(event);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Optional<Statement> opt = statementRepository.findByAccountIdAndYearMonth(toAccount, "2026-03");
            assertThat(opt).isPresent();
            assertThat(opt.get().getClosingBalanceMinorUnits()).isEqualTo(2500L);
        });

        // Replay exact same event
        publishTransferEvent(event);

        // Allow some time and verify still exactly 1 entry and balance is unchanged
        Thread.sleep(1000);
        Statement toStatement = statementRepository.findByAccountIdAndYearMonth(toAccount, "2026-03").orElseThrow();
        assertThat(toStatement.getEntries()).hasSize(1);
        assertThat(toStatement.getClosingBalanceMinorUnits()).isEqualTo(2500L);

        Statement fromStatement = statementRepository.findByAccountIdAndYearMonth(fromAccount, "2026-03").orElseThrow();
        assertThat(fromStatement.getEntries()).hasSize(1);
        assertThat(fromStatement.getClosingBalanceMinorUnits()).isEqualTo(-2500L);
    }

    @Test
    @DisplayName("Should carry forward previous month closing balance into new month opening balance")
    void shouldCarryForwardBalanceOnMonthRollover() throws Exception {
        UUID customerAccount = UUID.randomUUID();
        UUID peerAccount = UUID.randomUUID();

        // Month 1: 2026-01 -> Receive 4000 minor units ($40.00)
        TransferCompletedEvent eventJan = new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), peerAccount,
                customerAccount, 4000L, "USD", Instant.parse("2026-01-20T10:00:00Z"), 1);

        publishTransferEvent(eventJan);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Optional<Statement> janOpt = statementRepository.findByAccountIdAndYearMonth(customerAccount, "2026-01");
            assertThat(janOpt).isPresent();
            assertThat(janOpt.get().getClosingBalanceMinorUnits()).isEqualTo(4000L);
        });

        // Month 2: 2026-02 -> Send 1500 minor units ($15.00)
        TransferCompletedEvent eventFeb = new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(),
                customerAccount, peerAccount, 1500L, "USD", Instant.parse("2026-02-10T14:30:00Z"), 1);

        publishTransferEvent(eventFeb);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Optional<Statement> febOpt = statementRepository.findByAccountIdAndYearMonth(customerAccount, "2026-02");
            assertThat(febOpt).isPresent();
            Statement febStatement = febOpt.get();
            assertThat(febStatement.getOpeningBalanceMinorUnits()).isEqualTo(4000L);
            assertThat(febStatement.getClosingBalanceMinorUnits()).isEqualTo(2500L);
            assertThat(febStatement.getEntries()).hasSize(1);
            assertThat(febStatement.getEntries().getFirst().balanceAfterMinorUnits()).isEqualTo(2500L);
        });
    }

    @Test
    @DisplayName("Should not create statement for cash vault on deposit transfers")
    void shouldNotCreateStatementForCashVaultOnDeposit() throws Exception {
        UUID cashVaultId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        UUID customerAccount = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-01T09:00:00Z");

        TransferCompletedEvent depositEvent = new TransferCompletedEvent(UUID.randomUUID(), transferId, cashVaultId,
                customerAccount, 10000L, "USD", now, 1);

        publishTransferEvent(depositEvent);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Optional<Statement> customerOpt = statementRepository.findByAccountIdAndYearMonth(customerAccount,
                    "2026-04");
            assertThat(customerOpt).isPresent();
            assertThat(customerOpt.get().getClosingBalanceMinorUnits()).isEqualTo(10000L);
        });

        // Verify no statement was created for the cash vault
        Optional<Statement> vaultOpt = statementRepository.findByAccountIdAndYearMonth(cashVaultId, "2026-04");
        assertThat(vaultOpt).isEmpty();
        List<Statement> allVaultStatements = statementRepository.findByAccountIdOrderByYearMonthDesc(cashVaultId);
        assertThat(allVaultStatements).isEmpty();
    }

    @Test
    @DisplayName("Should query accumulated statements and stream CSV export via REST endpoints")
    void shouldQueryStatementsAndStreamCsvViaRestEndpoints() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID otherAccount = UUID.randomUUID();

        // 2026-05: Credit 5000 USD ($50.00)
        publishTransferEvent(new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), otherAccount, accountId,
                5000L, "USD", Instant.parse("2026-05-10T10:00:00Z"), 1));

        // 2026-06: Debit 2000 USD ($20.00)
        publishTransferEvent(new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), accountId, otherAccount,
                2000L, "USD", Instant.parse("2026-06-15T15:00:00Z"), 1));

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(
                () -> assertThat(statementRepository.findByAccountIdAndYearMonth(accountId, "2026-06")).isPresent());

        RestClient restClient = RestClient.create("http://localhost:" + port);

        // 1. Query JSON REST endpoint: GET /api/v1/accounts/{accountId}/statements?from=2026-05&to=2026-06
        String jsonResponse = restClient.get()
                .uri("/api/v1/accounts/{accountId}/statements?from=2026-05&to=2026-06", accountId)
                .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);

        assertThat(jsonResponse).isNotNull();
        assertThat(jsonResponse).contains("\"totalElements\":2");
        assertThat(jsonResponse).contains("\"currency\":\"USD\"");
        assertThat(jsonResponse).contains("\"yearMonth\":\"2026-06\"");
        assertThat(jsonResponse).contains("\"closingBalanceMajorUnits\":30.00");
        assertThat(jsonResponse).contains("\"yearMonth\":\"2026-05\"");
        assertThat(jsonResponse).contains("\"closingBalanceMajorUnits\":50.00");

        // 2. Query CSV REST endpoint: GET /api/v1/accounts/{accountId}/statements.csv?from=2026-05&to=2026-06
        ResponseEntity<String> csvResponse = restClient.get()
                .uri("/api/v1/accounts/{accountId}/statements.csv?from=2026-05&to=2026-06", accountId).retrieve()
                .toEntity(String.class);

        assertThat(csvResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csvResponse.getHeaders().getContentType()).isNotNull();
        assertThat(csvResponse.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(csvResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment; filename=\"statements.csv\"");
        assertThat(csvResponse.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        String csvBody = csvResponse.getBody();
        assertThat(csvBody).isNotNull();
        assertThat(csvBody).startsWith("date,type,amount,currency,balanceAfter\n");
        assertThat(csvBody).contains("CREDIT,50.00,USD,50.00");
        assertThat(csvBody).contains("DEBIT,20.00,USD,30.00");
    }
}
