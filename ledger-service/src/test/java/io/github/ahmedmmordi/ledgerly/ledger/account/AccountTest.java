package io.github.ahmedmmordi.ledgerly.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotActiveException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.CurrencyMismatchException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.InsufficientFundsException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Account Domain Entity Tests")
class AccountTest {

    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-456789abcdef");

    @Nested
    @DisplayName("Account Creation & Normalization")
    class CreationAndNormalizationTests {

        @Test
        @DisplayName("Should create customer account with normalized attributes and initial zero balance")
        void shouldCreateCustomerAccountWithZeroBalance() {
            Account account = Account.createCustomer(USER_ID, "  acc-cust-001  ", "usd");

            assertThat(account.getUserId()).isEqualTo(USER_ID);
            assertThat(account.getAccountNumber()).isEqualTo("ACC-CUST-001");
            assertThat(account.getCurrency()).isEqualTo("USD");
            assertThat(account.getType()).isEqualTo(AccountType.CUSTOMER);
            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.isActive()).isTrue();
            assertThat(account.getBalance()).isEqualTo(Money.ofMinor(0L, "USD"));
            assertThat(account.getBalance().amountInMinorUnits()).isZero();
        }

        @Test
        @DisplayName("Should create system account with SYSTEM type")
        void shouldCreateSystemAccount() {
            Account account = Account.createSystem(USER_ID, "SYS-FLOAT-01", "EUR");

            assertThat(account.getType()).isEqualTo(AccountType.SYSTEM);
            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.getAccountNumber()).isEqualTo("SYS-FLOAT-01");
            assertThat(account.getCurrency()).isEqualTo("EUR");
        }

        @Test
        @DisplayName("Should reject null user ID")
        void shouldRejectNullUserId() {
            assertThatThrownBy(() -> Account.createCustomer(null, "ACC-01", "USD"))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("User ID must not be null");
        }

        @Test
        @DisplayName("Should reject null account number")
        void shouldRejectNullAccountNumber() {
            assertThatThrownBy(() -> Account.createCustomer(USER_ID, null, "USD"))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Account number must not be null");
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   ", "\t", "\n" })
        @DisplayName("Should reject blank or empty account number")
        void shouldRejectBlankAccountNumber(String blankAccountNumber) {
            assertThatThrownBy(() -> Account.createCustomer(USER_ID, blankAccountNumber, "USD"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("Should reject null currency")
        void shouldRejectNullCurrency() {
            assertThatThrownBy(() -> Account.createCustomer(USER_ID, "ACC-01", null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Currency must not be null");
        }

        @ParameterizedTest
        @ValueSource(strings = { "INVALID", "US", "USDD", "123", "" })
        @DisplayName("Should reject invalid ISO 4217 currency code")
        void shouldRejectInvalidCurrencyCode(String invalidCurrency) {
            assertThatThrownBy(() -> Account.createCustomer(USER_ID, "ACC-01", invalidCurrency))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Credit Operations")
    class CreditTests {

        @Test
        @DisplayName("Should credit money to account and update balance correctly")
        void shouldCreditAmountSuccessfully() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");

            account.credit(Money.parse("150.75 USD"));

            assertThat(account.getBalance()).isEqualTo(Money.parse("150.75 USD"));
            assertThat(account.getBalance().amountInMinorUnits()).isEqualTo(15075L);
        }

        @Test
        @DisplayName("Should accumulate multiple credits correctly")
        void shouldAccumulateMultipleCredits() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");

            account.credit(Money.parse("100.00 USD"));
            account.credit(Money.parse("50.25 USD"));
            account.credit(Money.parse("0.75 USD"));

            assertThat(account.getBalance()).isEqualTo(Money.parse("151.00 USD"));
            assertThat(account.getBalance().amountInMinorUnits()).isEqualTo(15100L);
        }

        @Test
        @DisplayName("Should reject credit with null amount")
        void shouldRejectNullAmountOnCredit() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");

            assertThatThrownBy(() -> account.credit(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Amount must not be null");
        }

        @Test
        @DisplayName("Should reject credit of zero or negative amount")
        void shouldRejectNonPositiveCreditAmount() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");

            assertThatThrownBy(() -> account.credit(Money.ofMinor(0L, "USD")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Credit amount must be strictly positive");

            assertThatThrownBy(() -> account.credit(Money.ofMinor(-500L, "USD")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Credit amount must be strictly positive");
        }

        @Test
        @DisplayName("Should reject credit with currency mismatch")
        void shouldRejectCreditWithCurrencyMismatch() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");

            assertThatThrownBy(() -> account.credit(Money.parse("100.00 EUR")))
                    .isInstanceOf(CurrencyMismatchException.class).hasMessageContaining("expected USD but was EUR");
        }

        @Test
        @DisplayName("Should reject credit when account is frozen")
        void shouldRejectCreditWhenAccountFrozen() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.freeze();

            assertThatThrownBy(() -> account.credit(Money.parse("100.00 USD")))
                    .isInstanceOf(AccountNotActiveException.class).satisfies(ex -> {
                        AccountNotActiveException e = (AccountNotActiveException) ex;
                        assertThat(e.getStatus()).isEqualTo("FROZEN");
                    });
        }

        @Test
        @DisplayName("Should reject credit when account is closed")
        void shouldRejectCreditWhenAccountClosed() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.close();

            assertThatThrownBy(() -> account.credit(Money.parse("100.00 USD")))
                    .isInstanceOf(AccountNotActiveException.class).satisfies(ex -> {
                        AccountNotActiveException e = (AccountNotActiveException) ex;
                        assertThat(e.getStatus()).isEqualTo("CLOSED");
                    });
        }

        @Test
        @DisplayName("Should throw ArithmeticException on balance addition overflow")
        void shouldThrowOnCreditOverflow() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.ofMinor(Long.MAX_VALUE - 100L, "USD"));

            assertThatThrownBy(() -> account.credit(Money.ofMinor(101L, "USD")))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("Debit Operations")
    class DebitTests {

        @Test
        @DisplayName("Should debit money successfully when customer has sufficient balance")
        void shouldDebitAmountSuccessfully() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.parse("200.00 USD"));

            account.debit(Money.parse("75.50 USD"));

            assertThat(account.getBalance()).isEqualTo(Money.parse("124.50 USD"));
            assertThat(account.getBalance().amountInMinorUnits()).isEqualTo(12450L);
        }

        @Test
        @DisplayName("Should allow debiting exact balance down to zero")
        void shouldAllowDebitingExactBalance() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.parse("100.00 USD"));

            account.debit(Money.parse("100.00 USD"));

            assertThat(account.getBalance().amountInMinorUnits()).isZero();
            assertThat(account.getBalance()).isEqualTo(Money.ofMinor(0L, "USD"));
        }

        @Test
        @DisplayName("Should deduct multiple debits successively")
        void shouldDeductMultipleDebits() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.parse("100.00 USD"));

            account.debit(Money.parse("30.00 USD"));
            account.debit(Money.parse("20.00 USD"));
            account.debit(Money.parse("50.00 USD"));

            assertThat(account.getBalance()).isEqualTo(Money.ofMinor(0L, "USD"));
        }

        @Test
        @DisplayName("Should reject debit with null amount")
        void shouldRejectNullAmountOnDebit() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");

            assertThatThrownBy(() -> account.debit(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Amount must not be null");
        }

        @Test
        @DisplayName("Should reject customer debit exceeding current balance (overdraft protection) and verify exception details")
        void shouldRejectCustomerDebitExceedingBalance() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.parse("50.00 USD"));

            assertThatThrownBy(() -> account.debit(Money.parse("50.01 USD")))
                    .isInstanceOf(InsufficientFundsException.class).satisfies(ex -> {
                        InsufficientFundsException ife = (InsufficientFundsException) ex;
                        assertThat(ife.getRequestedAmount()).isEqualTo(Money.parse("50.01 USD"));
                        assertThat(ife.getCurrentBalance()).isEqualTo(Money.parse("50.00 USD"));
                    });

            assertThat(account.getBalance()).isEqualTo(Money.parse("50.00 USD"));
        }

        @Test
        @DisplayName("Should allow system account to overdraft into negative balance")
        void shouldAllowSystemAccountDebitExceedingBalance() {
            Account systemAccount = Account.createSystem(USER_ID, "SYS-RESERVE-01", "USD");

            systemAccount.debit(Money.parse("1000.00 USD"));

            assertThat(systemAccount.getBalance().amountInMinorUnits()).isEqualTo(-100000L);
            assertThat(systemAccount.getBalance()).isEqualTo(Money.ofMinor(-100000L, "USD"));
        }

        @Test
        @DisplayName("Should reject debit of zero or negative amount")
        void shouldRejectNonPositiveDebitAmount() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.parse("100.00 USD"));

            assertThatThrownBy(() -> account.debit(Money.ofMinor(0L, "USD")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Debit amount must be strictly positive");

            assertThatThrownBy(() -> account.debit(Money.ofMinor(-1000L, "USD")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Debit amount must be strictly positive");
        }

        @Test
        @DisplayName("Should reject debit with currency mismatch")
        void shouldRejectDebitWithCurrencyMismatch() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.parse("100.00 USD"));

            assertThatThrownBy(() -> account.debit(Money.parse("50.00 EUR")))
                    .isInstanceOf(CurrencyMismatchException.class).hasMessageContaining("expected USD but was EUR");
        }

        @Test
        @DisplayName("Should reject debit when account is frozen")
        void shouldRejectDebitWhenAccountFrozen() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.parse("100.00 USD"));
            account.freeze();

            assertThatThrownBy(() -> account.debit(Money.parse("10.00 USD")))
                    .isInstanceOf(AccountNotActiveException.class).satisfies(ex -> {
                        AccountNotActiveException e = (AccountNotActiveException) ex;
                        assertThat(e.getStatus()).isEqualTo("FROZEN");
                    });
        }

        @Test
        @DisplayName("Should reject debit when account is closed")
        void shouldRejectDebitWhenAccountClosed() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.close();

            assertThatThrownBy(() -> account.debit(Money.parse("10.00 USD")))
                    .isInstanceOf(AccountNotActiveException.class).satisfies(ex -> {
                        AccountNotActiveException e = (AccountNotActiveException) ex;
                        assertThat(e.getStatus()).isEqualTo("CLOSED");
                    });
        }

        @Test
        @DisplayName("Should throw ArithmeticException on balance subtraction underflow")
        void shouldThrowOnDebitUnderflow() {
            Account systemAccount = Account.createSystem(USER_ID, "SYS-01", "USD");
            systemAccount.debit(Money.ofMinor(Long.MAX_VALUE, "USD"));
            systemAccount.debit(Money.ofMinor(1L, "USD")); // balance is now exactly Long.MIN_VALUE

            assertThatThrownBy(() -> systemAccount.debit(Money.ofMinor(1L, "USD")))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("Lifecycle & Status Transitions")
    class StatusAndLifecycleTests {

        @Test
        @DisplayName("Should freeze and re-activate account")
        void shouldFreezeAndActivateAccount() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            assertThat(account.isActive()).isTrue();

            account.freeze();
            assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
            assertThat(account.isActive()).isFalse();

            account.activate();
            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.isActive()).isTrue();
        }

        @Test
        @DisplayName("Should reject activating a closed account (closed is terminal)")
        void shouldRejectActivatingClosedAccount() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.close();

            assertThatThrownBy(account::activate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot activate a closed account");

            assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        @DisplayName("Should reject freezing a closed account (closed is terminal)")
        void shouldRejectFreezingClosedAccount() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.close();

            assertThatThrownBy(account::freeze).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot freeze a closed account");

            assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        @DisplayName("Should close account when balance is exactly zero")
        void shouldCloseAccountWithZeroBalance() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");

            account.close();

            assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
            assertThat(account.isActive()).isFalse();
        }

        @Test
        @DisplayName("Should reject closing account when balance is positive")
        void shouldRejectClosingAccountWithPositiveBalance() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.parse("10.00 USD"));

            assertThatThrownBy(account::close).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot close account with non-zero balance: 1000");

            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should reject closing system account when balance is negative")
        void shouldRejectClosingAccountWithNegativeBalance() {
            Account systemAccount = Account.createSystem(USER_ID, "SYS-01", "USD");
            systemAccount.debit(Money.parse("10.00 USD"));

            assertThatThrownBy(systemAccount::close).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot close account with non-zero balance: -1000");
        }

        @Test
        @DisplayName("Should verify hasSufficientBalance correctly for customer and system accounts")
        void shouldCheckHasSufficientBalance() {
            Account customerAccount = Account.createCustomer(USER_ID, "ACC-01", "USD");
            customerAccount.credit(Money.parse("100.00 USD"));

            assertThat(customerAccount.hasSufficientBalance(Money.parse("100.00 USD"))).isTrue();
            assertThat(customerAccount.hasSufficientBalance(Money.parse("50.00 USD"))).isTrue();
            assertThat(customerAccount.hasSufficientBalance(Money.parse("100.01 USD"))).isFalse();

            Account systemAccount = Account.createSystem(USER_ID, "SYS-01", "USD");
            assertThat(systemAccount.hasSufficientBalance(Money.parse("999999.00 USD"))).isTrue();
        }

        @Test
        @DisplayName("Should reject hasSufficientBalance when query currency does not match account currency")
        void shouldRejectHasSufficientBalanceWithCurrencyMismatch() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");
            account.credit(Money.parse("100.00 USD"));

            assertThatThrownBy(() -> account.hasSufficientBalance(Money.parse("50.00 EUR")))
                    .isInstanceOf(CurrencyMismatchException.class).hasMessageContaining("expected USD but was EUR");
        }

        @Test
        @DisplayName("Should reject hasSufficientBalance with null amount")
        void shouldRejectHasSufficientBalanceWithNullAmount() {
            Account account = Account.createCustomer(USER_ID, "ACC-01", "USD");

            assertThatThrownBy(() -> account.hasSufficientBalance(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Amount must not be null");
        }
    }
}
