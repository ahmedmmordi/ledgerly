package io.github.ahmedmmordi.ledgerly.ledger.account.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedmmordi.ledgerly.ledger.account.Account;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountStatus;
import io.github.ahmedmmordi.ledgerly.ledger.account.AccountType;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Account DTO Records & Mappings")
class AccountDtoTest {

    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-456789abcdef");
    private static final UUID ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-999999abcdef");
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    @DisplayName("CreateAccountRequest Validation Tests")
    class CreateAccountRequestTests {

        @Test
        @DisplayName("Should pass validation with valid attributes")
        void shouldPassWithValidAttributes() {
            CreateAccountRequest request = new CreateAccountRequest(USER_ID, "USD");

            Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);

            assertThat(violations).isEmpty();
            assertThat(request.userId()).isEqualTo(USER_ID);
            assertThat(request.currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("Should normalize lowercase and whitespace currency in constructor")
        void shouldNormalizeCurrencyInConstructor() {
            CreateAccountRequest request = new CreateAccountRequest(USER_ID, "  eur  ");

            assertThat(request.currency()).isEqualTo("EUR");
            Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Should handle null currency without throwing NPE in constructor")
        void shouldHandleNullCurrencyInConstructor() {
            CreateAccountRequest request = new CreateAccountRequest(USER_ID, null);

            Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("currency"));
        }

        @Test
        @DisplayName("Should fail validation when userId is null")
        void shouldFailWhenUserIdIsNull() {
            CreateAccountRequest request = new CreateAccountRequest(null, "USD");

            Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<CreateAccountRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("userId");
            assertThat(violation.getMessage()).isEqualTo("User ID must not be null");
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should fail validation when currency is blank")
        void shouldFailWhenCurrencyIsBlank(String blankCurrency) {
            CreateAccountRequest request = new CreateAccountRequest(USER_ID, blankCurrency);

            Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("currency"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "US", "USDD", "123", "US$", "u$d" })
        @DisplayName("Should fail validation when currency regex does not match 3 uppercase letters")
        void shouldFailWhenCurrencyRegexDoesNotMatch(String invalidCurrency) {
            CreateAccountRequest request = new CreateAccountRequest(USER_ID, invalidCurrency);

            Set<ConstraintViolation<CreateAccountRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("currency")
                    && v.getMessage().contains("3-letter uppercase ISO 4217 code"));
        }
    }

    @Nested
    @DisplayName("AccountResponse Mapping Tests")
    class AccountResponseTests {

        @Test
        @DisplayName("Should correctly map all fields from active Account entity with 2 decimal currency")
        void shouldMapFromActiveAccountWithTwoDecimals() {
            Account account = Account.createCustomer(USER_ID, "ACC-USD-001", "USD");
            account.credit(Money.ofMinor(1050L, "USD"));
            Instant now = Instant.now();
            ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
            ReflectionTestUtils.setField(account, "createdAt", now);
            ReflectionTestUtils.setField(account, "updatedAt", now);

            AccountResponse response = AccountResponse.from(account);

            assertThat(response.id()).isEqualTo(ACCOUNT_ID);
            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response.accountNumber()).isEqualTo("ACC-USD-001");
            assertThat(response.currency()).isEqualTo("USD");
            assertThat(response.balanceInMinorUnits()).isEqualTo(1050L);
            assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("10.50"));
            assertThat(response.type()).isEqualTo(AccountType.CUSTOMER);
            assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(response.createdAt()).isEqualTo(now);
            assertThat(response.updatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should correctly map zero decimal currency (JPY)")
        void shouldMapFromAccountWithZeroDecimals() {
            Account account = Account.createCustomer(USER_ID, "ACC-JPY-001", "JPY");
            account.credit(Money.ofMinor(5000L, "JPY"));
            ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);

            AccountResponse response = AccountResponse.from(account);

            assertThat(response.currency()).isEqualTo("JPY");
            assertThat(response.balanceInMinorUnits()).isEqualTo(5000L);
            assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("5000"));
        }

        @Test
        @DisplayName("Should correctly map 3 decimal currency (BHD)")
        void shouldMapFromAccountWithThreeDecimals() {
            Account account = Account.createCustomer(USER_ID, "ACC-BHD-001", "BHD");
            account.credit(Money.ofMinor(1250L, "BHD"));
            ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);

            AccountResponse response = AccountResponse.from(account);

            assertThat(response.currency()).isEqualTo("BHD");
            assertThat(response.balanceInMinorUnits()).isEqualTo(1250L);
            assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("1.250"));
        }

        @Test
        @DisplayName("Should throw NullPointerException when Account is null")
        void shouldThrowWhenAccountIsNull() {
            assertThatThrownBy(() -> AccountResponse.from(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Account must not be null");
        }
    }
}
