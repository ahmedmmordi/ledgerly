package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedmmordi.ledgerly.ledger.domain.CurrencyMismatchException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LedgerEntry Domain Entity Tests")
class LedgerEntryTest {

    private static final UUID TRANSFER_ID = UUID.fromString("0193297a-9a90-7888-8123-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-bbbbbbbbbbbb");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Nested
    @DisplayName("Debit Entry Creation")
    class DebitEntryCreationTests {

        @Test
        @DisplayName("Should create DEBIT entry with valid parameters")
        void shouldCreateDebitEntryWithValidParameters() {
            Money amount = Money.parse("100.50 USD");
            Money balanceAfter = Money.parse("250.00 USD");

            LedgerEntry entry = LedgerEntry.createDebit(TRANSFER_ID, ACCOUNT_ID, amount, balanceAfter);

            assertThat(entry.getTransferId()).isEqualTo(TRANSFER_ID);
            assertThat(entry.getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(entry.getType()).isEqualTo(EntryType.DEBIT);
            assertThat(entry.isDebit()).isTrue();
            assertThat(entry.isCredit()).isFalse();
            assertThat(entry.getAmount()).isEqualTo(10050L);
            assertThat(entry.getBalanceAfter()).isEqualTo(25000L);
            assertThat(entry.getAmount(USD)).isEqualTo(amount);
            assertThat(entry.getBalanceAfter(USD)).isEqualTo(balanceAfter);
            assertThat(entry.getId()).isNull();
            assertThat(entry.getCreatedAt()).isNull();
        }

        @Test
        @DisplayName("Should allow zero balance_after on debit entry (drained account)")
        void shouldSupportZeroBalanceAfterOnDebit() {
            Money amount = Money.parse("50.00 USD");
            Money balanceAfter = Money.parse("0.00 USD");

            LedgerEntry entry = LedgerEntry.createDebit(TRANSFER_ID, ACCOUNT_ID, amount, balanceAfter);

            assertThat(entry.getBalanceAfter()).isZero();
            assertThat(entry.getBalanceAfter(USD)).isEqualTo(balanceAfter);
        }

        @Test
        @DisplayName("Should allow negative balance_after on debit entry for system account overdraft")
        void shouldSupportNegativeBalanceAfterOnDebit() {
            Money amount = Money.parse("1000.00 USD");
            Money negativeBalanceAfter = Money.parse("-1000.00 USD");

            LedgerEntry entry = LedgerEntry.createDebit(TRANSFER_ID, ACCOUNT_ID, amount, negativeBalanceAfter);

            assertThat(entry.getBalanceAfter()).isEqualTo(-100000L);
            assertThat(entry.getBalanceAfter(USD)).isEqualTo(negativeBalanceAfter);
        }
    }

    @Nested
    @DisplayName("Credit Entry Creation")
    class CreditEntryCreationTests {

        @Test
        @DisplayName("Should create CREDIT entry with valid parameters")
        void shouldCreateCreditEntryWithValidParameters() {
            Money amount = Money.parse("75.25 USD");
            Money balanceAfter = Money.parse("325.25 USD");

            LedgerEntry entry = LedgerEntry.createCredit(TRANSFER_ID, ACCOUNT_ID, amount, balanceAfter);

            assertThat(entry.getTransferId()).isEqualTo(TRANSFER_ID);
            assertThat(entry.getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(entry.getType()).isEqualTo(EntryType.CREDIT);
            assertThat(entry.isCredit()).isTrue();
            assertThat(entry.isDebit()).isFalse();
            assertThat(entry.getAmount()).isEqualTo(7525L);
            assertThat(entry.getBalanceAfter()).isEqualTo(32525L);
            assertThat(entry.getAmount(USD)).isEqualTo(amount);
            assertThat(entry.getBalanceAfter(USD)).isEqualTo(balanceAfter);
            assertThat(entry.getId()).isNull();
            assertThat(entry.getCreatedAt()).isNull();
        }

        @Test
        @DisplayName("Should allow negative balance_after on credit entry when system account is recovering from overdraft")
        void shouldSupportNegativeBalanceAfterOnCreditForSystemAccount() {
            Money amount = Money.parse("400.00 USD");
            Money negativeBalanceAfter = Money.parse("-600.00 USD");

            LedgerEntry entry = LedgerEntry.createCredit(TRANSFER_ID, ACCOUNT_ID, amount, negativeBalanceAfter);

            assertThat(entry.getBalanceAfter()).isEqualTo(-60000L);
            assertThat(entry.getBalanceAfter(USD)).isEqualTo(negativeBalanceAfter);
        }
    }

    @Nested
    @DisplayName("Validation & Invariants")
    class ValidationAndInvariantTests {

        @Test
        @DisplayName("Should reject null transferId")
        void shouldRejectNullTransferId() {
            Money amount = Money.parse("10.00 USD");
            Money balanceAfter = Money.parse("100.00 USD");

            assertThatThrownBy(() -> LedgerEntry.createDebit(null, ACCOUNT_ID, amount, balanceAfter))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Transfer ID must not be null");
        }

