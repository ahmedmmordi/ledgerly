package io.github.ahmedmmordi.ledgerly.ledger.transfer.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.EntryType;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.LedgerEntry;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.Transfer;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.TransferStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Transfer DTO Records & Mappings")
class TransferDtoTest {

    private static final UUID FROM_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-111111111111");
    private static final UUID TO_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-222222222222");
    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-333333333333");
    private static final UUID TRANSFER_ID = UUID.fromString("0193297a-9a90-7888-8123-444444444444");
    private static final UUID ENTRY_ID = UUID.fromString("0193297a-9a90-7888-8123-555555555555");
    private static final Instant TIMESTAMP = Instant.parse("2026-11-14T20:00:00Z");

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Nested
    @DisplayName("TransferRequest Validation Tests")
    class TransferRequestTests {

        @Test
        @DisplayName("Should pass validation with valid attributes")
        void shouldPassWithValidAttributes() {
            TransferRequest request = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "USD", "Rent payment",
                    USER_ID);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

            assertThat(violations).isEmpty();
            assertThat(request.fromAccountId()).isEqualTo(FROM_ACCOUNT_ID);
            assertThat(request.toAccountId()).isEqualTo(TO_ACCOUNT_ID);
            assertThat(request.amount()).isEqualTo(5000L);
            assertThat(request.currency()).isEqualTo("USD");
            assertThat(request.description()).isEqualTo("Rent payment");
            assertThat(request.initiatedByUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Should normalize lowercase and whitespace currency in constructor")
        void shouldNormalizeCurrencyInConstructor() {
            TransferRequest request = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 1000L, "  eur  ", "Payment",
                    null);

            assertThat(request.currency()).isEqualTo("EUR");
            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Should trim description and convert blank description to null")
        void shouldTrimDescriptionAndConvertBlankToNull() {
            TransferRequest request1 = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 1000L, "USD",
                    "   Payment note   ", null);
            TransferRequest request2 = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 1000L, "USD", "     ", null);

            assertThat(request1.description()).isEqualTo("Payment note");
            assertThat(request2.description()).isNull();
        }

        @Test
        @DisplayName("Should fail validation when fromAccountId is null")
        void shouldFailWhenFromAccountIdIsNull() {
            TransferRequest request = new TransferRequest(null, TO_ACCOUNT_ID, 1000L, "USD", "Note", null);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<TransferRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("fromAccountId");
            assertThat(violation.getMessage()).isEqualTo("From account ID must not be null");
        }

        @Test
        @DisplayName("Should fail validation when toAccountId is null")
        void shouldFailWhenToAccountIdIsNull() {
            TransferRequest request = new TransferRequest(FROM_ACCOUNT_ID, null, 1000L, "USD", "Note", null);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<TransferRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("toAccountId");
            assertThat(violation.getMessage()).isEqualTo("To account ID must not be null");
        }

