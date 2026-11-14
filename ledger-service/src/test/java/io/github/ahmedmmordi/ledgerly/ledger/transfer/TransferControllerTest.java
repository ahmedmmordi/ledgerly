package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ahmedmmordi.ledgerly.common.exception.GlobalExceptionHandler;
import io.github.ahmedmmordi.ledgerly.ledger.config.LedgerExceptionHandler;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotActiveException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.CurrencyMismatchException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.InsufficientFundsException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.domain.TransferNotFoundException;
import java.time.Instant;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferController WebMvc Standalone Tests")
class TransferControllerTest {

    private static final UUID FROM_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-111111111111");
    private static final UUID TO_ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-222222222222");
    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-333333333333");
    private static final UUID TRANSFER_ID = UUID.fromString("0193297a-9a90-7888-8123-444444444444");
    private static final UUID DEBIT_ENTRY_ID = UUID.fromString("0193297a-9a90-7888-8123-555555555555");
    private static final UUID CREDIT_ENTRY_ID = UUID.fromString("0193297a-9a90-7888-8123-666666666666");
    private static final Instant TIMESTAMP = Instant.parse("2026-11-14T20:00:00Z");

    private static LocalValidatorFactoryBean validator;

    @Mock
    private TransferService transferService;

    @InjectMocks
    private TransferController transferController;

    private MockMvc mockMvc;

    private Transfer sampleTransfer;

