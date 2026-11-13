package io.github.ahmedmmordi.ledgerly.ledger.transfer.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountType;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotActiveException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.CurrencyMismatchException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.InsufficientFundsException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Transfer Validation Rules Tests")
class TransferRulesTest {

    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-456789abcdef");
    private static final UUID ACCOUNT_ID_1 = UUID.fromString("0193297a-9a90-7888-8123-111111111111");
    private static final UUID ACCOUNT_ID_2 = UUID.fromString("0193297a-9a90-7888-8123-222222222222");

    private Account createAccount(String accountNumber, String currency, long initialBalance, AccountType type) {
        Account account = (type == AccountType.CUSTOMER) ? Account.createCustomer(USER_ID, accountNumber, currency)
                : Account.createSystem(USER_ID, accountNumber, currency);
        if (initialBalance > 0L) {
            account.credit(Money.ofMinor(initialBalance, currency));
        }
        return account;
    }

    private Account createAccountWithId(UUID id, String accountNumber, String currency, long initialBalance,
            AccountType type) {
        Account account = createAccount(accountNumber, currency, initialBalance, type);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    @Nested
    @DisplayName("TransferContext Invariants")
    class TransferContextTests {

        @Test
        @DisplayName("Should create valid context when all arguments are non-null")
        void shouldCreateValidContext() {
            Account from = createAccount("ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccount("ACC002", "USD", 0L, AccountType.CUSTOMER);
            Money amount = Money.ofMinor(5_000L, "USD");

            TransferContext context = new TransferContext(from, to, amount);

            assertThat(context.fromAccount()).isSameAs(from);
            assertThat(context.toAccount()).isSameAs(to);
            assertThat(context.amount()).isEqualTo(amount);
        }

        @Test
        @DisplayName("Should reject null fromAccount")
        void shouldRejectNullFromAccount() {
            Account to = createAccount("ACC002", "USD", 0L, AccountType.CUSTOMER);
            Money amount = Money.ofMinor(5_000L, "USD");

            assertThatThrownBy(() -> new TransferContext(null, to, amount)).isInstanceOf(NullPointerException.class)
                    .hasMessage("fromAccount must not be null");
        }

        @Test
        @DisplayName("Should reject null toAccount")
        void shouldRejectNullToAccount() {
            Account from = createAccount("ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Money amount = Money.ofMinor(5_000L, "USD");

            assertThatThrownBy(() -> new TransferContext(from, null, amount)).isInstanceOf(NullPointerException.class)
                    .hasMessage("toAccount must not be null");
        }

        @Test
        @DisplayName("Should reject null amount")
        void shouldRejectNullAmount() {
            Account from = createAccount("ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccount("ACC002", "USD", 0L, AccountType.CUSTOMER);

            assertThatThrownBy(() -> new TransferContext(from, to, null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("amount must not be null");
        }
    }

    @Nested
    @DisplayName("DistinctAccountsRule (@Order 1)")
    class DistinctAccountsRuleTests {

        private final DistinctAccountsRule rule = new DistinctAccountsRule();

        @Test
        @DisplayName("Should have @Order(1)")
        void shouldHaveOrderOne() {
            Integer order = OrderUtils.getOrder(DistinctAccountsRule.class);
            assertThat(order).isEqualTo(1);
        }

        @Test
        @DisplayName("Should pass when accounts have distinct IDs and distinct account numbers")
        void shouldPassForDistinctAccounts() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(5_000L, "USD"));

            assertThatCode(() -> rule.validate(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should pass when accounts have different account numbers even if IDs are unpersisted null")
        void shouldPassWhenIdsAreNull() {
            Account from = createAccount("ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccount("ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(5_000L, "USD"));

            assertThatCode(() -> rule.validate(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should pass when accounts have distinct IDs even if account numbers are null")
        void shouldPassWhenAccountNumbersAreNull() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            ReflectionTestUtils.setField(from, "accountNumber", null);
            ReflectionTestUtils.setField(to, "accountNumber", null);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(1_000L, "USD"));

            assertThatCode(() -> rule.validate(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject when fromAccount and toAccount are the exact same instance")
        void shouldRejectSameReference() {
            Account account = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(account, account, Money.ofMinor(1_000L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("From account and to account must be distinct");
        }

        @Test
        @DisplayName("Should reject when fromAccount and toAccount share the same non-null ID")
        void shouldRejectMatchingIds() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_1, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(1_000L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("From account and to account must be distinct");
        }

        @Test
        @DisplayName("Should reject when fromAccount and toAccount share the same account number")
        void shouldRejectMatchingAccountNumbers() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC001", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(1_000L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("From account and to account must be distinct");
        }
    }

    @Nested
    @DisplayName("PositiveAmountRule (@Order 2)")
    class PositiveAmountRuleTests {

        private final PositiveAmountRule rule = new PositiveAmountRule();

        @Test
        @DisplayName("Should have @Order(2)")
        void shouldHaveOrderTwo() {
            Integer order = OrderUtils.getOrder(PositiveAmountRule.class);
            assertThat(order).isEqualTo(2);
        }

        @Test
        @DisplayName("Should pass when amount is strictly positive")
        void shouldPassForStrictlyPositiveAmount() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);

            assertThatCode(() -> rule.validate(new TransferContext(from, to, Money.ofMinor(1L, "USD"))))
                    .doesNotThrowAnyException();
            assertThatCode(() -> rule.validate(new TransferContext(from, to, Money.ofMinor(100_000L, "USD"))))
                    .doesNotThrowAnyException();
            assertThatCode(() -> rule.validate(new TransferContext(from, to, Money.ofMinor(Long.MAX_VALUE, "USD"))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject zero amount")
        void shouldRejectZeroAmount() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(0L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Transfer amount must be strictly positive");
        }

        @Test
        @DisplayName("Should reject negative amount")
        void shouldRejectNegativeAmount() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(-500L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Transfer amount must be strictly positive");
        }
    }

    @Nested
    @DisplayName("SameCurrencyRule (@Order 3)")
    class SameCurrencyRuleTests {

        private final SameCurrencyRule rule = new SameCurrencyRule();

        @Test
        @DisplayName("Should have @Order(3)")
        void shouldHaveOrderThree() {
            Integer order = OrderUtils.getOrder(SameCurrencyRule.class);
            assertThat(order).isEqualTo(3);
        }

        @Test
        @DisplayName("Should pass when fromAccount, toAccount, and amount have matching currencies")
        void shouldPassWhenAllCurrenciesMatch() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(2_000L, "USD"));

            assertThatCode(() -> rule.validate(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should pass for non-USD matching currencies (EUR, GBP, JPY)")
        void shouldPassForNonUsdCurrencies() {
            Account fromEur = createAccountWithId(ACCOUNT_ID_1, "ACC001", "EUR", 10_000L, AccountType.CUSTOMER);
            Account toEur = createAccountWithId(ACCOUNT_ID_2, "ACC002", "EUR", 0L, AccountType.CUSTOMER);
            TransferContext eurContext = new TransferContext(fromEur, toEur, Money.ofMinor(2_000L, "EUR"));

            assertThatCode(() -> rule.validate(eurContext)).doesNotThrowAnyException();

            Account fromJpy = createAccountWithId(ACCOUNT_ID_1, "ACC003", "JPY", 10_000L, AccountType.CUSTOMER);
            Account toJpy = createAccountWithId(ACCOUNT_ID_2, "ACC004", "JPY", 0L, AccountType.CUSTOMER);
            TransferContext jpyContext = new TransferContext(fromJpy, toJpy, Money.ofMinor(2_000L, "JPY"));

            assertThatCode(() -> rule.validate(jpyContext)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject when fromAccount currency does not match toAccount currency")
        void shouldRejectAccountCurrencyMismatch() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "EUR", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(2_000L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("Currency mismatch: expected USD but was EUR");
        }

        @Test
        @DisplayName("Should reject when transfer amount currency does not match account currency")
        void shouldRejectAmountCurrencyMismatch() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(2_000L, "EUR"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("Currency mismatch: expected USD but was EUR");
        }
    }

    @Nested
    @DisplayName("ActiveAccountsRule (@Order 4)")
    class ActiveAccountsRuleTests {

        private final ActiveAccountsRule rule = new ActiveAccountsRule();

        @Test
        @DisplayName("Should have @Order(4)")
        void shouldHaveOrderFour() {
            Integer order = OrderUtils.getOrder(ActiveAccountsRule.class);
            assertThat(order).isEqualTo(4);
        }

        @Test
        @DisplayName("Should pass when both accounts are active")
        void shouldPassWhenBothAccountsAreActive() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(1_000L, "USD"));

            assertThatCode(() -> rule.validate(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject when fromAccount is FROZEN")
        void shouldRejectFrozenFromAccount() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            from.freeze();
            TransferContext context = new TransferContext(from, to, Money.ofMinor(1_000L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(AccountNotActiveException.class)
                    .hasMessageContaining("Account " + ACCOUNT_ID_1 + " is not active (status: FROZEN)");
        }

        @Test
        @DisplayName("Should reject when fromAccount is CLOSED")
        void shouldRejectClosedFromAccount() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 0L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            from.close();
            TransferContext context = new TransferContext(from, to, Money.ofMinor(1_000L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(AccountNotActiveException.class)
                    .hasMessageContaining("Account " + ACCOUNT_ID_1 + " is not active (status: CLOSED)");
        }

        @Test
        @DisplayName("Should reject when toAccount is FROZEN")
        void shouldRejectFrozenToAccount() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            to.freeze();
            TransferContext context = new TransferContext(from, to, Money.ofMinor(1_000L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(AccountNotActiveException.class)
                    .hasMessageContaining("Account " + ACCOUNT_ID_2 + " is not active (status: FROZEN)");
        }

        @Test
        @DisplayName("Should reject when toAccount is CLOSED")
        void shouldRejectClosedToAccount() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            to.close();
            TransferContext context = new TransferContext(from, to, Money.ofMinor(1_000L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(AccountNotActiveException.class)
                    .hasMessageContaining("Account " + ACCOUNT_ID_2 + " is not active (status: CLOSED)");
        }
    }

    @Nested
    @DisplayName("SufficientBalanceRule (@Order 5)")
    class SufficientBalanceRuleTests {

        private final SufficientBalanceRule rule = new SufficientBalanceRule();

        @Test
        @DisplayName("Should have @Order(5)")
        void shouldHaveOrderFive() {
            Integer order = OrderUtils.getOrder(SufficientBalanceRule.class);
            assertThat(order).isEqualTo(5);
        }

        @Test
        @DisplayName("Should pass when customer account balance exceeds transfer amount")
        void shouldPassWhenBalanceExceedsAmount() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(4_000L, "USD"));

            assertThatCode(() -> rule.validate(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should pass when customer account balance exactly equals transfer amount")
        void shouldPassWhenBalanceEqualsAmount() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 5_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(5_000L, "USD"));

            assertThatCode(() -> rule.validate(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject when customer account balance is less than transfer amount")
        void shouldRejectWhenBalanceIsInsufficient() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 2_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(5_000L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("Account " + ACCOUNT_ID_1 + " has insufficient funds");
        }

        @Test
        @DisplayName("Should reject when customer account balance is 0 and amount is positive")
        void shouldRejectWhenCustomerBalanceIsZero() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 0L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(1L, "USD"));

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("Account " + ACCOUNT_ID_1 + " has insufficient funds");
        }

        @Test
        @DisplayName("Should verify InsufficientFundsException contains accurate domain amounts")
        void shouldVerifyInsufficientFundsExceptionDetails() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 1_500L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            Money transferAmount = Money.ofMinor(5_000L, "USD");
            TransferContext context = new TransferContext(from, to, transferAmount);

            assertThatThrownBy(() -> rule.validate(context)).isInstanceOf(InsufficientFundsException.class)
                    .satisfies(ex -> {
                        InsufficientFundsException ife = (InsufficientFundsException) ex;
                        assertThat(ife.getAccountId()).isEqualTo(ACCOUNT_ID_1);
                        assertThat(ife.getRequestedAmount()).isEqualTo(transferAmount);
                        assertThat(ife.getCurrentBalance()).isEqualTo(Money.ofMinor(1_500L, "USD"));
                    });
        }

        @Test
        @DisplayName("Should pass for SYSTEM account with zero balance (exempt from overdraft)")
        void shouldPassForSystemAccountWithZeroBalance() {
            Account systemFrom = createAccountWithId(ACCOUNT_ID_1, "SYS001", "USD", 0L, AccountType.SYSTEM);
            Account customerTo = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(systemFrom, customerTo, Money.ofMinor(100_000L, "USD"));

            assertThatCode(() -> rule.validate(context)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should pass for SYSTEM account with negative balance")
        void shouldPassForSystemAccountWithNegativeBalance() {
            Account systemFrom = createAccountWithId(ACCOUNT_ID_1, "SYS001", "USD", 0L, AccountType.SYSTEM);
            // Debit system account so its balance becomes negative
            systemFrom.debit(Money.ofMinor(50_000L, "USD"));
            assertThat(systemFrom.getBalance().amountInMinorUnits()).isEqualTo(-50_000L);

            Account customerTo = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(systemFrom, customerTo, Money.ofMinor(25_000L, "USD"));

            assertThatCode(() -> rule.validate(context)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Rule Pipeline & Ordering Integration")
    class RulePipelineIntegrationTests {

        private final List<TransferRule> orderedRules = List
                .of(new DistinctAccountsRule(), new PositiveAmountRule(), new SameCurrencyRule(),
                        new ActiveAccountsRule(), new SufficientBalanceRule())
                .stream()
                .sorted(Comparator.comparingInt(rule -> OrderUtils.getOrder(rule.getClass(), Integer.MAX_VALUE)))
                .toList();

        @Test
        @DisplayName("Should execute all rules sequentially for a completely valid transfer")
        void shouldPassAllRulesForValidTransfer() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 10_000L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            TransferContext context = new TransferContext(from, to, Money.ofMinor(5_000L, "USD"));

            assertThatCode(() -> orderedRules.forEach(rule -> rule.validate(context))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Stage 1 Fail-Fast: DistinctAccountsRule fires before magnitude, currency, or balance")
        void shouldFailFastOnDistinctAccountsRule() {
            Account account = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 0L, AccountType.CUSTOMER);
            // Even though balance is 0 and amount is negative, DistinctAccountsRule should fire first
            TransferContext context = new TransferContext(account, account, Money.ofMinor(-100L, "USD"));

            assertThatThrownBy(() -> orderedRules.forEach(rule -> rule.validate(context)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("From account and to account must be distinct");
        }

        @Test
        @DisplayName("Stage 2 Fail-Fast: PositiveAmountRule fires before currency, status, or balance")
        void shouldFailFastOnPositiveAmountRule() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 0L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "EUR", 0L, AccountType.CUSTOMER);
            // Negative amount should fail at rule 2, before currency mismatch at rule 3
            TransferContext context = new TransferContext(from, to, Money.ofMinor(-50L, "USD"));

            assertThatThrownBy(() -> orderedRules.forEach(rule -> rule.validate(context)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Transfer amount must be strictly positive");
        }

        @Test
        @DisplayName("Stage 3 Fail-Fast: SameCurrencyRule fires before active accounts or balance")
        void shouldFailFastOnSameCurrencyRule() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 0L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "EUR", 0L, AccountType.CUSTOMER);
            // Currency mismatch should be caught at order 3, before order 5 balance check
            TransferContext context = new TransferContext(from, to, Money.ofMinor(10_000L, "USD"));

            assertThatThrownBy(() -> orderedRules.forEach(rule -> rule.validate(context)))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("Currency mismatch: expected USD but was EUR");
        }

        @Test
        @DisplayName("Stage 4 Fail-Fast: ActiveAccountsRule fires before balance check")
        void shouldFailFastOnActiveAccountsRule() {
            Account from = createAccountWithId(ACCOUNT_ID_1, "ACC001", "USD", 0L, AccountType.CUSTOMER);
            Account to = createAccountWithId(ACCOUNT_ID_2, "ACC002", "USD", 0L, AccountType.CUSTOMER);
            from.freeze();
            // Inactive account should fail at rule 4, before insufficient balance at rule 5
            TransferContext context = new TransferContext(from, to, Money.ofMinor(5_000L, "USD"));

            assertThatThrownBy(() -> orderedRules.forEach(rule -> rule.validate(context)))
                    .isInstanceOf(AccountNotActiveException.class)
                    .hasMessageContaining("is not active (status: FROZEN)");
        }
    }
}