        @Test
        @DisplayName("Should reject null accountId")
        void shouldRejectNullAccountId() {
            Money amount = Money.parse("10.00 USD");
            Money balanceAfter = Money.parse("100.00 USD");

            assertThatThrownBy(() -> LedgerEntry.createDebit(TRANSFER_ID, null, amount, balanceAfter))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Account ID must not be null");
        }

        @Test
        @DisplayName("Should reject null amount")
        void shouldRejectNullAmount() {
            Money balanceAfter = Money.parse("100.00 USD");

            assertThatThrownBy(() -> LedgerEntry.createDebit(TRANSFER_ID, ACCOUNT_ID, null, balanceAfter))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Amount must not be null");
        }

        @Test
        @DisplayName("Should reject null balanceAfter")
        void shouldRejectNullBalanceAfter() {
            Money amount = Money.parse("10.00 USD");

            assertThatThrownBy(() -> LedgerEntry.createDebit(TRANSFER_ID, ACCOUNT_ID, amount, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Balance after must not be null");
        }

        @Test
        @DisplayName("Should reject entry when amount currency and balanceAfter currency mismatch")
        void shouldRejectCurrencyMismatchBetweenAmountAndBalanceAfter() {
            Money amount = Money.ofMinor(5000L, USD);
            Money balanceAfter = Money.ofMinor(15000L, EUR);

            assertThatThrownBy(() -> LedgerEntry.createDebit(TRANSFER_ID, ACCOUNT_ID, amount, balanceAfter))
                    .isInstanceOf(CurrencyMismatchException.class).hasMessageContaining("expected USD but was EUR");

            assertThatThrownBy(() -> LedgerEntry.createCredit(TRANSFER_ID, ACCOUNT_ID, amount, balanceAfter))
                    .isInstanceOf(CurrencyMismatchException.class).hasMessageContaining("expected USD but was EUR");
        }

        @Test
        @DisplayName("Should reject zero or negative ledger entry amount")
        void shouldRejectNonPositiveAmount() {
            Money balanceAfter = Money.parse("100.00 USD");

            assertThatThrownBy(
                    () -> LedgerEntry.createDebit(TRANSFER_ID, ACCOUNT_ID, Money.ofMinor(0L, "USD"), balanceAfter))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Ledger entry amount must be strictly positive");

            assertThatThrownBy(
                    () -> LedgerEntry.createCredit(TRANSFER_ID, ACCOUNT_ID, Money.ofMinor(-500L, "USD"), balanceAfter))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Ledger entry amount must be strictly positive");
        }
    }

    @Nested
    @DisplayName("Helper Methods & Currency Conversions")
    class HelperAndConversionTests {

        @Test
        @DisplayName("Should convert amount and balanceAfter to Money with given Currency")
        void shouldConvertAmountAndBalanceAfterToMoney() {
            Money amount = Money.parse("150.00 USD");
            Money balanceAfter = Money.parse("500.00 USD");
            LedgerEntry entry = LedgerEntry.createCredit(TRANSFER_ID, ACCOUNT_ID, amount, balanceAfter);

            assertThat(entry.getAmount(USD)).isEqualTo(amount);
            assertThat(entry.getBalanceAfter(USD)).isEqualTo(balanceAfter);
        }

        @Test
        @DisplayName("Should support non-USD currency (EUR) for amount and balanceAfter conversion")
        void shouldSupportNonUsdCurrency() {
            Money amount = Money.ofMinor(7500L, EUR);
            Money balanceAfter = Money.ofMinor(22500L, EUR);

            LedgerEntry entry = LedgerEntry.createCredit(TRANSFER_ID, ACCOUNT_ID, amount, balanceAfter);

            assertThat(entry.getAmount(EUR)).isEqualTo(amount);
            assertThat(entry.getBalanceAfter(EUR)).isEqualTo(balanceAfter);
        }

        @Test
        @DisplayName("Should reject null currency in getAmount() and getBalanceAfter()")
        void shouldRejectNullCurrencyInConversionMethods() {
            Money amount = Money.parse("150.00 USD");
            Money balanceAfter = Money.parse("500.00 USD");
            LedgerEntry entry = LedgerEntry.createCredit(TRANSFER_ID, ACCOUNT_ID, amount, balanceAfter);

            assertThatThrownBy(() -> entry.getAmount((Currency) null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Currency must not be null");

            assertThatThrownBy(() -> entry.getBalanceAfter((Currency) null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Currency must not be null");
        }

        @Test
        @DisplayName("Should support maximum valid 64-bit integer amount and balanceAfter")
        void shouldSupportMaximumValidAmount() {
            Money maxMoney = Money.ofMinor(Long.MAX_VALUE, "USD");
            LedgerEntry entry = LedgerEntry.createCredit(TRANSFER_ID, ACCOUNT_ID, maxMoney, maxMoney);

            assertThat(entry.getAmount()).isEqualTo(Long.MAX_VALUE);
            assertThat(entry.getBalanceAfter()).isEqualTo(Long.MAX_VALUE);
            assertThat(entry.getAmount(USD)).isEqualTo(maxMoney);
            assertThat(entry.getBalanceAfter(USD)).isEqualTo(maxMoney);
        }
    }
}
