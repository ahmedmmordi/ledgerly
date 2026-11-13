package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Transfer Domain Entity Tests")
class TransferTest {

    private static final UUID FROM_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-111111111111");
    private static final UUID TO_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-222222222222");
    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-333333333333");

    @Nested
    @DisplayName("Transfer Creation & Validation")
    class CreationAndValidationTests {

        @Test
        @DisplayName("Should create PENDING transfer with valid parameters")
        void shouldCreatePendingTransferWithValidInputs() {
            Money amount = Money.parse("150.75 USD");
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, amount, "Invoice payment",
                    USER_ID);

            assertThat(transfer.getFromAccountId()).isEqualTo(FROM_ACCOUNT_ID);
            assertThat(transfer.getToAccountId()).isEqualTo(TO_ACCOUNT_ID);
            assertThat(transfer.getAmount()).isEqualTo(amount);
            assertThat(transfer.getAmount().amountInMinorUnits()).isEqualTo(15075L);
            assertThat(transfer.getCurrency()).isEqualTo("USD");
            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
            assertThat(transfer.isPending()).isTrue();
            assertThat(transfer.isCompleted()).isFalse();
            assertThat(transfer.isFailed()).isFalse();
            assertThat(transfer.getDescription()).isEqualTo("Invoice payment");
            assertThat(transfer.getInitiatedByUserId()).isEqualTo(USER_ID);
            assertThat(transfer.getFailureReason()).isNull();
        }

        @Test
        @DisplayName("Should create COMPLETED transfer with createCompleted factory method")
        void shouldCreateCompletedTransferWithValidInputs() {
            Money amount = Money.parse("50.00 EUR");
            Transfer transfer = Transfer.createCompleted(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, amount, "Direct settlement",
                    USER_ID);

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(transfer.isCompleted()).isTrue();
            assertThat(transfer.isPending()).isFalse();
            assertThat(transfer.isFailed()).isFalse();
            assertThat(transfer.getCurrency()).isEqualTo("EUR");
            assertThat(transfer.getAmount()).isEqualTo(amount);
        }

        @Test
        @DisplayName("Should allow creating transfer with null description and null initiatedByUserId")
        void shouldAllowNullOptionalFields() {
            Money amount = Money.parse("25.00 USD");
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, amount, null, null);

            assertThat(transfer.getDescription()).isNull();
            assertThat(transfer.getInitiatedByUserId()).isNull();
            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   ", "\t", "\n" })
        @DisplayName("Should normalize blank description to null")
        void shouldNormalizeBlankDescriptionToNull(String blankDescription) {
            Money amount = Money.parse("25.00 USD");
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, amount, blankDescription,
                    USER_ID);

