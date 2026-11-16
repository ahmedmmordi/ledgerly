package io.github.ahmedmmordi.ledgerly.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;

@NullMarked
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisher Unit Tests")
@SuppressWarnings({ "null", "DataFlowIssue", "ConstantConditions", "unchecked" })
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    private static final String TOPIC = "transfers";
    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRIES = 3;
    private static final int SEND_TIMEOUT_SECONDS = 5;

    private OutboxPublisher buildPublisher() {
        return new OutboxPublisher(outboxEventRepository, kafkaTemplate, transactionManager, BATCH_SIZE, MAX_RETRIES,
                SEND_TIMEOUT_SECONDS, TOPIC);
    }

    private OutboxEvent buildPendingEvent() {
        return OutboxEvent.create("TRANSFER", UUID.randomUUID(), "TransferCompleted", "{\"amount\":\"100.00\"}");
    }

    private void stubKafkaSendSuccess() throws Exception {
        CompletableFuture<SendResult<String, String>> successFuture = mock(CompletableFuture.class);
        when(successFuture.get(eq((long) SEND_TIMEOUT_SECONDS), eq(TimeUnit.SECONDS)))
                .thenReturn(mock(SendResult.class));
        when(kafkaTemplate.send(eq(TOPIC), any(String.class), any(String.class))).thenReturn(successFuture);
    }

    private void stubKafkaSendTimeout() throws Exception {
        CompletableFuture<SendResult<String, String>> timeoutFuture = mock(CompletableFuture.class);
        when(timeoutFuture.get(eq((long) SEND_TIMEOUT_SECONDS), eq(TimeUnit.SECONDS)))
                .thenThrow(new TimeoutException("Kafka send timed out"));
        when(kafkaTemplate.send(eq(TOPIC), any(String.class), any(String.class))).thenReturn(timeoutFuture);
    }

    private void stubKafkaSendExecutionException(Throwable cause) throws Exception {
        CompletableFuture<SendResult<String, String>> failFuture = mock(CompletableFuture.class);
        when(failFuture.get(eq((long) SEND_TIMEOUT_SECONDS), eq(TimeUnit.SECONDS)))
                .thenThrow(new ExecutionException("Kafka broker error", cause));
        when(kafkaTemplate.send(eq(TOPIC), any(String.class), any(String.class))).thenReturn(failFuture);
    }

    private void stubKafkaSendInterrupted() throws Exception {
        CompletableFuture<SendResult<String, String>> interruptFuture = mock(CompletableFuture.class);
        when(interruptFuture.get(eq((long) SEND_TIMEOUT_SECONDS), eq(TimeUnit.SECONDS)))
                .thenThrow(new InterruptedException("Thread interrupted during send"));
        when(kafkaTemplate.send(eq(TOPIC), any(String.class), any(String.class))).thenReturn(interruptFuture);
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("null repository throws NullPointerException")
        void nullRepository_throwsNPE() {
            assertThatThrownBy(() -> new OutboxPublisher(null, kafkaTemplate, transactionManager, BATCH_SIZE,
                    MAX_RETRIES, SEND_TIMEOUT_SECONDS, TOPIC)).isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("OutboxEventRepository");
        }

        @Test
        @DisplayName("null KafkaTemplate throws NullPointerException")
        void nullKafkaTemplate_throwsNPE() {
            assertThatThrownBy(() -> new OutboxPublisher(outboxEventRepository, null, transactionManager, BATCH_SIZE,
                    MAX_RETRIES, SEND_TIMEOUT_SECONDS, TOPIC)).isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("KafkaTemplate");
        }

        @Test
        @DisplayName("null TransactionManager throws NullPointerException")
        void nullTransactionManager_throwsNPE() {
            assertThatThrownBy(() -> new OutboxPublisher(outboxEventRepository, kafkaTemplate, null, BATCH_SIZE,
                    MAX_RETRIES, SEND_TIMEOUT_SECONDS, TOPIC)).isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("TransactionManager");
        }

        @ParameterizedTest(name = "batchSize={0}")
        @ValueSource(ints = { 0, -1, -100 })
        @DisplayName("non-positive batchSize throws IllegalArgumentException")
        void nonPositiveBatchSize_throwsIAE(int invalidBatchSize) {
            assertThatThrownBy(() -> new OutboxPublisher(outboxEventRepository, kafkaTemplate, transactionManager,
                    invalidBatchSize, MAX_RETRIES, SEND_TIMEOUT_SECONDS, TOPIC))
                            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Batch size");
        }

        @ParameterizedTest(name = "maxRetries={0}")
        @ValueSource(ints = { 0, -1, -5 })
        @DisplayName("maxRetries less than 1 throws IllegalArgumentException")
        void maxRetriesLessThanOne_throwsIAE(int invalidMaxRetries) {
            assertThatThrownBy(() -> new OutboxPublisher(outboxEventRepository, kafkaTemplate, transactionManager,
                    BATCH_SIZE, invalidMaxRetries, SEND_TIMEOUT_SECONDS, TOPIC))
                            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Max retries");
        }

        @ParameterizedTest(name = "sendTimeoutSeconds={0}")
        @ValueSource(ints = { 0, -1, -10 })
        @DisplayName("non-positive sendTimeoutSeconds throws IllegalArgumentException")
        void nonPositiveSendTimeout_throwsIAE(int invalidTimeout) {
            assertThatThrownBy(() -> new OutboxPublisher(outboxEventRepository, kafkaTemplate, transactionManager,
                    BATCH_SIZE, MAX_RETRIES, invalidTimeout, TOPIC)).isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Send timeout");
        }

        @Test
        @DisplayName("null topic throws NullPointerException")
        void nullTopic_throwsNPE() {
            assertThatThrownBy(() -> new OutboxPublisher(outboxEventRepository, kafkaTemplate, transactionManager,
                    BATCH_SIZE, MAX_RETRIES, SEND_TIMEOUT_SECONDS, null)).isInstanceOf(NullPointerException.class)
                            .hasMessageContaining("Topic");
        }

        @ParameterizedTest(name = "topic=\"{0}\"")
        @ValueSource(strings = { "", "   ", "\t", "\n" })
        @DisplayName("blank topic throws IllegalArgumentException")
        void blankTopic_throwsIAE(String blankTopic) {
            assertThatThrownBy(() -> new OutboxPublisher(outboxEventRepository, kafkaTemplate, transactionManager,
                    BATCH_SIZE, MAX_RETRIES, SEND_TIMEOUT_SECONDS, blankTopic))
                            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Topic");
        }
    }

    @Nested
    @DisplayName("publishPendingEvents — empty outbox")
    class EmptyOutbox {

        @Test
        @DisplayName("returns 0 and never touches KafkaTemplate when no PENDING events exist")
        void emptyOutbox_returnsZeroAndNeverSends() {
            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of());

            OutboxPublisher publisher = buildPublisher();
            int published = publisher.publishPendingEvents();

            assertThat(published).isZero();
            verify(kafkaTemplate, never()).send(any(), any(), any());
            verify(outboxEventRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("publishPendingEvents — successful publish")
    class SuccessfulPublish {

        @Test
        @DisplayName("single PENDING event: marks sent, saves, returns 1")
        void singleEvent_success_marksSentAndSaves() throws Exception {
            OutboxEvent event = buildPendingEvent();
            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of(event));
            stubKafkaSendSuccess();

            OutboxPublisher publisher = buildPublisher();
            int published = publisher.publishPendingEvents();

            assertThat(published).isEqualTo(1);
            assertThat(event.isSent()).isTrue();
            assertThat(event.getSentAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
            verify(outboxEventRepository, times(1)).save(event);
        }

        @Test
        @DisplayName("publishes to correct topic with aggregateId as key and original payload as value")
        void send_usesCorrectTopicKeyAndPayload() throws Exception {
            OutboxEvent event = buildPendingEvent();
            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of(event));
            stubKafkaSendSuccess();

            buildPublisher().publishPendingEvents();

            verify(kafkaTemplate).send(eq(TOPIC), eq(event.getAggregateId().toString()), eq(event.getPayload()));
        }

        @Test
        @DisplayName("two successful events: returns 2 and saves each once")
        void twoEvents_bothSucceed_returnsTwoAndSavesBoth() throws Exception {
            OutboxEvent event1 = buildPendingEvent();
            OutboxEvent event2 = buildPendingEvent();
            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of(event1, event2));
            stubKafkaSendSuccess();

            int published = buildPublisher().publishPendingEvents();

            assertThat(published).isEqualTo(2);
            assertThat(event1.isSent()).isTrue();
            assertThat(event2.isSent()).isTrue();
            verify(outboxEventRepository).save(event1);
            verify(outboxEventRepository).save(event2);
        }
    }

    @Nested
    @DisplayName("publishPendingEvents — Kafka send timeout")
    class KafkaSendTimeout {

        @Test
        @DisplayName("TimeoutException wrapped in ExecutionException: records failure, saves, returns 0")
        void timeout_recordsFailureAndSaves() throws Exception {
            OutboxEvent event = buildPendingEvent();
            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of(event));
            // TimeoutException falls into the generic catch block with cause = exception itself
            stubKafkaSendTimeout();

            int published = buildPublisher().publishPendingEvents();

            assertThat(published).isZero();
            assertThat(event.isPending()).isTrue();
            assertThat(event.getErrorMessage()).isNotBlank();
            verify(outboxEventRepository, times(1)).save(event);
        }

        @Test
        @DisplayName("TimeoutException wrapped in ExecutionException: records failure via cause message")
        void executionException_withCause_recordsCauseMessage() throws Exception {
            OutboxEvent event = buildPendingEvent();
            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of(event));
            stubKafkaSendExecutionException(new RuntimeException("broker unavailable"));

            int published = buildPublisher().publishPendingEvents();

            assertThat(published).isZero();
            assertThat(event.getErrorMessage()).contains("broker unavailable");
            verify(outboxEventRepository, times(1)).save(event);
        }

        @Test
        @DisplayName("ExecutionException with null cause message falls back to cause class simple name")
        void executionException_nullCauseMessage_fallsBackToClassName() throws Exception {
            OutboxEvent event = buildPendingEvent();
            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of(event));
            stubKafkaSendExecutionException(new RuntimeException((String) null));

            int published = buildPublisher().publishPendingEvents();

            assertThat(published).isZero();
            assertThat(event.getErrorMessage()).isEqualTo("RuntimeException");
            verify(outboxEventRepository, times(1)).save(event);
        }
    }

    @Nested
    @DisplayName("publishPendingEvents — InterruptedException")
    class KafkaSendInterrupted {

        @Test
        @DisplayName("InterruptedException: restores interrupt flag, records failure, saves, returns 0")
        void interrupted_restoresFlagAndRecordsFailure() throws Exception {
            OutboxEvent event = buildPendingEvent();
            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of(event));
            stubKafkaSendInterrupted();

            OutboxPublisher publisher = buildPublisher();
            int published = publisher.publishPendingEvents();

            // Verify publisher restored the thread interrupt flag
            assertThat(Thread.interrupted()).isTrue();

            assertThat(published).isZero();
            assertThat(event.getErrorMessage()).startsWith("Publishing interrupted:");
            verify(outboxEventRepository, times(1)).save(event);
        }
    }

    @Nested
    @DisplayName("publishPendingEvents — max retries exhaustion")
    class MaxRetriesExhaustion {

        @Test
        @DisplayName("after maxRetries failures the event status transitions to FAILED")
        void maxRetries_eventTransitionsToFailed() throws Exception {
            OutboxPublisher publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, transactionManager,
                    BATCH_SIZE, 1, SEND_TIMEOUT_SECONDS, TOPIC);

            OutboxEvent event = buildPendingEvent();
            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of(event));
            stubKafkaSendExecutionException(new RuntimeException("permanent error"));

            publisher.publishPendingEvents();

            assertThat(event.isFailed()).isTrue();
            verify(outboxEventRepository, times(1)).save(event);
        }
    }

    @Nested
    @DisplayName("publishPendingEvents — mixed batch")
    class MixedBatch {

        @Test
        @DisplayName("first event succeeds, second times out: returns 1, first SENT, second still PENDING")
        void mixedBatch_firstSucceedsSecondFails_returnsOne() throws Exception {
            OutboxEvent event1 = buildPendingEvent();
            OutboxEvent event2 = buildPendingEvent();

            when(outboxEventRepository.findPendingForProcessing(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                    .thenReturn(List.of(event1, event2));

            // First send succeeds, second times out
            CompletableFuture<SendResult<String, String>> successFuture = mock(CompletableFuture.class);
            when(successFuture.get(eq((long) SEND_TIMEOUT_SECONDS), eq(TimeUnit.SECONDS)))
                    .thenReturn(mock(SendResult.class));

            CompletableFuture<SendResult<String, String>> timeoutFuture = mock(CompletableFuture.class);
            when(timeoutFuture.get(eq((long) SEND_TIMEOUT_SECONDS), eq(TimeUnit.SECONDS)))
                    .thenThrow(new TimeoutException("timed out"));

            when(kafkaTemplate.send(eq(TOPIC), eq(event1.getAggregateId().toString()), any()))
                    .thenReturn(successFuture);
            when(kafkaTemplate.send(eq(TOPIC), eq(event2.getAggregateId().toString()), any()))
                    .thenReturn(timeoutFuture);

            int published = buildPublisher().publishPendingEvents();

            assertThat(published).isEqualTo(1);
            assertThat(event1.isSent()).isTrue();
            assertThat(event2.isPending()).isTrue();
            assertThat(event2.getErrorMessage()).isNotBlank();
            verify(outboxEventRepository).save(event1);
            verify(outboxEventRepository).save(event2);
        }
    }

    @Nested
    @DisplayName("publishPendingEvents — batch-size enforcement")
    class BatchSizeEnforcement {

        @Test
        @DisplayName("passes correct PageRequest(0, batchSize) to repository query")
        void passesCorrectPageRequest() {
            int customBatchSize = 10;
            OutboxPublisher publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, transactionManager,
                    customBatchSize, MAX_RETRIES, SEND_TIMEOUT_SECONDS, TOPIC);

            when(outboxEventRepository.findPendingForProcessing(any(), any())).thenReturn(List.of());

            publisher.publishPendingEvents();

            ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
            verify(outboxEventRepository).findPendingForProcessing(eq(OutboxStatus.PENDING), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(customBatchSize);
            assertThat(captor.getValue().getPageNumber()).isZero();
        }
    }
}
