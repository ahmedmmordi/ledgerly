package io.github.ahmedmmordi.ledgerly.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ahmedmmordi.ledgerly.common.event.TransferCompletedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxService Unit Tests")
@SuppressWarnings({ "null", "DataFlowIssue", "ConstantConditions" })
class OutboxServiceTest {

    private static final String AGGREGATE_TYPE = "TRANSFER";
    private static final UUID AGGREGATE_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final String EVENT_TYPE = "TransferCompletedEvent";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(outboxEventRepository, objectMapper);
    }

    @Nested
    @DisplayName("Constructor Validation Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should fail when outboxEventRepository is null")
        void shouldFailWhenRepositoryIsNull() {
            assertThatThrownBy(() -> new OutboxService(null, objectMapper)).isInstanceOf(NullPointerException.class)
                    .hasMessage("OutboxEventRepository must not be null");
        }

        @Test
        @DisplayName("Should fail when objectMapper is null")
        void shouldFailWhenObjectMapperIsNull() {
            assertThatThrownBy(() -> new OutboxService(outboxEventRepository, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("ObjectMapper must not be null");
        }
    }

    @Nested
    @DisplayName("Record Event Tests")
    class RecordEventTests {

        @BeforeEach
        void setUpSaveStub() {
            lenient().when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> {
                OutboxEvent event = inv.getArgument(0);
                ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
                return event;
            });
        }

        @Test
        @DisplayName("Should serialize object payload to JSON and save OutboxEvent in PENDING status")
        void shouldRecordEventWithObjectPayload() throws Exception {
            TransferCompletedEvent payload = new TransferCompletedEvent(UUID.randomUUID(), AGGREGATE_ID,
                    UUID.randomUUID(), UUID.randomUUID(), 1000L, "USD", Instant.now(), 1);
            String json = "{\"transferId\":\"" + AGGREGATE_ID + "\",\"amountInMinorUnits\":1000}";

            when(objectMapper.writeValueAsString(payload)).thenReturn(json);

            outboxService.recordEvent(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, payload);

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());

            OutboxEvent saved = captor.getValue();
            assertThat(saved.getAggregateType()).isEqualTo(AGGREGATE_TYPE);
            assertThat(saved.getAggregateId()).isEqualTo(AGGREGATE_ID);
            assertThat(saved.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(saved.getPayload()).isEqualTo(json);
            assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(saved.getRetryCount()).isZero();
            assertThat(saved.getSentAt()).isNull();
            assertThat(saved.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("Should record raw string payload directly without invoking objectMapper")
        void shouldRecordRawStringPayloadWithoutObjectMapper() throws Exception {
            String rawJson = "{\"status\":\"ok\",\"raw\":true}";

            outboxService.recordEvent(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, rawJson);

            verify(objectMapper, never()).writeValueAsString(any());

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());

            OutboxEvent saved = captor.getValue();
            assertThat(saved.getPayload()).isEqualTo(rawJson);
            assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        }

        @Test
        @DisplayName("Should wrap JsonProcessingException into IllegalArgumentException")
        void shouldWrapJsonProcessingException() throws Exception {
            Object unmappableObject = new Object();
            JsonProcessingException jsonException = mock(JsonProcessingException.class);
            when(objectMapper.writeValueAsString(unmappableObject)).thenThrow(jsonException);

            assertThatThrownBy(
                    () -> outboxService.recordEvent(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, unmappableObject))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Failed to serialize outbox event payload to JSON").hasCause(jsonException);

            verify(outboxEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should fail when aggregateType is null")
        void shouldFailWhenAggregateTypeIsNull() {
            assertThatThrownBy(() -> outboxService.recordEvent(null, AGGREGATE_ID, EVENT_TYPE, "{}"))
                    .isInstanceOf(NullPointerException.class).hasMessage("Aggregate type must not be null");
        }

        @Test
        @DisplayName("Should fail when aggregateId is null")
        void shouldFailWhenAggregateIdIsNull() {
            assertThatThrownBy(() -> outboxService.recordEvent(AGGREGATE_TYPE, null, EVENT_TYPE, "{}"))
                    .isInstanceOf(NullPointerException.class).hasMessage("Aggregate ID must not be null");
        }

        @Test
        @DisplayName("Should fail when eventType is null")
        void shouldFailWhenEventTypeIsNull() {
            assertThatThrownBy(() -> outboxService.recordEvent(AGGREGATE_TYPE, AGGREGATE_ID, null, "{}"))
                    .isInstanceOf(NullPointerException.class).hasMessage("Event type must not be null");
        }

        @Test
        @DisplayName("Should fail when payload is null")
        void shouldFailWhenPayloadIsNull() {
            assertThatThrownBy(() -> outboxService.recordEvent(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, null))
                    .isInstanceOf(NullPointerException.class).hasMessage("Payload must not be null");
        }

        @ParameterizedTest(name = "Blank aggregateType: ''{0}''")
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should fail when aggregateType is blank")
        void shouldFailWhenAggregateTypeIsBlank(String blankType) {
            assertThatThrownBy(() -> outboxService.recordEvent(blankType, AGGREGATE_ID, EVENT_TYPE, "{}"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Aggregate type must not be blank");
        }

        @ParameterizedTest(name = "Blank eventType: ''{0}''")
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should fail when eventType is blank")
        void shouldFailWhenEventTypeIsBlank(String blankEventType) {
            assertThatThrownBy(() -> outboxService.recordEvent(AGGREGATE_TYPE, AGGREGATE_ID, blankEventType, "{}"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Event type must not be blank");
        }

        @ParameterizedTest(name = "Blank payload string: ''{0}''")
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should fail when payload string is blank")
        void shouldFailWhenPayloadStringIsBlank(String blankPayload) {
            assertThatThrownBy(() -> outboxService.recordEvent(AGGREGATE_TYPE, AGGREGATE_ID, EVENT_TYPE, blankPayload))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Payload must not be blank");
        }
    }
}