            assertThat(transfer.getDescription()).isNull();
        }

        @Test
        @DisplayName("Should safely truncate description exceeding 255 characters with ellipsis")
        void shouldTruncateExcessiveDescription() {
            Money amount = Money.parse("25.00 USD");
            String longDescription = "D".repeat(300);
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, amount, longDescription,
                    USER_ID);

            assertThat(transfer.getDescription()).hasSize(255);
            assertThat(transfer.getDescription()).isEqualTo("D".repeat(252) + "...");
        }

        @Test
        @DisplayName("Should support maximum valid 64-bit integer amount")
        void shouldSupportMaximumValidAmount() {
            Money maxMoney = Money.ofMinor(Long.MAX_VALUE, "USD");
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, maxMoney, "Large transfer",
                    USER_ID);

            assertThat(transfer.getAmount().amountInMinorUnits()).isEqualTo(Long.MAX_VALUE);
            assertThat(transfer.getAmount()).isEqualTo(maxMoney);
        }

        @Test
        @DisplayName("Should reject null fromAccountId")
        void shouldRejectNullFromAccountId() {
            Money amount = Money.parse("10.00 USD");
            assertThatThrownBy(() -> Transfer.createPending(null, TO_ACCOUNT_ID, amount, "test", USER_ID))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("From account ID must not be null");
        }

        @Test
        @DisplayName("Should reject null toAccountId")
        void shouldRejectNullToAccountId() {
            Money amount = Money.parse("10.00 USD");
            assertThatThrownBy(() -> Transfer.createPending(FROM_ACCOUNT_ID, null, amount, "test", USER_ID))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("To account ID must not be null");
        }

        @Test
        @DisplayName("Should reject null amount")
        void shouldRejectNullAmount() {
            assertThatThrownBy(() -> Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, null, "test", USER_ID))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Amount must not be null");
        }

        @Test
        @DisplayName("Should reject transfer when fromAccount equals toAccount (distinct accounts invariant)")
        void shouldRejectSameAccountTransfer() {
            Money amount = Money.parse("100.00 USD");
            assertThatThrownBy(
                    () -> Transfer.createPending(FROM_ACCOUNT_ID, FROM_ACCOUNT_ID, amount, "Self transfer", USER_ID))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("From account and to account must be distinct");
        }

        @Test
        @DisplayName("Should reject zero or negative transfer amount")
        void shouldRejectNonPositiveTransferAmount() {
            assertThatThrownBy(() -> Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, Money.ofMinor(0L, "USD"),
                    "Zero amount", USER_ID)).isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Transfer amount must be strictly positive");

            assertThatThrownBy(() -> Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID,
                    Money.ofMinor(-5000L, "USD"), "Negative amount", USER_ID))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Transfer amount must be strictly positive");
        }
    }

    @Nested
    @DisplayName("Lifecycle & State Transitions")
    class LifecycleAndStateTransitionTests {

        @Test
        @DisplayName("Should transition from PENDING to COMPLETED via markCompleted()")
        void shouldTransitionFromPendingToCompleted() {
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, Money.parse("100.00 USD"),
                    "Payment", USER_ID);
            assertThat(transfer.isPending()).isTrue();

            transfer.markCompleted();

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(transfer.isCompleted()).isTrue();
            assertThat(transfer.isPending()).isFalse();
            assertThat(transfer.isFailed()).isFalse();
            assertThat(transfer.getFailureReason()).isNull();
        }

        @Test
        @DisplayName("Should transition from PENDING to FAILED via markFailed() with trimmed audit reason")
        void shouldTransitionFromPendingToFailed() {
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, Money.parse("100.00 USD"),
                    "Payment", USER_ID);

            transfer.markFailed("  Account has insufficient funds  ");

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
            assertThat(transfer.isFailed()).isTrue();
            assertThat(transfer.isPending()).isFalse();
            assertThat(transfer.isCompleted()).isFalse();
            assertThat(transfer.getFailureReason()).isEqualTo("Account has insufficient funds");
        }

        @Test
        @DisplayName("Should safely truncate failure reason exceeding 255 characters with ellipsis")
        void shouldTruncateExcessiveFailureReason() {
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, Money.parse("100.00 USD"),
                    "Payment", USER_ID);
            String longReason = "A".repeat(300);

            transfer.markFailed(longReason);

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
            assertThat(transfer.getFailureReason()).hasSize(255);
            assertThat(transfer.getFailureReason()).startsWith("AAAA");
            assertThat(transfer.getFailureReason()).endsWith("...");
            assertThat(transfer.getFailureReason()).isEqualTo("A".repeat(252) + "...");
        }

        @Test
        @DisplayName("Should accept null failure reason on markFailed()")
        void shouldAcceptNullFailureReason() {
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, Money.parse("100.00 USD"),
                    "Payment", USER_ID);

            transfer.markFailed(null);

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
            assertThat(transfer.getFailureReason()).isNull();
        }

        @Test
        @DisplayName("Should reject markCompleted() on already COMPLETED transfer")
        void shouldRejectCompletingAlreadyCompletedTransfer() {
            Transfer transfer = Transfer.createCompleted(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, Money.parse("100.00 USD"),
                    "Payment", USER_ID);

            assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PENDING transfers can be marked COMPLETED, current: COMPLETED");

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should reject markFailed() on already COMPLETED transfer")
        void shouldRejectFailingAlreadyCompletedTransfer() {
            Transfer transfer = Transfer.createCompleted(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, Money.parse("100.00 USD"),
                    "Payment", USER_ID);

            assertThatThrownBy(() -> transfer.markFailed("System error")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PENDING transfers can be marked FAILED, current: COMPLETED");

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should reject markCompleted() on already FAILED transfer")
        void shouldRejectCompletingAlreadyFailedTransfer() {
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, Money.parse("100.00 USD"),
                    "Payment", USER_ID);
            transfer.markFailed("Insufficient funds");

            assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PENDING transfers can be marked COMPLETED, current: FAILED");

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        }

        @Test
        @DisplayName("Should reject markFailed() on already FAILED transfer")
        void shouldRejectFailingAlreadyFailedTransfer() {
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, Money.parse("100.00 USD"),
                    "Payment", USER_ID);
            transfer.markFailed("First failure");

            assertThatThrownBy(() -> transfer.markFailed("Second failure")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only PENDING transfers can be marked FAILED, current: FAILED");

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
            assertThat(transfer.getFailureReason()).isEqualTo("First failure");
        }
    }
}
