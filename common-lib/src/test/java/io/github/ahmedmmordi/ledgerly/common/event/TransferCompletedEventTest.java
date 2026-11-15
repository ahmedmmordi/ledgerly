package io.github.ahmedmmordi.ledgerly.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TransferCompletedEvent Unit Tests")
@SuppressWarnings({ "null", "DataFlowIssue", "ConstantConditions" })
class TransferCompletedEventTest {

    private static final UUID EVENT_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final UUID TRANSFER_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000002");
    private static final UUID FROM_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000003");
    private static final UUID TO_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000004");
    private static final long AMOUNT = 5000L;
    private static final String CURRENCY = "USD";
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-15T18:00:00Z");
    private static final int VERSION = 1;

    @Test
    @DisplayName("Should expose expected constants for aggregate type, event type, and version")
    void shouldExposeExpectedConstants() {
        assertThat(TransferCompletedEvent.AGGREGATE_TYPE).isEqualTo("TRANSFER");
        assertThat(TransferCompletedEvent.EVENT_TYPE).isEqualTo("TransferCompletedEvent");
        assertThat(TransferCompletedEvent.CURRENT_VERSION).isEqualTo(1);
    }

    @Test
    @DisplayName("Should create TransferCompletedEvent via canonical constructor with trimmed and uppercase currency")
    void shouldCreateEventViaCanonicalConstructor() {
        TransferCompletedEvent event = new TransferCompletedEvent(EVENT_ID, TRANSFER_ID, FROM_ACCOUNT_ID, TO_ACCOUNT_ID,
                AMOUNT, "  usd  ", OCCURRED_AT, VERSION);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.transferId()).isEqualTo(TRANSFER_ID);
        assertThat(event.fromAccountId()).isEqualTo(FROM_ACCOUNT_ID);
        assertThat(event.toAccountId()).isEqualTo(TO_ACCOUNT_ID);
        assertThat(event.amountInMinorUnits()).isEqualTo(AMOUNT);
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.version()).isEqualTo(VERSION);
    }

    @Test
    @DisplayName("Should create TransferCompletedEvent via of() factory with random UUID and current timestamp")
    void shouldCreateEventViaOfFactory() {
        Instant before = Instant.now().minusMillis(100);
        TransferCompletedEvent event = TransferCompletedEvent.of(TRANSFER_ID, FROM_ACCOUNT_ID, TO_ACCOUNT_ID, AMOUNT,
                CURRENCY);
        Instant after = Instant.now().plusMillis(100);

        assertThat(event.eventId()).isNotNull();
        assertThat(event.transferId()).isEqualTo(TRANSFER_ID);
        assertThat(event.fromAccountId()).isEqualTo(FROM_ACCOUNT_ID);
        assertThat(event.toAccountId()).isEqualTo(TO_ACCOUNT_ID);
        assertThat(event.amountInMinorUnits()).isEqualTo(AMOUNT);
        assertThat(event.currency()).isEqualTo(CURRENCY);
        assertThat(event.occurredAt()).isBetween(before, after);
        assertThat(event.version()).isEqualTo(TransferCompletedEvent.CURRENT_VERSION);
    }

    @Test
    @DisplayName("Should fail when eventId is null")
    void shouldFailWhenEventIdIsNull() {
        assertThatThrownBy(() -> new TransferCompletedEvent(null, TRANSFER_ID, FROM_ACCOUNT_ID, TO_ACCOUNT_ID, AMOUNT,
                CURRENCY, OCCURRED_AT, VERSION)).isInstanceOf(NullPointerException.class)
                        .hasMessage("Event ID must not be null");
    }

    @Test
    @DisplayName("Should fail when transferId is null")
    void shouldFailWhenTransferIdIsNull() {
        assertThatThrownBy(() -> new TransferCompletedEvent(EVENT_ID, null, FROM_ACCOUNT_ID, TO_ACCOUNT_ID, AMOUNT,
                CURRENCY, OCCURRED_AT, VERSION)).isInstanceOf(NullPointerException.class)
                        .hasMessage("Transfer ID must not be null");
    }

    @Test
    @DisplayName("Should fail when fromAccountId is null")
    void shouldFailWhenFromAccountIdIsNull() {
        assertThatThrownBy(() -> new TransferCompletedEvent(EVENT_ID, TRANSFER_ID, null, TO_ACCOUNT_ID, AMOUNT,
                CURRENCY, OCCURRED_AT, VERSION)).isInstanceOf(NullPointerException.class)
                        .hasMessage("From account ID must not be null");
    }

    @Test
    @DisplayName("Should fail when toAccountId is null")
    void shouldFailWhenToAccountIdIsNull() {
        assertThatThrownBy(() -> new TransferCompletedEvent(EVENT_ID, TRANSFER_ID, FROM_ACCOUNT_ID, null, AMOUNT,
                CURRENCY, OCCURRED_AT, VERSION)).isInstanceOf(NullPointerException.class)
                        .hasMessage("To account ID must not be null");
    }

    @Test
    @DisplayName("Should fail when currency is null")
    void shouldFailWhenCurrencyIsNull() {
        assertThatThrownBy(() -> new TransferCompletedEvent(EVENT_ID, TRANSFER_ID, FROM_ACCOUNT_ID, TO_ACCOUNT_ID,
                AMOUNT, null, OCCURRED_AT, VERSION)).isInstanceOf(NullPointerException.class)
                        .hasMessage("Currency must not be null");
    }

    @Test
    @DisplayName("Should fail when occurredAt is null")
    void shouldFailWhenOccurredAtIsNull() {
        assertThatThrownBy(() -> new TransferCompletedEvent(EVENT_ID, TRANSFER_ID, FROM_ACCOUNT_ID, TO_ACCOUNT_ID,
                AMOUNT, CURRENCY, null, VERSION)).isInstanceOf(NullPointerException.class)
                        .hasMessage("OccurredAt timestamp must not be null");
    }

    @Test
    @DisplayName("Should fail when fromAccountId equals toAccountId")
    void shouldFailWhenAccountsAreIdentical() {
        assertThatThrownBy(() -> new TransferCompletedEvent(EVENT_ID, TRANSFER_ID, FROM_ACCOUNT_ID, FROM_ACCOUNT_ID,
                AMOUNT, CURRENCY, OCCURRED_AT, VERSION)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("From account and to account must be distinct");
    }

    @ParameterizedTest(name = "Invalid amount: {0}")
    @ValueSource(longs = { 0L, -1L, -5000L })
    @DisplayName("Should fail when amount is zero or negative")
    void shouldFailWhenAmountIsNotPositive(long invalidAmount) {
        assertThatThrownBy(() -> new TransferCompletedEvent(EVENT_ID, TRANSFER_ID, FROM_ACCOUNT_ID, TO_ACCOUNT_ID,
                invalidAmount, CURRENCY, OCCURRED_AT, VERSION)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Amount in minor units must be strictly positive");
    }

    @ParameterizedTest(name = "Invalid currency: ''{0}''")
    @ValueSource(strings = { "", "   ", "US", "USDD", "12" })
    @DisplayName("Should fail when currency length is not exactly 3 characters")
    void shouldFailWhenCurrencyLengthIsNotThree(String invalidCurrency) {
        assertThatThrownBy(() -> new TransferCompletedEvent(EVENT_ID, TRANSFER_ID, FROM_ACCOUNT_ID, TO_ACCOUNT_ID,
                AMOUNT, invalidCurrency, OCCURRED_AT, VERSION)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Currency must be a 3-letter ISO code");
    }

    @ParameterizedTest(name = "Invalid version: {0}")
    @ValueSource(ints = { 0, -1, -5 })
    @DisplayName("Should fail when schema version is less than 1")
    void shouldFailWhenVersionIsLessThanOne(int invalidVersion) {
        assertThatThrownBy(() -> new TransferCompletedEvent(EVENT_ID, TRANSFER_ID, FROM_ACCOUNT_ID, TO_ACCOUNT_ID,
                AMOUNT, CURRENCY, OCCURRED_AT, invalidVersion)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Event schema version must be at least 1");
    }
}
