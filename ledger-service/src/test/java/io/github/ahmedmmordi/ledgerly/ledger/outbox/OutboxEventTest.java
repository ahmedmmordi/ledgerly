package io.github.ahmedmmordi.ledgerly.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("OutboxEvent Domain Entity Unit Tests")
@SuppressWarnings({ "null", "DataFlowIssue", "ConstantConditions" })
class OutboxEventTest {

    private static final String AGGREGATE_TYPE = "TRANSFER";
    private static final UUID AGGREGATE_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final String EVENT_TYPE = "TransferCompletedEvent";
    private static final String PAYLOAD = "{\"transferId\":\"0193297a-9a90-7888-8123-000000000001\",\"amount\":1000}";

    @Nested
    @DisplayName("Creation & Validation Tests")
    class CreationTests {

        @Test
        @DisplayName("Should create OutboxEvent in PENDING status with trimmed strings and zero retries")
        void shouldCreateOutboxEventSuccessfully() {
            OutboxEvent event = OutboxEvent.create("  " + AGGREGATE_TYPE + "  ", AGGREGATE_ID, "  " + EVENT_TYPE + "  ",
                    "  " + PAYLOAD + "  ");

            assertThat(event.getAggregateType()).isEqualTo(AGGREGATE_TYPE);
            assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
            assertThat(event.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(event.getPayload()).isEqualTo(PAYLOAD);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getRetryCount()).isZero();
            assertThat(event.getErrorMessage()).isNull();
            assertThat(event.getSentAt()).isNull();
            assertThat(event.isPending()).isTrue();
            assertThat(event.isSent()).isFalse();
            assertThat(event.isFailed()).isFalse();
        }

        @Test
        @DisplayName("Should fail when aggregateType is null")
        void shouldFailWhenAggregateTypeIsNull() {
            assertThatThrownBy(() -> OutboxEvent.create(null, AGGREGATE_ID, EVENT_TYPE, PAYLOAD))
                    .isInstanceOf(NullPointerException.class).hasMessage("Aggregate type must not be null");
        }