    @BeforeAll
    static void initValidator() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownValidator() {
        if (validator != null) {
            validator.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(transferController)
                .setControllerAdvice(new LedgerExceptionHandler(), new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).setValidator(validator)
                .build();

        Money transferAmount = Money.ofMinor(5000L, "USD");
        sampleTransfer = Transfer.createCompleted(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, transferAmount, "Invoice #101",
                USER_ID);
        ReflectionTestUtils.setField(sampleTransfer, "id", TRANSFER_ID);
        ReflectionTestUtils.setField(sampleTransfer, "createdAt", TIMESTAMP);
        ReflectionTestUtils.setField(sampleTransfer, "updatedAt", TIMESTAMP);
    }

    private static String quoteOrNull(Object value) {
        return (value == null) ? "null" : "\"" + value + "\"";
    }

    private static String createTransferJson(Object fromId, Object toId, Object amount, Object currency, Object desc,
            Object userId) {
        return """
                {
                    "fromAccountId": %s,
                    "toAccountId": %s,
                    "amount": %s,
                    "currency": %s,
                    "description": %s,
                    "initiatedByUserId": %s
                }
                """.formatted(quoteOrNull(fromId), quoteOrNull(toId), amount == null ? "null" : amount,
                quoteOrNull(currency), quoteOrNull(desc), quoteOrNull(userId));
    }

    private static String createDepositJson(Object accountId, Object amount, Object currency, Object desc,
            Object userId) {
        return """
                {
                    "accountId": %s,
                    "amount": %s,
                    "currency": %s,
                    "description": %s,
                    "initiatedByUserId": %s
                }
                """.formatted(quoteOrNull(accountId), amount == null ? "null" : amount, quoteOrNull(currency),
                quoteOrNull(desc), quoteOrNull(userId));
    }

    @Nested
    @DisplayName("POST /api/v1/transfers - Execute Transfer")
    class TransferExecutionTests {

        @Test
        @DisplayName("Should return 201 Created with Location header and TransferResponse on valid request")
        void shouldExecuteTransferSuccessfully() throws Exception {
            Money expectedMoney = Money.ofMinor(5000L, "USD");
            when(transferService.transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, expectedMoney, "Invoice #101", USER_ID))
                    .thenReturn(sampleTransfer);

            String validJson = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "USD", "Invoice #101",
                    USER_ID);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(validJson))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/transfers/" + TRANSFER_ID))
                    .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.fromAccountId").value(FROM_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.toAccountId").value(TO_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.amountInMinorUnits").value(5000))
                    .andExpect(jsonPath("$.amount").value(50.00)).andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.description").value("Invoice #101"))
                    .andExpect(jsonPath("$.failureReason").isEmpty())
                    .andExpect(jsonPath("$.initiatedByUserId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.createdAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$.updatedAt").value(TIMESTAMP.toString()));

            verify(transferService).transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, expectedMoney, "Invoice #101", USER_ID);
        }

        @Test
        @DisplayName("Should execute successfully when optional fields (description, initiatedByUserId) are null")
        void shouldExecuteTransferSuccessfullyWithMinimalPayload() throws Exception {
            Money expectedMoney = Money.ofMinor(5000L, "USD");
            Transfer minimalTransfer = Transfer.createCompleted(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, expectedMoney, null,
                    null);
            ReflectionTestUtils.setField(minimalTransfer, "id", TRANSFER_ID);
            ReflectionTestUtils.setField(minimalTransfer, "createdAt", TIMESTAMP);
            ReflectionTestUtils.setField(minimalTransfer, "updatedAt", TIMESTAMP);

            when(transferService.transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, expectedMoney, null, null))
                    .thenReturn(minimalTransfer);

            String minimalJson = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "USD", null, null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(minimalJson))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/transfers/" + TRANSFER_ID))
                    .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.description").doesNotExist())
                    .andExpect(jsonPath("$.initiatedByUserId").doesNotExist());

            verify(transferService).transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, expectedMoney, null, null);
        }

        @Test
        @DisplayName("Should normalize whitespace-only description to null")
        void shouldNormalizeWhitespaceOnlyDescriptionToNull() throws Exception {
            Money expectedMoney = Money.ofMinor(5000L, "USD");
            when(transferService.transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, expectedMoney, null, USER_ID))
                    .thenReturn(sampleTransfer);

            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "USD", "    ", USER_ID);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isCreated());

            verify(transferService).transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, expectedMoney, null, USER_ID);
        }

        @Test
        @DisplayName("Should normalize lowercase currency in request and succeed")
        void shouldExecuteTransferSuccessfullyWhenCurrencyIsLowercase() throws Exception {
            Money expectedMoney = Money.ofMinor(5000L, "USD");
            when(transferService.transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, expectedMoney, "Invoice #101", USER_ID))
                    .thenReturn(sampleTransfer);

            String lowercaseJson = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "usd", "Invoice #101",
                    USER_ID);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(lowercaseJson))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/transfers/" + TRANSFER_ID))
                    .andExpect(jsonPath("$.currency").value("USD"));

            verify(transferService).transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, expectedMoney, "Invoice #101", USER_ID);
        }

        @Test
        @DisplayName("Should return 400 Bad Request when fromAccountId is null")
        void shouldReturn400WhenFromAccountIdIsNull() throws Exception {
            String json = createTransferJson(null, TO_ACCOUNT_ID, 5000L, "USD", "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.title").value("Validation Failed"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail", containsString("fromAccountId")))
                    .andExpect(jsonPath("$.detail", containsString("From account ID must not be null")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when toAccountId is null")
        void shouldReturn400WhenToAccountIdIsNull() throws Exception {
            String json = createTransferJson(FROM_ACCOUNT_ID, null, 5000L, "USD", "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.title").value("Validation Failed"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail", containsString("toAccountId")))
                    .andExpect(jsonPath("$.detail", containsString("To account ID must not be null")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when amount is null")
        void shouldReturn400WhenAmountIsNull() throws Exception {
            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, null, "USD", "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("amount")))
                    .andExpect(jsonPath("$.detail", containsString("Amount must not be null")));
        }

        @ParameterizedTest
        @ValueSource(longs = { 0L, -1L, -5000L })
        @DisplayName("Should return 400 Bad Request when amount is not positive")
        void shouldReturn400WhenAmountIsNotPositive(long nonPositiveAmount) throws Exception {
            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, nonPositiveAmount, "USD", "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("amount")))
                    .andExpect(jsonPath("$.detail", containsString("Amount must be strictly positive")));
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should return 400 Bad Request when currency is null or blank")
        void shouldReturn400WhenCurrencyIsBlankOrNull(String blankCurrency) throws Exception {
            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, blankCurrency, "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("currency")))
                    .andExpect(jsonPath("$.detail", containsString("Currency must not be blank")));
        }

        @ParameterizedTest
        @ValueSource(strings = { "US", "USDD", "123", "US$" })
        @DisplayName("Should return 400 Bad Request when currency format is invalid")
        void shouldReturn400WhenCurrencyFormatIsInvalid(String invalidCurrency) throws Exception {
            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, invalidCurrency, "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("currency"))).andExpect(jsonPath("$.detail",
                            containsString("Currency must be a valid 3-letter uppercase ISO 4217 code")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when description exceeds 255 characters")
        void shouldReturn400WhenDescriptionExceedsMaxLength() throws Exception {
            String longDescription = "A".repeat(256);
            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "USD", longDescription, null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("description")))
                    .andExpect(jsonPath("$.detail", containsString("Description must not exceed 255 characters")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when payload is empty JSON object")
        void shouldReturn400WhenPayloadIsEmptyJson() throws Exception {
            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when payload is malformed JSON")
        void shouldReturn400WhenPayloadIsMalformed() throws Exception {
            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content("not-json"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 415 Unsupported Media Type when content-type is text/plain")
        void shouldReturn415WhenContentTypeIsNotJson() throws Exception {
            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.TEXT_PLAIN).content("plain-text"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when service throws IllegalArgumentException for distinct accounts")
        void shouldReturn400WhenAccountsNotDistinct() throws Exception {
            Money amount = Money.ofMinor(5000L, "USD");
            when(transferService.transfer(FROM_ACCOUNT_ID, FROM_ACCOUNT_ID, amount, "Note", null))
                    .thenThrow(new IllegalArgumentException("From account and to account must be distinct"));

            String json = createTransferJson(FROM_ACCOUNT_ID, FROM_ACCOUNT_ID, 5000L, "USD", "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:bad-request"))
                    .andExpect(jsonPath("$.title").value("Invalid Argument"))
                    .andExpect(jsonPath("$.detail").value("From account and to account must be distinct"));
        }

        @Test
        @DisplayName("Should return 404 Not Found RFC 7807 when source account is not found")
        void shouldReturn404WhenAccountNotFound() throws Exception {
            Money amount = Money.ofMinor(5000L, "USD");
            when(transferService.transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, amount, "Note", null))
                    .thenThrow(new AccountNotFoundException(FROM_ACCOUNT_ID));

            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "USD", "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:account-not-found"))
                    .andExpect(jsonPath("$.title").value("Account Not Found"))
                    .andExpect(jsonPath("$.accountId").value(FROM_ACCOUNT_ID.toString()));
        }

        @Test
        @DisplayName("Should return 409 Conflict RFC 7807 when account is not active")
        void shouldReturn409WhenAccountNotActive() throws Exception {
            Money amount = Money.ofMinor(5000L, "USD");
            when(transferService.transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, amount, "Note", null))
                    .thenThrow(new AccountNotActiveException(FROM_ACCOUNT_ID, "FROZEN"));

            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "USD", "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:account-not-active"))
                    .andExpect(jsonPath("$.title").value("Account Not Active"))
                    .andExpect(jsonPath("$.status").value("FROZEN"));
        }

        @Test
        @DisplayName("Should return 422 Unprocessable Content RFC 7807 when insufficient funds")
        void shouldReturn422WhenInsufficientFunds() throws Exception {
            Money requested = Money.ofMinor(5000L, "USD");
            Money current = Money.ofMinor(1000L, "USD");
            when(transferService.transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, requested, "Note", null))
                    .thenThrow(new InsufficientFundsException(FROM_ACCOUNT_ID, requested, current));

            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "USD", "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:insufficient-funds"))
                    .andExpect(jsonPath("$.title").value("Insufficient Funds"))
                    .andExpect(jsonPath("$.accountId").value(FROM_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.requestedAmount").value("50.00 USD"))
                    .andExpect(jsonPath("$.currentBalance").value("10.00 USD"));
        }

        @Test
        @DisplayName("Should return 400 Bad Request RFC 7807 when transfer currencies mismatch")
        void shouldReturn400WhenCurrencyMismatch() throws Exception {
            Money amount = Money.ofMinor(5000L, "USD");
            when(transferService.transfer(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, amount, "Note", null))
                    .thenThrow(new CurrencyMismatchException(Currency.getInstance("USD"), Currency.getInstance("EUR")));

            String json = createTransferJson(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, 5000L, "USD", "Note", null);

            mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:currency-mismatch"))
                    .andExpect(jsonPath("$.title").value("Currency Mismatch"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/transfers/deposit - Execute Deposit")
    class DepositExecutionTests {

        @Test
        @DisplayName("Should return 201 Created with Location header and full TransferResponse on valid deposit")
        void shouldExecuteDepositSuccessfully() throws Exception {
            Money depositMoney = Money.ofMinor(10000L, "USD");
            when(transferService.deposit(TO_ACCOUNT_ID, depositMoney, "Cash deposit", USER_ID))
                    .thenReturn(sampleTransfer);

            String validJson = createDepositJson(TO_ACCOUNT_ID, 10000L, "USD", "Cash deposit", USER_ID);

            mockMvc.perform(
                    post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(validJson))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/transfers/" + TRANSFER_ID))
                    .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.fromAccountId").value(FROM_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.toAccountId").value(TO_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.amountInMinorUnits").value(5000))
                    .andExpect(jsonPath("$.amount").value(50.00)).andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.description").value("Invoice #101"))
                    .andExpect(jsonPath("$.initiatedByUserId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.createdAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$.updatedAt").value(TIMESTAMP.toString()));

            verify(transferService).deposit(TO_ACCOUNT_ID, depositMoney, "Cash deposit", USER_ID);
        }

        @Test
        @DisplayName("Should execute deposit successfully with null description")
        void shouldExecuteDepositSuccessfullyWithNullDescription() throws Exception {
            Money depositMoney = Money.ofMinor(10000L, "USD");
            when(transferService.deposit(eq(TO_ACCOUNT_ID), eq(depositMoney), isNull(), eq(USER_ID)))
                    .thenReturn(sampleTransfer);

            String jsonWithoutDesc = createDepositJson(TO_ACCOUNT_ID, 10000L, "USD", null, USER_ID);

            mockMvc.perform(
                    post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(jsonWithoutDesc))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/transfers/" + TRANSFER_ID));

            verify(transferService).deposit(TO_ACCOUNT_ID, depositMoney, null, USER_ID);
        }

        @Test
        @DisplayName("Should normalize whitespace-only deposit description to null")
        void shouldNormalizeWhitespaceOnlyDepositDescriptionToNull() throws Exception {
            Money depositMoney = Money.ofMinor(10000L, "USD");
            when(transferService.deposit(eq(TO_ACCOUNT_ID), eq(depositMoney), isNull(), eq(USER_ID)))
                    .thenReturn(sampleTransfer);

            String json = createDepositJson(TO_ACCOUNT_ID, 10000L, "USD", "     ", USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isCreated());

            verify(transferService).deposit(TO_ACCOUNT_ID, depositMoney, null, USER_ID);
        }

        @Test
        @DisplayName("Should normalize lowercase currency in deposit request and succeed")
        void shouldExecuteDepositSuccessfullyWhenCurrencyIsLowercase() throws Exception {
            Money depositMoney = Money.ofMinor(10000L, "USD");
            when(transferService.deposit(TO_ACCOUNT_ID, depositMoney, "Cash deposit", USER_ID))
                    .thenReturn(sampleTransfer);

            String lowercaseJson = createDepositJson(TO_ACCOUNT_ID, 10000L, "usd", "Cash deposit", USER_ID);

            mockMvc.perform(
                    post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(lowercaseJson))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/transfers/" + TRANSFER_ID))
                    .andExpect(jsonPath("$.currency").value("USD"));

            verify(transferService).deposit(TO_ACCOUNT_ID, depositMoney, "Cash deposit", USER_ID);
        }

        @Test
        @DisplayName("Should return 400 Bad Request when accountId is null")
        void shouldReturn400WhenDepositAccountIdIsNull() throws Exception {
            String json = createDepositJson(null, 10000L, "USD", "Deposit", USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("accountId")))
                    .andExpect(jsonPath("$.detail", containsString("Account ID must not be null")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when initiatedByUserId is null")
        void shouldReturn400WhenDepositUserIdIsNull() throws Exception {
            String json = createDepositJson(TO_ACCOUNT_ID, 10000L, "USD", "Deposit", null);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("initiatedByUserId")))
                    .andExpect(jsonPath("$.detail", containsString("Initiated by user ID must not be null")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when deposit amount is null")
        void shouldReturn400WhenDepositAmountIsNull() throws Exception {
            String json = createDepositJson(TO_ACCOUNT_ID, null, "USD", "Deposit", USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("amount")))
                    .andExpect(jsonPath("$.detail", containsString("Amount must not be null")));
        }

        @ParameterizedTest
        @ValueSource(longs = { 0L, -500L })
        @DisplayName("Should return 400 Bad Request when deposit amount is not positive")
        void shouldReturn400WhenDepositAmountNotPositive(long nonPositiveAmount) throws Exception {
            String json = createDepositJson(TO_ACCOUNT_ID, nonPositiveAmount, "USD", "Deposit", USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("amount")))
                    .andExpect(jsonPath("$.detail", containsString("Amount must be strictly positive")));
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should return 400 Bad Request when deposit currency is null or blank")
        void shouldReturn400WhenDepositCurrencyIsBlankOrNull(String blankCurrency) throws Exception {
            String json = createDepositJson(TO_ACCOUNT_ID, 10000L, blankCurrency, "Deposit", USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("currency")))
                    .andExpect(jsonPath("$.detail", containsString("Currency must not be blank")));
        }

        @ParameterizedTest
        @ValueSource(strings = { "US", "USDD", "123", "US$" })
        @DisplayName("Should return 400 Bad Request when deposit currency format is invalid")
        void shouldReturn400WhenDepositCurrencyFormatIsInvalid(String invalidCurrency) throws Exception {
            String json = createDepositJson(TO_ACCOUNT_ID, 10000L, invalidCurrency, "Deposit", USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("currency"))).andExpect(jsonPath("$.detail",
                            containsString("Currency must be a valid 3-letter uppercase ISO 4217 code")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when deposit description exceeds 255 characters")
        void shouldReturn400WhenDepositDescriptionExceedsMaxLength() throws Exception {
            String longDesc = "A".repeat(256);
            String json = createDepositJson(TO_ACCOUNT_ID, 10000L, "USD", longDesc, USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.detail", containsString("description")))
                    .andExpect(jsonPath("$.detail", containsString("Description must not exceed 255 characters")));
        }

        @Test
        @DisplayName("Should return 404 Not Found RFC 7807 when deposit account is not found")
        void shouldReturn404WhenDepositAccountNotFound() throws Exception {
            Money depositMoney = Money.ofMinor(10000L, "USD");
            when(transferService.deposit(TO_ACCOUNT_ID, depositMoney, "Deposit", USER_ID))
                    .thenThrow(new AccountNotFoundException(TO_ACCOUNT_ID));

            String json = createDepositJson(TO_ACCOUNT_ID, 10000L, "USD", "Deposit", USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:account-not-found"))
                    .andExpect(jsonPath("$.title").value("Account Not Found"))
                    .andExpect(jsonPath("$.accountId").value(TO_ACCOUNT_ID.toString()));
        }

        @Test
        @DisplayName("Should return 409 Conflict RFC 7807 when deposit account is not active")
        void shouldReturn409WhenDepositAccountNotActive() throws Exception {
            Money depositMoney = Money.ofMinor(10000L, "USD");
            when(transferService.deposit(TO_ACCOUNT_ID, depositMoney, "Deposit", USER_ID))
                    .thenThrow(new AccountNotActiveException(TO_ACCOUNT_ID, "FROZEN"));

            String json = createDepositJson(TO_ACCOUNT_ID, 10000L, "USD", "Deposit", USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:account-not-active"))
                    .andExpect(jsonPath("$.title").value("Account Not Active"))
                    .andExpect(jsonPath("$.status").value("FROZEN"));
        }

        @Test
        @DisplayName("Should return 400 Bad Request RFC 7807 when deposit currency mismatches account currency")
        void shouldReturn400WhenDepositCurrencyMismatch() throws Exception {
            Money depositMoney = Money.ofMinor(10000L, "USD");
            when(transferService.deposit(TO_ACCOUNT_ID, depositMoney, "Deposit", USER_ID))
                    .thenThrow(new CurrencyMismatchException(Currency.getInstance("USD"), Currency.getInstance("EUR")));

            String json = createDepositJson(TO_ACCOUNT_ID, 10000L, "USD", "Deposit", USER_ID);

            mockMvc.perform(post("/api/v1/transfers/deposit").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:currency-mismatch"))
                    .andExpect(jsonPath("$.title").value("Currency Mismatch"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/transfers/{id} - Get Transfer by ID")
    class GetTransferByIdTests {

        @Test
        @DisplayName("Should return 200 OK with TransferResponse when transfer exists")
        void shouldGetTransferById() throws Exception {
            when(transferService.getTransfer(TRANSFER_ID)).thenReturn(sampleTransfer);

            mockMvc.perform(get("/api/v1/transfers/{id}", TRANSFER_ID)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.fromAccountId").value(FROM_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.toAccountId").value(TO_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.amountInMinorUnits").value(5000))
                    .andExpect(jsonPath("$.amount").value(50.00)).andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.description").value("Invoice #101"))
                    .andExpect(jsonPath("$.initiatedByUserId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.createdAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$.updatedAt").value(TIMESTAMP.toString()));

            verify(transferService).getTransfer(TRANSFER_ID);
        }

        @Test
        @DisplayName("Should return 200 OK with failureReason populated for failed transfer")
        void shouldGetFailedTransferById() throws Exception {
            Money transferAmount = Money.ofMinor(5000L, "USD");
            Transfer failedTransfer = Transfer.createPending(FROM_ACCOUNT_ID, TO_ACCOUNT_ID, transferAmount, "Note",
                    USER_ID);
            failedTransfer.markFailed("Pessimistic lock timeout");
            ReflectionTestUtils.setField(failedTransfer, "id", TRANSFER_ID);
            ReflectionTestUtils.setField(failedTransfer, "createdAt", TIMESTAMP);
            ReflectionTestUtils.setField(failedTransfer, "updatedAt", TIMESTAMP);

            when(transferService.getTransfer(TRANSFER_ID)).thenReturn(failedTransfer);

            mockMvc.perform(get("/api/v1/transfers/{id}", TRANSFER_ID)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.failureReason").value("Pessimistic lock timeout"));

            verify(transferService).getTransfer(TRANSFER_ID);
        }

        @Test
        @DisplayName("Should return 404 Not Found RFC 7807 when transfer does not exist")
        void shouldReturn404WhenTransferNotFound() throws Exception {
            when(transferService.getTransfer(TRANSFER_ID)).thenThrow(new TransferNotFoundException(TRANSFER_ID));

            mockMvc.perform(get("/api/v1/transfers/{id}", TRANSFER_ID)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:transfer-not-found"))
                    .andExpect(jsonPath("$.title").value("Transfer Not Found"))
                    .andExpect(jsonPath("$.transferId").value(TRANSFER_ID.toString()));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when path variable is not a valid UUID")
        void shouldReturn400WhenIdNotValidUuid() throws Exception {
            mockMvc.perform(get("/api/v1/transfers/{id}", "not-a-uuid")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 405 Method Not Allowed when calling unsupported HTTP method")
        void shouldReturn405WhenMethodNotAllowed() throws Exception {
            mockMvc.perform(delete("/api/v1/transfers/{id}", TRANSFER_ID)).andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/transfers - Paginated Query")
    class GetTransfersQueryTests {

        @Test
        @DisplayName("Should return 200 OK with paginated transfers using default pageable (page 0, size 20)")
        void shouldGetTransfersByAccountIdWithDefaultPageable() throws Exception {
            PageRequest expectedPageable = PageRequest.of(0, 20);
            when(transferService.getTransfersByAccountId(eq(FROM_ACCOUNT_ID), eq(expectedPageable)))
                    .thenReturn(new PageImpl<>(List.of(sampleTransfer), expectedPageable, 1));

            mockMvc.perform(get("/api/v1/transfers").param("accountId", FROM_ACCOUNT_ID.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.totalElements").value(1));

            verify(transferService).getTransfersByAccountId(eq(FROM_ACCOUNT_ID), eq(expectedPageable));
        }

        @Test
        @DisplayName("Should return 200 OK with paginated transfers when custom page and size are provided")
        void shouldGetTransfersByAccountIdWithCustomPageable() throws Exception {
            PageRequest customPage = PageRequest.of(1, 10);
            when(transferService.getTransfersByAccountId(eq(FROM_ACCOUNT_ID), eq(customPage)))
                    .thenReturn(new PageImpl<>(List.of(sampleTransfer), customPage, 11));

            mockMvc.perform(get("/api/v1/transfers").param("accountId", FROM_ACCOUNT_ID.toString()).param("page", "1")
                    .param("size", "10")).andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.content[0].fromAccountId").value(FROM_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.content[0].toAccountId").value(TO_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.totalElements").value(11)).andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.size").value(10)).andExpect(jsonPath("$.number").value(1));

            verify(transferService).getTransfersByAccountId(eq(FROM_ACCOUNT_ID), eq(customPage));
        }

        @Test
        @DisplayName("Should return 200 OK with paginated transfers when userId is provided")
        void shouldGetTransfersByUserId() throws Exception {
            PageRequest pageRequest = PageRequest.of(0, 20);
            when(transferService.getTransfersByInitiatedUserId(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(sampleTransfer), pageRequest, 1));

            mockMvc.perform(
                    get("/api/v1/transfers").param("userId", USER_ID.toString()).param("page", "0").param("size", "20"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.content[0].initiatedByUserId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.content[0].amountInMinorUnits").value(5000))
                    .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.number").value(0));

            verify(transferService).getTransfersByInitiatedUserId(eq(USER_ID), any(Pageable.class));
        }

        @Test
        @DisplayName("Should return 200 OK with empty content array when no transfers match accountId")
        void shouldReturnEmptyPageWhenNoTransfersFoundForAccount() throws Exception {
            PageRequest pageRequest = PageRequest.of(0, 20);
            when(transferService.getTransfersByAccountId(eq(FROM_ACCOUNT_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList(), pageRequest, 0));

            mockMvc.perform(get("/api/v1/transfers").param("accountId", FROM_ACCOUNT_ID.toString()).param("page", "0")
                    .param("size", "20")).andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)))
                    .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));

            verify(transferService).getTransfersByAccountId(eq(FROM_ACCOUNT_ID), any(Pageable.class));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when accountId query parameter is not a valid UUID")
        void shouldReturn400WhenAccountIdQueryParamIsInvalidUuid() throws Exception {
            mockMvc.perform(get("/api/v1/transfers").param("accountId", "not-a-valid-uuid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when userId query parameter is not a valid UUID")
        void shouldReturn400WhenUserIdQueryParamIsInvalidUuid() throws Exception {
            mockMvc.perform(get("/api/v1/transfers").param("userId", "not-a-valid-uuid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when both accountId and userId are provided")
        void shouldReturn400WhenBothQueryParamsProvided() throws Exception {
            mockMvc.perform(get("/api/v1/transfers").param("accountId", FROM_ACCOUNT_ID.toString()).param("userId",
                    USER_ID.toString())).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:bad-request"))
                    .andExpect(jsonPath("$.title").value("Invalid Argument")).andExpect(jsonPath("$.detail")
                            .value("Cannot filter transfers by both accountId and userId simultaneously"));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when neither accountId nor userId is provided")
        void shouldReturn400WhenNeitherQueryParamProvided() throws Exception {
            mockMvc.perform(get("/api/v1/transfers")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:bad-request"))
                    .andExpect(jsonPath("$.title").value("Invalid Argument")).andExpect(
                            jsonPath("$.detail").value("Either accountId or userId query parameter must be provided"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/transfers/{id}/entries - Get Ledger Entries")
    class GetLedgerEntriesTests {

        @Test
        @DisplayName("Should return 200 OK with balanced debit and credit ledger entries")
        void shouldGetLedgerEntriesByTransferId() throws Exception {
            Money transferAmount = Money.ofMinor(5000L, "USD");
            Money debitBalanceAfter = Money.ofMinor(10000L, "USD");
            Money creditBalanceAfter = Money.ofMinor(15000L, "USD");

            LedgerEntry debitEntry = LedgerEntry.createDebit(TRANSFER_ID, FROM_ACCOUNT_ID, transferAmount,
                    debitBalanceAfter);
            ReflectionTestUtils.setField(debitEntry, "id", DEBIT_ENTRY_ID);
            ReflectionTestUtils.setField(debitEntry, "createdAt", TIMESTAMP);

            LedgerEntry creditEntry = LedgerEntry.createCredit(TRANSFER_ID, TO_ACCOUNT_ID, transferAmount,
                    creditBalanceAfter);
            ReflectionTestUtils.setField(creditEntry, "id", CREDIT_ENTRY_ID);
            ReflectionTestUtils.setField(creditEntry, "createdAt", TIMESTAMP);

            when(transferService.getTransfer(TRANSFER_ID)).thenReturn(sampleTransfer);
            when(transferService.getLedgerEntriesByTransferId(TRANSFER_ID))
                    .thenReturn(List.of(debitEntry, creditEntry));

            mockMvc.perform(get("/api/v1/transfers/{id}/entries", TRANSFER_ID)).andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].id").value(DEBIT_ENTRY_ID.toString()))
                    .andExpect(jsonPath("$[0].transferId").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$[0].accountId").value(FROM_ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$[0].type").value("DEBIT"))
                    .andExpect(jsonPath("$[0].amountInMinorUnits").value(5000))
                    .andExpect(jsonPath("$[0].amount").value(50.00))
                    .andExpect(jsonPath("$[0].balanceAfterInMinorUnits").value(10000))
                    .andExpect(jsonPath("$[0].balanceAfter").value(100.00))
                    .andExpect(jsonPath("$[0].currency").value("USD"))
                    .andExpect(jsonPath("$[0].createdAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$[1].id").value(CREDIT_ENTRY_ID.toString()))
                    .andExpect(jsonPath("$[1].type").value("CREDIT"))
                    .andExpect(jsonPath("$[1].amountInMinorUnits").value(5000))
                    .andExpect(jsonPath("$[1].amount").value(50.00))
                    .andExpect(jsonPath("$[1].balanceAfterInMinorUnits").value(15000))
                    .andExpect(jsonPath("$[1].balanceAfter").value(150.00))
                    .andExpect(jsonPath("$[1].currency").value("USD"));

            verify(transferService).getTransfer(TRANSFER_ID);
            verify(transferService).getLedgerEntriesByTransferId(TRANSFER_ID);
        }

        @Test
        @DisplayName("Should return 200 OK with empty array when transfer has no ledger entries")
        void shouldReturnEmptyListWhenTransferHasNoLedgerEntries() throws Exception {
            when(transferService.getTransfer(TRANSFER_ID)).thenReturn(sampleTransfer);
            when(transferService.getLedgerEntriesByTransferId(TRANSFER_ID)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/v1/transfers/{id}/entries", TRANSFER_ID)).andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            verify(transferService).getTransfer(TRANSFER_ID);
            verify(transferService).getLedgerEntriesByTransferId(TRANSFER_ID);
        }

        @Test
        @DisplayName("Should return 400 Bad Request when path variable for entries is not a valid UUID")
        void shouldReturn400WhenPathIdIsNotValidUuid() throws Exception {
            mockMvc.perform(get("/api/v1/transfers/{id}/entries", "invalid-uuid")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 404 Not Found when transfer does not exist")
        void shouldReturn404WhenTransferNotFoundForEntries() throws Exception {
            when(transferService.getTransfer(TRANSFER_ID)).thenThrow(new TransferNotFoundException(TRANSFER_ID));

            mockMvc.perform(get("/api/v1/transfers/{id}/entries", TRANSFER_ID)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:transfer-not-found"))
                    .andExpect(jsonPath("$.title").value("Transfer Not Found"));
        }
    }
}
