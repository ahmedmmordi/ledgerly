package io.github.ahmedmmordi.ledgerly.ledger.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service responsible for serializing and persisting transactional outbox events.
 */
@Slf4j
@Service
@NullMarked
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = Objects.requireNonNull(outboxEventRepository,
                "OutboxEventRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
    }

    /**
     * Serializes an event payload to JSON and persists an {@link OutboxEvent} in {@code PENDING} status. Enforces
     * {@link Propagation#MANDATORY} for atomic dual-write consistency within the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvent(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        Objects.requireNonNull(aggregateType, "Aggregate type must not be null");
        Objects.requireNonNull(aggregateId, "Aggregate ID must not be null");
        Objects.requireNonNull(eventType, "Event type must not be null");
        Objects.requireNonNull(payload, "Payload must not be null");

        String payloadJson;
        try {
            payloadJson = payload instanceof String str ? str : objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload for aggregate {} [{}]", aggregateType, aggregateId, e);
            throw new IllegalArgumentException("Failed to serialize outbox event payload to JSON", e);
        }

        OutboxEvent outboxEvent = OutboxEvent.create(aggregateType, aggregateId, eventType, payloadJson);
        OutboxEvent saved = outboxEventRepository.save(outboxEvent);
        log.debug("Recorded outbox event {} for aggregate {} [{}] with event type {}", saved.getId(), aggregateType,
                aggregateId, eventType);
    }
}
