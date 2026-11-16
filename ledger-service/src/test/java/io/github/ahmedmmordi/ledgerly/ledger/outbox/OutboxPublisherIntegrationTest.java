package io.github.ahmedmmordi.ledgerly.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ahmedmmordi.ledgerly.common.event.TransferCompletedEvent;
import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountRepository;
import io.github.ahmedmmordi.ledgerly.ledger.config.KafkaProducerConfig;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.Transfer;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.TransferService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@NullMarked
@SpringBootTest
@Testcontainers
@DisplayName("OutboxPublisher Integration Tests")
@SuppressWarnings({ "null", "ConstantConditions" })
class OutboxPublisherIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("DB_POOL_MAX", () -> 20);
        registry.add("DB_POOL_MIN", () -> 5);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 30000);
        registry.add("spring.datasource.hikari.connection-test-query", () -> "SELECT 1");
        registry.add("spring.datasource.hikari.validation-timeout", () -> 5000);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("KAFKA_BOOTSTRAP_SERVERS", kafka::getBootstrapServers);
        registry.add("ledger.outbox.poller.fixed-delay-ms", () -> "100");
        registry.add("ledger.outbox.poller.send-timeout-seconds", () -> "15");
    }

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private KafkaConsumer<String, String> testConsumer;

    @BeforeEach
    void setUpConsumer() {
        outboxEventRepository.deleteAll();

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        testConsumer = new KafkaConsumer<>(props);

        await().atMost(Duration.ofSeconds(15)).until(() -> {
            List<PartitionInfo> partitions = testConsumer.partitionsFor(KafkaProducerConfig.TOPIC_TRANSFERS);
            return partitions != null && !partitions.isEmpty();
        });

        List<TopicPartition> topicPartitions = testConsumer.partitionsFor(KafkaProducerConfig.TOPIC_TRANSFERS).stream()
                .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
        testConsumer.assign(topicPartitions);
        testConsumer.seekToEnd(topicPartitions);
        for (TopicPartition tp : topicPartitions) {
            testConsumer.position(tp);
        }
    }

    @AfterEach
    void tearDownConsumer() {
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    private static String generateUniqueAccountNumber() {
        return "ACC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    @Test
    @DisplayName("Transfer triggers outbox record which is polled, published to Kafka, and marked SENT")
    void transferTriggersOutboxAndPublishesToKafka() {
        UUID userId = UUID.randomUUID();
        Account fromAccount = accountRepository
                .save(Account.createCustomer(userId, generateUniqueAccountNumber(), "USD"));
        Account toAccount = accountRepository
                .save(Account.createCustomer(userId, generateUniqueAccountNumber(), "USD"));

        transferService.deposit(fromAccount.getId(), Money.ofMinor(5000L, "USD"), "Initial funding", userId);

        Transfer transfer = transferService.transfer(fromAccount.getId(), toAccount.getId(),
                Money.ofMinor(2000L, "USD"), "Outbox E2E test", userId);

        List<OutboxEvent> events = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                TransferCompletedEvent.AGGREGATE_TYPE, transfer.getId());
        assertThat(events).hasSize(1);
        OutboxEvent initialEvent = events.getFirst();
        UUID outboxEventId = initialEvent.getId();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OutboxEvent polledEvent = outboxEventRepository.findById(outboxEventId).orElseThrow();
            assertThat(polledEvent.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(polledEvent.getSentAt()).isNotNull();
            assertThat(polledEvent.getErrorMessage()).isNull();
            assertThat(polledEvent.getRetryCount()).isZero();
            assertThat(polledEvent.isSent()).isTrue();
            assertThat(polledEvent.isPending()).isFalse();
            assertThat(polledEvent.isFailed()).isFalse();
        });

        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofMillis(200));
            records.forEach(received::add);

            assertThat(received).anySatisfy(record -> {
                assertThat(record.key()).isEqualTo(transfer.getId().toString());
                assertThat(record.topic()).isEqualTo(KafkaProducerConfig.TOPIC_TRANSFERS);
                TransferCompletedEvent event = objectMapper.readValue(record.value(), TransferCompletedEvent.class);
                assertThat(event.transferId()).isEqualTo(transfer.getId());
                assertThat(event.fromAccountId()).isEqualTo(fromAccount.getId());
                assertThat(event.toAccountId()).isEqualTo(toAccount.getId());
                assertThat(event.amountInMinorUnits()).isEqualTo(2000L);
                assertThat(event.currency()).isEqualTo("USD");
                assertThat(event.eventId()).isNotNull();
                assertThat(event.eventId().version()).isEqualTo(4);
                assertThat(event.occurredAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
                assertThat(event.version()).isEqualTo(1);
            });
        });
    }

    @Test
    @DisplayName("Direct outbox event recorded in transaction is polled, published, and marked SENT")
    void directOutboxEventIsPublishedAndMarkedSent() {
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"testKey\":\"testValue\"}";

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(
                _ -> outboxService.recordEvent("TEST_AGGREGATE", aggregateId, "TestEventCompleted", payload));

        List<OutboxEvent> events = outboxEventRepository
                .findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("TEST_AGGREGATE", aggregateId);
        assertThat(events).hasSize(1);
        UUID outboxEventId = events.getFirst().getId();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            OutboxEvent polledEvent = outboxEventRepository.findById(outboxEventId).orElseThrow();
            assertThat(polledEvent.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(polledEvent.getSentAt()).isNotNull();
            assertThat(polledEvent.getErrorMessage()).isNull();
            assertThat(polledEvent.getRetryCount()).isZero();
            assertThat(polledEvent.isSent()).isTrue();
            assertThat(polledEvent.isPending()).isFalse();
            assertThat(polledEvent.isFailed()).isFalse();
        });

        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofMillis(200));
            records.forEach(received::add);

            assertThat(received).anySatisfy(record -> {
                assertThat(record.key()).isEqualTo(aggregateId.toString());
                assertThat(record.value()).isEqualTo(payload);
            });
        });
    }

    @Test
    @DisplayName("Transaction rollback aborts outbox persistence and nothing is published to Kafka")
    void transactionRollbackAbortsOutboxPersistenceAndKafkaPublishing() {
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"rollbackTest\":\"shouldNotBeSent\"}";

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(_ -> {
            outboxService.recordEvent("TEST_AGGREGATE", aggregateId, "RollbackEvent", payload);
            throw new IllegalStateException("Simulated business failure triggering rollback");
        })).isInstanceOf(IllegalStateException.class);

        List<OutboxEvent> events = outboxEventRepository
                .findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("TEST_AGGREGATE", aggregateId);
        assertThat(events).isEmpty();

        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
            assertThat(record.key()).isNotEqualTo(aggregateId.toString());
        }
    }
}
