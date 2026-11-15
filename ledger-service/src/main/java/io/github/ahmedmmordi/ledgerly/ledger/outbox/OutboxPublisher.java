package io.github.ahmedmmordi.ledgerly.ledger.outbox;

import io.github.ahmedmmordi.ledgerly.ledger.config.KafkaProducerConfig;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled worker polling PENDING outbox events via SKIP LOCKED and publishing to Kafka.
 */
@Slf4j
@Service
@NullMarked
@ConditionalOnProperty(prefix = "ledger.outbox.poller", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxRetries;
    private final int sendTimeoutSeconds;
    private final String topic;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${ledger.outbox.poller.batch-size:50}") int batchSize,
            @Value("${ledger.outbox.poller.max-retries:3}") int maxRetries,
            @Value("${ledger.outbox.poller.send-timeout-seconds:5}") int sendTimeoutSeconds,
            @Value("${ledger.kafka.topics.transfers:" + KafkaProducerConfig.TOPIC_TRANSFERS + "}") String topic) {
        this.outboxEventRepository = Objects.requireNonNull(outboxEventRepository,
                "OutboxEventRepository must not be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "KafkaTemplate must not be null");
        Objects.requireNonNull(transactionManager, "TransactionManager must not be null");
        this.transactionTemplate = new TransactionTemplate(transactionManager);

        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be strictly positive");
        }
        if (maxRetries < 1) {
            throw new IllegalArgumentException("Max retries must be at least 1");
        }
        if (sendTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Send timeout must be strictly positive");
        }
        Objects.requireNonNull(topic, "Topic must not be null");
        String trimmedTopic = topic.trim();
        if (trimmedTopic.isEmpty()) {
            throw new IllegalArgumentException("Topic must not be blank");
        }
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
        this.topic = trimmedTopic;
    }

    /**
     * Polls pending outbox records and publishes them to Kafka.
     *
     * @return count of successfully published events
     */
    @Scheduled(fixedDelayString = "${ledger.outbox.poller.fixed-delay-ms:1000}")
    public int publishPendingEvents() {
        Integer published = transactionTemplate.execute(_ -> {
            List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingForProcessing(OutboxStatus.PENDING,
                    PageRequest.of(0, batchSize));

            if (pendingEvents.isEmpty()) {
                return 0;
            }

            log.debug("Polled {} pending outbox events for publishing", pendingEvents.size());
            int publishedCount = 0;

            for (OutboxEvent event : pendingEvents) {
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Poller thread interrupted, aborting remaining batch");
                    break;
                }
                if (publishSingleEvent(event)) {
                    publishedCount++;
                }
            }

            log.info("Published {} of {} outbox events to Kafka topic {}", publishedCount, pendingEvents.size(), topic);
            return publishedCount;
        });

        return published != null ? published : 0;
    }

    private boolean publishSingleEvent(OutboxEvent event) {
        String key = event.getAggregateId().toString();
        try {
            kafkaTemplate.send(topic, key, event.getPayload()).get(sendTimeoutSeconds, TimeUnit.SECONDS);
            event.markSent(Instant.now());
            outboxEventRepository.save(event);
            log.debug("Published outbox event {} for aggregate {} [{}] to topic {}", event.getId(),
                    event.getAggregateType(), event.getAggregateId(), topic);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Publishing thread interrupted for outbox event {}", event.getId(), e);
            String message = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage()
                    : "Thread interrupted";
            event.recordFailure("Publishing interrupted: " + message, maxRetries);
            outboxEventRepository.save(event);
            return false;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage();
            String reason = (message != null && !message.isBlank()) ? message.trim() : cause.getClass().getSimpleName();
            log.error("Failed to publish outbox event {} for aggregate {} [{}]: {}", event.getId(),
                    event.getAggregateType(), event.getAggregateId(), reason, cause);
            event.recordFailure(reason, maxRetries);
            outboxEventRepository.save(event);
            return false;
        }
    }
}
