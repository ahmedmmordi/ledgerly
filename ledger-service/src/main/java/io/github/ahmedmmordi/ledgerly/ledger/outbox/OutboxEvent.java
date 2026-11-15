package io.github.ahmedmmordi.ledgerly.ledger.outbox;

import io.github.ahmedmmordi.ledgerly.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "outbox_events")
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NullMarked
public class OutboxEvent extends BaseEntity {

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Nullable
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Nullable
    @Column(name = "sent_at")
    private Instant sentAt;

    public static OutboxEvent create(String aggregateType, UUID aggregateId, String eventType, String payload) {
        Objects.requireNonNull(aggregateType, "Aggregate type must not be null");
        Objects.requireNonNull(aggregateId, "Aggregate ID must not be null");
        Objects.requireNonNull(eventType, "Event type must not be null");
        Objects.requireNonNull(payload, "Payload must not be null");

        String trimmedAggregateType = aggregateType.trim();
        if (trimmedAggregateType.isEmpty()) {
            throw new IllegalArgumentException("Aggregate type must not be blank");
        }
        if (trimmedAggregateType.length() > 64) {
            throw new IllegalArgumentException("Aggregate type must not exceed 64 characters");
        }

        String trimmedEventType = eventType.trim();
        if (trimmedEventType.isEmpty()) {
            throw new IllegalArgumentException("Event type must not be blank");
        }
        if (trimmedEventType.length() > 128) {
            throw new IllegalArgumentException("Event type must not exceed 128 characters");
        }

        String trimmedPayload = payload.trim();
        if (trimmedPayload.isEmpty()) {
            throw new IllegalArgumentException("Payload must not be blank");
        }

        return OutboxEvent.builder().aggregateType(trimmedAggregateType).aggregateId(aggregateId)
                .eventType(trimmedEventType).payload(trimmedPayload).status(OutboxStatus.PENDING).retryCount(0).build();
    }

    public void markSent(Instant sentAt) {
        Objects.requireNonNull(sentAt, "Sent at timestamp must not be null");
        if (this.status == OutboxStatus.SENT) {
            return;
        }
        this.status = OutboxStatus.SENT;
        this.sentAt = sentAt;
        this.errorMessage = null;
    }

    public void recordFailure(String reason, int maxRetries) {
        Objects.requireNonNull(reason, "Failure reason must not be null");
        if (maxRetries < 1) {
            throw new IllegalArgumentException("Max retries must be at least 1");
        }
        this.retryCount++;
        this.errorMessage = reason.trim();
        if (this.retryCount >= maxRetries) {
            this.status = OutboxStatus.FAILED;
        }
    }

    public boolean isPending() {
        return status == OutboxStatus.PENDING;
    }

    public boolean isSent() {
        return status == OutboxStatus.SENT;
    }

    public boolean isFailed() {
        return status == OutboxStatus.FAILED;
    }
}