        @Test
        @DisplayName("Should fail validation when amount is null")
        void shouldFailWhenAmountIsNull() {
            TransferRequest request = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, null, "USD", "Note", null);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<TransferRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("amount");
            assertThat(violation.getMessage()).isEqualTo("Amount must not be null");
        }

        @ParameterizedTest
        @ValueSource(longs = { 0L, -1L, -5000L })
        @DisplayName("Should fail validation when amount is not positive")
        void shouldFailWhenAmountIsNotPositive(long nonPositiveAmount) {
            TransferRequest request = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, nonPositiveAmount, "USD",
                    "Note", null);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("amount")
                    && v.getMessage().equals("Amount must be strictly positive"));
        }

        @Test
        @DisplayName("Should fail validation when currency is null")
        void shouldFailWhenCurrencyIsNull() {
            TransferRequest request = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 1000L, null, "Note", null);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("currency")
                    && v.getMessage().equals("Currency must not be blank"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should fail validation when currency is blank")
        void shouldFailWhenCurrencyIsBlank(String blankCurrency) {
            TransferRequest request = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 1000L, blankCurrency, "Note",
                    null);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("currency")
                    && v.getMessage().equals("Currency must not be blank"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "US", "USDD", "123", "US$", "U$D" })
        @DisplayName("Should fail validation when currency pattern does not match 3 uppercase letters")
        void shouldFailWhenCurrencyPatternIsInvalid(String invalidCurrency) {
            TransferRequest request = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 1000L, invalidCurrency,
                    "Note", null);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("currency")
                    && v.getMessage().equals("Currency must be a valid 3-letter uppercase ISO 4217 code"));
        }

        @Test
        @DisplayName("Should fail validation when description exceeds 255 characters")
        void shouldFailWhenDescriptionExceeds255Chars() {
            String longDescription = "A".repeat(256);
            TransferRequest request = new TransferRequest(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 1000L, "USD", longDescription,
                    null);

            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<TransferRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("description");
            assertThat(violation.getMessage()).isEqualTo("Description must not exceed 255 characters");
        }
    }

    @Nested
    @DisplayName("DepositRequest Validation Tests")
    class DepositRequestTests {

        @Test
        @DisplayName("Should pass validation with valid attributes")
        void shouldPassWithValidAttributes() {
            DepositRequest request = new DepositRequest(TO_ACCOUNT_ID, 10000L, "USD", "Initial deposit", USER_ID);

            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

            assertThat(violations).isEmpty();
            assertThat(request.accountId()).isEqualTo(TO_ACCOUNT_ID);
            assertThat(request.amount()).isEqualTo(10000L);
            assertThat(request.currency()).isEqualTo("USD");
            assertThat(request.description()).isEqualTo("Initial deposit");
            assertThat(request.initiatedByUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("Should normalize lowercase and whitespace currency in constructor")
        void shouldNormalizeCurrencyInConstructor() {
            DepositRequest request = new DepositRequest(TO_ACCOUNT_ID, 5000L, "  gbp  ", null, USER_ID);

            assertThat(request.currency()).isEqualTo("GBP");
            assertThat(request.description()).isNull();
            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Should fail validation when accountId is null")
        void shouldFailWhenAccountIdIsNull() {
            DepositRequest request = new DepositRequest(null, 5000L, "USD", null, USER_ID);

            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<DepositRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("accountId");
            assertThat(violation.getMessage()).isEqualTo("Account ID must not be null");
        }

        @Test
        @DisplayName("Should fail validation when amount is null")
        void shouldFailWhenAmountIsNull() {
            DepositRequest request = new DepositRequest(TO_ACCOUNT_ID, null, "USD", "Deposit", USER_ID);

            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<DepositRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("amount");
            assertThat(violation.getMessage()).isEqualTo("Amount must not be null");
        }

        @ParameterizedTest
        @ValueSource(longs = { 0L, -100L })
        @DisplayName("Should fail validation when deposit amount is not positive")
        void shouldFailWhenAmountIsNotPositive(long nonPositiveAmount) {
            DepositRequest request = new DepositRequest(TO_ACCOUNT_ID, nonPositiveAmount, "USD", null, USER_ID);

            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("amount")
                    && v.getMessage().equals("Amount must be strictly positive"));
        }

        @Test
        @DisplayName("Should fail validation when currency is null")
        void shouldFailWhenCurrencyIsNull() {
            DepositRequest request = new DepositRequest(TO_ACCOUNT_ID, 5000L, null, "Deposit", USER_ID);

            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("currency")
                    && v.getMessage().equals("Currency must not be blank"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should fail validation when currency is blank")
        void shouldFailWhenCurrencyIsBlank(String blankCurrency) {
            DepositRequest request = new DepositRequest(TO_ACCOUNT_ID, 5000L, blankCurrency, "Deposit", USER_ID);

            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("currency")
                    && v.getMessage().equals("Currency must not be blank"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "US", "USDD", "123", "US$", "U$D" })
        @DisplayName("Should fail validation when currency pattern does not match 3 uppercase letters")
        void shouldFailWhenCurrencyPatternIsInvalid(String invalidCurrency) {
            DepositRequest request = new DepositRequest(TO_ACCOUNT_ID, 5000L, invalidCurrency, "Deposit", USER_ID);

            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("currency")
                    && v.getMessage().equals("Currency must be a valid 3-letter uppercase ISO 4217 code"));
        }

        @Test
        @DisplayName("Should fail validation when description exceeds 255 characters")
        void shouldFailWhenDescriptionExceeds255Chars() {
            String longDescription = "A".repeat(256);
            DepositRequest request = new DepositRequest(TO_ACCOUNT_ID, 5000L, "USD", longDescription, USER_ID);

            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<DepositRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("description");
            assertThat(violation.getMessage()).isEqualTo("Description must not exceed 255 characters");
        }

        @Test
        @DisplayName("Should fail validation when initiatedByUserId is null")
        void shouldFailWhenInitiatedByUserIdIsNull() {
            DepositRequest request = new DepositRequest(TO_ACCOUNT_ID, 5000L, "USD", null, null);

            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            ConstraintViolation<DepositRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath().toString()).isEqualTo("initiatedByUserId");
            assertThat(violation.getMessage()).isEqualTo("Initiated by user ID must not be null");
        }
    }

    @Nested
    @DisplayName("TransferResponse Mapping Tests")
    class TransferResponseTests {

        @Test
        @DisplayName("Should map from completed Transfer entity correctly")
        void shouldMapFromCompletedTransferEntity() {
            Money transferAmount = Money.ofMinor(5432L, "USD");
            Transfer transfer = Transfer.createCompleted(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, transferAmount,
                    "Salary transfer", USER_ID);
            ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
            ReflectionTestUtils.setField(transfer, "createdAt", TIMESTAMP);
            ReflectionTestUtils.setField(transfer, "updatedAt", TIMESTAMP);

            TransferResponse response = TransferResponse.from(transfer);

            assertThat(response.id()).isEqualTo(TRANSFER_ID);
            assertThat(response.fromAccountId()).isEqualTo(FROM_ACCOUNT_ID);
            assertThat(response.toAccountId()).isEqualTo(TO_ACCOUNT_ID);
            assertThat(response.amountInMinorUnits()).isEqualTo(5432L);
            assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("54.32"));
            assertThat(response.currency()).isEqualTo("USD");
            assertThat(response.status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(response.description()).isEqualTo("Salary transfer");
            assertThat(response.failureReason()).isNull();
            assertThat(response.initiatedByUserId()).isEqualTo(USER_ID);
            assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
            assertThat(response.updatedAt()).isEqualTo(TIMESTAMP);
        }

        @Test
        @DisplayName("Should map from failed Transfer entity with failureReason correctly")
        void shouldMapFromFailedTransferEntity() {
            Money transferAmount = Money.ofMinor(1000L, "USD");
            Transfer transfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, transferAmount, "Failed attempt",
                    USER_ID);
            ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
            ReflectionTestUtils.setField(transfer, "createdAt", TIMESTAMP);
            ReflectionTestUtils.setField(transfer, "updatedAt", TIMESTAMP);
            transfer.markFailed("Insufficient funds in account");

            TransferResponse response = TransferResponse.from(transfer);

            assertThat(response.id()).isEqualTo(TRANSFER_ID);
            assertThat(response.status()).isEqualTo(TransferStatus.FAILED);
            assertThat(response.failureReason()).isEqualTo("Insufficient funds in account");
        }

        @Test
        @DisplayName("Should throw NullPointerException when transfer entity is null")
        void shouldThrowNpeWhenTransferIsNull() {
            assertThatThrownBy(() -> TransferResponse.from(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("Transfer must not be null");
        }
    }

    @Nested
    @DisplayName("LedgerEntryResponse Mapping Tests")
    class LedgerEntryResponseTests {

        @Test
        @DisplayName("Should map from LedgerEntry entity without currency")
        void shouldMapFromLedgerEntryWithoutCurrency() {
            Money entryAmount = Money.ofMinor(2500L, "USD");
            Money balanceAfter = Money.ofMinor(12500L, "USD");
            LedgerEntry entry = LedgerEntry.createDebit(TRANSFER_ID, FROM_ACCOUNT_ID, entryAmount, balanceAfter);
            ReflectionTestUtils.setField(entry, "id", ENTRY_ID);
            ReflectionTestUtils.setField(entry, "createdAt", TIMESTAMP);

            LedgerEntryResponse response = LedgerEntryResponse.from(entry);

            assertThat(response.id()).isEqualTo(ENTRY_ID);
            assertThat(response.transferId()).isEqualTo(TRANSFER_ID);
            assertThat(response.accountId()).isEqualTo(FROM_ACCOUNT_ID);
            assertThat(response.type()).isEqualTo(EntryType.DEBIT);
            assertThat(response.amountInMinorUnits()).isEqualTo(2500L);
            assertThat(response.amount()).isNull();
            assertThat(response.balanceAfterInMinorUnits()).isEqualTo(12500L);
            assertThat(response.balanceAfter()).isNull();
            assertThat(response.currency()).isNull();
            assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
        }

        @Test
        @DisplayName("Should map from LedgerEntry entity with currency formatting and normalization")
        void shouldMapFromLedgerEntryWithCurrency() {
            Money entryAmount = Money.ofMinor(2500L, "USD");
            Money balanceAfter = Money.ofMinor(12500L, "USD");
            LedgerEntry entry = LedgerEntry.createCredit(TRANSFER_ID, TO_ACCOUNT_ID, entryAmount, balanceAfter);
            ReflectionTestUtils.setField(entry, "id", ENTRY_ID);
            ReflectionTestUtils.setField(entry, "createdAt", TIMESTAMP);

            LedgerEntryResponse response = LedgerEntryResponse.from(entry, "  usd  ");

            assertThat(response.id()).isEqualTo(ENTRY_ID);
            assertThat(response.transferId()).isEqualTo(TRANSFER_ID);
            assertThat(response.accountId()).isEqualTo(TO_ACCOUNT_ID);
            assertThat(response.type()).isEqualTo(EntryType.CREDIT);
            assertThat(response.amountInMinorUnits()).isEqualTo(2500L);
            assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("25.00"));
            assertThat(response.balanceAfterInMinorUnits()).isEqualTo(12500L);
            assertThat(response.balanceAfter()).isEqualByComparingTo(new BigDecimal("125.00"));
            assertThat(response.currency()).isEqualTo("USD");
            assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when currency code is invalid")
        void shouldThrowWhenCurrencyCodeIsInvalid() {
            Money entryAmount = Money.ofMinor(100L, "USD");
            Money balanceAfter = Money.ofMinor(500L, "USD");
            LedgerEntry entry = LedgerEntry.createDebit(TRANSFER_ID, FROM_ACCOUNT_ID, entryAmount, balanceAfter);

            assertThatThrownBy(() -> LedgerEntryResponse.from(entry, "INVALID"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should throw NullPointerException when ledger entry entity is null")
        void shouldThrowNpeWhenLedgerEntryIsNull() {
            assertThatThrownBy(() -> LedgerEntryResponse.from(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("LedgerEntry must not be null");
        }
    }
}