        @ParameterizedTest(name = "Blank aggregateType: ''{0}''")
        @ValueSource(strings = { "", "   ", "\t", "\n" })
        @DisplayName("Should fail when aggregateType is blank")
        void shouldFailWhenAggregateTypeIsBlank(String blankType) {
            assertThatThrownBy(() -> OutboxEvent.create(blankType, AGGREGATE_ID, EVENT_TYPE, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Aggregate type must not be blank");
        }

        @Test
        @DisplayName("Should accept aggregateType exactly at 64 characters and reject 65 characters")
        void shouldEnforceAggregateTypeLengthBoundaries() {
            String type64 = "A".repeat(64);
            OutboxEvent event = OutboxEvent.create(type64, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            assertThat(event.getAggregateType()).isEqualTo(type64);

            String type65 = "A".repeat(65);
            assertThatThrownBy(() -> OutboxEvent.create(type65, AGGREGATE_ID, EVENT_TYPE, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Aggregate type must not exceed 64 characters");
        }

        @Test
        @DisplayName("Should fail when aggregateId is null")
        void shouldFailWhenAggregateIdIsNull() {
            assertThatThrownBy(() -> OutboxEvent.create(AGGREGATE_TYPE, null, EVENT_TYPE, PAYLOAD))
                    .isInstanceOf(NullPointerException.class).hasMessage("Aggregate ID must not be null");
        }

        @Test
        @DisplayName("Should fail when eventType is null")
        void shouldFailWhenEventTypeIsNull() {
            assertThatThrownBy(() -> OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, null, PAYLOAD))
                    .isInstanceOf(NullPointerException.class).hasMessage("Event type must not be null");
        }

        @ParameterizedTest(name = "Blank eventType: ''{0}''")
        @ValueSource(strings = { "", "   ", "\t", "\n" })
        @DisplayName("Should fail when eventType is blank")
        void shouldFailWhenEventTypeIsBlank(String blankEventType) {
            assertThatThrownBy(() -> OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, blankEventType, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Event type must not be blank");
        }

        @Test
        @DisplayName("Should accept eventType exactly at 128 characters and reject 129 characters")
        void shouldEnforceEventTypeLengthBoundaries() {
            String eventType128 = "E".repeat(128);
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, eventType128, PAYLOAD);
            assertThat(event.getEventType()).isEqualTo(eventType128);

            String eventType129 = "E".repeat(129);
            assertThatThrownBy(() -> OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, eventType129, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Event type must not exceed 128 characters");
        }

        @Test
        @DisplayName("Should fail when payload is null")
        void shouldFailWhenPayloadIsNull() {
            assertThatThrownBy(() -> OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Payload must not be null");
        }

        @ParameterizedTest(name = "Blank payload: ''{0}''")
        @ValueSource(strings = { "", "   ", "\t", "\n" })
        @DisplayName("Should fail when payload is blank")
        void shouldFailWhenPayloadIsBlank(String blankPayload) {
            assertThatThrownBy(() -> OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, blankPayload))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Payload must not be blank");
        }
    }

    @Nested
    @DisplayName("State Transitions & Lifecycle Tests")
    class StateTransitionTests {

        @Test
        @DisplayName("Should mark event as SENT and clear error message")
        void shouldMarkSentSuccessfully() {
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            event.recordFailure("Temporary network blip", 3);
            assertThat(event.getRetryCount()).isEqualTo(1);
            assertThat(event.getErrorMessage()).isEqualTo("Temporary network blip");
            assertThat(event.isPending()).isTrue();

            Instant sentAt = Instant.now();
            event.markSent(sentAt);

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(event.getSentAt()).isEqualTo(sentAt);
            assertThat(event.getErrorMessage()).isNull();
            assertThat(event.isSent()).isTrue();
            assertThat(event.isPending()).isFalse();
            assertThat(event.isFailed()).isFalse();
        }

        @Test
        @DisplayName("Should be idempotent when markSent is called repeatedly")
        void shouldBeIdempotentOnRepeatedMarkSent() {
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            Instant firstSentAt = Instant.now();
            event.markSent(firstSentAt);

            Instant secondSentAt = firstSentAt.plusSeconds(60);
            event.markSent(secondSentAt);

            assertThat(event.getSentAt()).isEqualTo(firstSentAt);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        }

        @Test
        @DisplayName("Should fail when markSent is given null timestamp")
        void shouldFailWhenMarkSentWithNull() {
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            assertThatThrownBy(() -> event.markSent(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Sent at timestamp must not be null");
        }

        @Test
        @DisplayName("Should increment retryCount and transition to FAILED when reaching maxRetries")
        void shouldIncrementRetryCountAndTransitionToFailed() {
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            int maxRetries = 3;

            // Attempt 1
            event.recordFailure("  Kafka broker connection timeout  ", maxRetries);
            assertThat(event.getRetryCount()).isEqualTo(1);
            assertThat(event.getErrorMessage()).isEqualTo("Kafka broker connection timeout");
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.isPending()).isTrue();

            // Attempt 2
            event.recordFailure("Kafka leader not available", maxRetries);
            assertThat(event.getRetryCount()).isEqualTo(2);
            assertThat(event.getErrorMessage()).isEqualTo("Kafka leader not available");
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.isPending()).isTrue();

            // Attempt 3: reaches maxRetries -> transitions to FAILED
            event.recordFailure("Fatal authorization failure", maxRetries);
            assertThat(event.getRetryCount()).isEqualTo(3);
            assertThat(event.getErrorMessage()).isEqualTo("Fatal authorization failure");
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(event.isPending()).isFalse();
            assertThat(event.isFailed()).isTrue();
            assertThat(event.isSent()).isFalse();
        }

        @Test
        @DisplayName("Should transition directly to FAILED on first failure when maxRetries is 1")
        void shouldTransitionDirectlyToFailedWhenMaxRetriesIsOne() {
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            event.recordFailure("Immediate non-retryable failure", 1);

            assertThat(event.getRetryCount()).isEqualTo(1);
            assertThat(event.getErrorMessage()).isEqualTo("Immediate non-retryable failure");
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(event.isFailed()).isTrue();
            assertThat(event.isPending()).isFalse();
            assertThat(event.isSent()).isFalse();
        }

        @Test
        @DisplayName("Should allow marking FAILED event as SENT when retried or replayed")
        void shouldAllowMarkingFailedEventAsSent() {
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            event.recordFailure("Broker unavailable", 1);
            assertThat(event.isFailed()).isTrue();
            assertThat(event.getErrorMessage()).isEqualTo("Broker unavailable");

            Instant sentAt = Instant.now();
            event.markSent(sentAt);

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(event.getSentAt()).isEqualTo(sentAt);
            assertThat(event.getErrorMessage()).isNull();
            assertThat(event.isSent()).isTrue();
            assertThat(event.isFailed()).isFalse();
        }

        @Test
        @DisplayName("Should fail when recordFailure is given null reason")
        void shouldFailWhenRecordFailureGivenNullReason() {
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            assertThatThrownBy(() -> event.recordFailure(null, 3)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Failure reason must not be null");
        }

        @ParameterizedTest(name = "Invalid maxRetries: {0}")
        @ValueSource(ints = { 0, -1, -5 })
        @DisplayName("Should fail when maxRetries is less than 1")
        void shouldFailWhenMaxRetriesLessThanOne(int invalidMaxRetries) {
            OutboxEvent event = OutboxEvent.create(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, PAYLOAD);
            assertThatThrownBy(() -> event.recordFailure("Connection error", invalidMaxRetries))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Max retries must be at least 1");
        }
    }
}
