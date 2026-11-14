package io.github.ahmedmmordi.ledgerly.ledger.account;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ahmedmmordi.ledgerly.common.exception.GlobalExceptionHandler;
import io.github.ahmedmmordi.ledgerly.ledger.config.LedgerExceptionHandler;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotFoundException;
import java.time.Instant;
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
@DisplayName("AccountController WebMvc Standalone Tests")
class AccountControllerTest {

    private static final UUID USER_ID = UUID.fromString("0193297a-9a90-7888-8123-456789abcdef");
    private static final UUID ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-111111111111");
    private static final String ACCOUNT_NUMBER = "ACC1234567890";
    private static final Instant TIMESTAMP = Instant.parse("2026-11-14T20:00:00Z");

    private static LocalValidatorFactoryBean validator;

    @Mock
    private AccountService accountService;

    @InjectMocks
    private AccountController accountController;

    private MockMvc mockMvc;

    private Account activeAccount;

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
        mockMvc = MockMvcBuilders.standaloneSetup(accountController)
                .setControllerAdvice(new LedgerExceptionHandler(), new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).setValidator(validator)
                .build();

        activeAccount = Account.createCustomer(USER_ID, ACCOUNT_NUMBER, "USD");
        ReflectionTestUtils.setField(activeAccount, "id", ACCOUNT_ID);
        ReflectionTestUtils.setField(activeAccount, "createdAt", TIMESTAMP);
        ReflectionTestUtils.setField(activeAccount, "updatedAt", TIMESTAMP);
    }

    private static String createAccountJson(String userId, String currency) {
        String uid = (userId == null) ? "null" : "\"" + userId + "\"";
        String cur = (currency == null) ? "null" : "\"" + currency + "\"";
        return """
                {
                    "userId": %s,
                    "currency": %s
                }
                """.formatted(uid, cur);
    }

    @Nested
    @DisplayName("POST /api/v1/accounts - Create Account")
    class CreateAccountTests {

        @Test
        @DisplayName("Should return 201 Created with Location header and account response on valid request")
        void shouldCreateAccountSuccessfully() throws Exception {
            when(accountService.createCustomerAccount(USER_ID, "USD")).thenReturn(activeAccount);

            String validJson = createAccountJson(USER_ID.toString(), "USD");

            mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(validJson))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/accounts/" + ACCOUNT_ID))
                    .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.accountNumber").value(ACCOUNT_NUMBER))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.balanceInMinorUnits").value(0)).andExpect(jsonPath("$.balance").value(0.00))
                    .andExpect(jsonPath("$.type").value("CUSTOMER")).andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.createdAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$.updatedAt").value(TIMESTAMP.toString()));

            verify(accountService).createCustomerAccount(USER_ID, "USD");
        }

        @Test
        @DisplayName("Should normalize lowercase currency code in request and succeed")
        void shouldCreateAccountSuccessfullyWhenCurrencyIsLowercase() throws Exception {
            when(accountService.createCustomerAccount(USER_ID, "USD")).thenReturn(activeAccount);

            String lowercaseJson = createAccountJson(USER_ID.toString(), "usd");

            mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(lowercaseJson))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/accounts/" + ACCOUNT_ID))
                    .andExpect(jsonPath("$.currency").value("USD"));

            verify(accountService).createCustomerAccount(USER_ID, "USD");
        }

        @Test
        @DisplayName("Should return 400 Bad Request when userId is null")
        void shouldReturn400WhenUserIdIsNull() throws Exception {
            String invalidJson = createAccountJson(null, "USD");

            mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.title").value("Validation Failed"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail", containsString("userId")))
                    .andExpect(jsonPath("$.detail", containsString("User ID must not be null")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when currency is null")
        void shouldReturn400WhenCurrencyIsNull() throws Exception {
            String invalidJson = createAccountJson(USER_ID.toString(), null);

            mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.title").value("Validation Failed"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail", containsString("currency")))
                    .andExpect(jsonPath("$.detail", containsString("Currency must not be blank")));
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        @DisplayName("Should return 400 Bad Request when currency is blank")
        void shouldReturn400WhenCurrencyIsBlank(String blankCurrency) throws Exception {
            String invalidJson = createAccountJson(USER_ID.toString(), blankCurrency);

            mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.title").value("Validation Failed"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail", containsString("currency")))
                    .andExpect(jsonPath("$.detail", containsString("Currency must not be blank")));
        }

        @ParameterizedTest
        @ValueSource(strings = { "US", "USDD", "123", "US$" })
        @DisplayName("Should return 400 Bad Request when currency regex format is invalid")
        void shouldReturn400WhenCurrencyRegexIsInvalid(String invalidCurrency) throws Exception {
            String invalidJson = createAccountJson(USER_ID.toString(), invalidCurrency);

            mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.title").value("Validation Failed"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail", containsString("currency"))).andExpect(jsonPath("$.detail",
                            containsString("Currency must be a valid 3-letter uppercase ISO 4217 code")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when request body is empty JSON object")
        void shouldReturn400WhenRequestBodyIsEmptyObject() throws Exception {
            mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
                    .andExpect(jsonPath("$.title").value("Validation Failed"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail", containsString("userId")))
                    .andExpect(jsonPath("$.detail", containsString("currency")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when request body is malformed JSON")
        void shouldReturn400WhenRequestBodyIsMalformed() throws Exception {
            mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("not-json"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request with ProblemDetail when service throws IllegalArgumentException")
        void shouldReturn400WhenServiceThrowsIllegalArgument() throws Exception {
            when(accountService.createCustomerAccount(USER_ID, "USD"))
                    .thenThrow(new IllegalArgumentException("Unsupported currency code: XXX"));

            String requestJson = createAccountJson(USER_ID.toString(), "USD");

            mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:bad-request"))
                    .andExpect(jsonPath("$.title").value("Invalid Argument"))
                    .andExpect(jsonPath("$.detail").value("Unsupported currency code: XXX"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/accounts/{id} - Get Account by ID")
    class GetAccountByIdTests {

        @Test
        @DisplayName("Should return 200 OK with full account details when found")
        void shouldGetAccountById() throws Exception {
            when(accountService.getAccount(ACCOUNT_ID)).thenReturn(activeAccount);

            mockMvc.perform(get("/api/v1/accounts/{id}", ACCOUNT_ID)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.accountNumber").value(ACCOUNT_NUMBER))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.balanceInMinorUnits").value(0)).andExpect(jsonPath("$.balance").value(0.00))
                    .andExpect(jsonPath("$.type").value("CUSTOMER")).andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.createdAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$.updatedAt").value(TIMESTAMP.toString()));

            verify(accountService).getAccount(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Should return 404 Not Found RFC 7807 when account does not exist")
        void shouldReturn404WhenNotFound() throws Exception {
            when(accountService.getAccount(ACCOUNT_ID)).thenThrow(new AccountNotFoundException(ACCOUNT_ID));

            mockMvc.perform(get("/api/v1/accounts/{id}", ACCOUNT_ID)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:account-not-found"))
                    .andExpect(jsonPath("$.title").value("Account Not Found"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when path variable is not a valid UUID")
        void shouldReturn400WhenIdIsNotValidUuid() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{id}", "invalid-uuid")).andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/accounts/number/{accountNumber} - Get Account by Number")
    class GetAccountByNumberTests {

        @Test
        @DisplayName("Should return 200 OK with full account details when found")
        void shouldGetAccountByNumber() throws Exception {
            when(accountService.getAccountByNumber(ACCOUNT_NUMBER)).thenReturn(activeAccount);

            mockMvc.perform(get("/api/v1/accounts/number/{accountNumber}", ACCOUNT_NUMBER)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.accountNumber").value(ACCOUNT_NUMBER))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.balanceInMinorUnits").value(0)).andExpect(jsonPath("$.balance").value(0.00))
                    .andExpect(jsonPath("$.type").value("CUSTOMER")).andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.createdAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$.updatedAt").value(TIMESTAMP.toString()));

            verify(accountService).getAccountByNumber(ACCOUNT_NUMBER);
        }

        @Test
        @DisplayName("Should return 404 Not Found RFC 7807 when account number does not exist")
        void shouldReturn404WhenNumberNotFound() throws Exception {
            when(accountService.getAccountByNumber(ACCOUNT_NUMBER))
                    .thenThrow(new AccountNotFoundException(ACCOUNT_NUMBER));

            mockMvc.perform(get("/api/v1/accounts/number/{accountNumber}", ACCOUNT_NUMBER))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:account-not-found"))
                    .andExpect(jsonPath("$.title").value("Account Not Found"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.accountNumber").value(ACCOUNT_NUMBER));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/accounts?userId={userId} - Get User Accounts")
    class GetAccountsByUserIdTests {

        @Test
        @DisplayName("Should return 200 OK with paginated accounts for user")
        void shouldGetAccountsByUserId() throws Exception {
            PageRequest pageRequest = PageRequest.of(0, 20);
            when(accountService.getAccountsByUserId(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(activeAccount), pageRequest, 1));

            mockMvc.perform(
                    get("/api/v1/accounts").param("userId", USER_ID.toString()).param("page", "0").param("size", "20"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id").value(ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.content[0].userId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.content[0].accountNumber").value(ACCOUNT_NUMBER))
                    .andExpect(jsonPath("$.content[0].currency").value("USD"))
                    .andExpect(jsonPath("$.content[0].balanceInMinorUnits").value(0))
                    .andExpect(jsonPath("$.content[0].balance").value(0.00))
                    .andExpect(jsonPath("$.content[0].type").value("CUSTOMER"))
                    .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                    .andExpect(jsonPath("$.content[0].createdAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$.content[0].updatedAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.number").value(0));

            verify(accountService).getAccountsByUserId(eq(USER_ID), any(Pageable.class));
        }

        @Test
        @DisplayName("Should return 200 OK with empty content when user has no accounts")
        void shouldReturnEmptyPageWhenUserHasNoAccounts() throws Exception {
            PageRequest pageRequest = PageRequest.of(0, 20);
            when(accountService.getAccountsByUserId(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), pageRequest, 0));

            mockMvc.perform(get("/api/v1/accounts").param("userId", USER_ID.toString())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0))).andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));

            verify(accountService).getAccountsByUserId(eq(USER_ID), any(Pageable.class));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when userId query parameter is missing")
        void shouldReturn400WhenUserIdParamMissing() throws Exception {
            mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Lifecycle Operations - Freeze, Activate, Close")
    class LifecycleTests {

        @Test
        @DisplayName("Should return 200 OK with FROZEN status when freezing account")
        void shouldFreezeAccount() throws Exception {
            Account frozenAccount = Account.createCustomer(USER_ID, ACCOUNT_NUMBER, "USD");
            ReflectionTestUtils.setField(frozenAccount, "id", ACCOUNT_ID);
            ReflectionTestUtils.setField(frozenAccount, "createdAt", TIMESTAMP);
            ReflectionTestUtils.setField(frozenAccount, "updatedAt", TIMESTAMP);
            frozenAccount.freeze();
            when(accountService.freezeAccount(ACCOUNT_ID)).thenReturn(frozenAccount);

            mockMvc.perform(put("/api/v1/accounts/{id}/freeze", ACCOUNT_ID)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.accountNumber").value(ACCOUNT_NUMBER))
                    .andExpect(jsonPath("$.currency").value("USD")).andExpect(jsonPath("$.status").value("FROZEN"));

            verify(accountService).freezeAccount(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Should return 404 Not Found when freezing non-existent account")
        void shouldReturn404WhenFreezingNonExistentAccount() throws Exception {
            when(accountService.freezeAccount(ACCOUNT_ID)).thenThrow(new AccountNotFoundException(ACCOUNT_ID));

            mockMvc.perform(put("/api/v1/accounts/{id}/freeze", ACCOUNT_ID)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:account-not-found"))
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()));
        }

        @Test
        @DisplayName("Should return 409 Conflict when attempting to freeze a closed account")
        void shouldReturn409WhenFreezingClosedAccount() throws Exception {
            when(accountService.freezeAccount(ACCOUNT_ID))
                    .thenThrow(new IllegalStateException("Cannot freeze a closed account"));

            mockMvc.perform(put("/api/v1/accounts/{id}/freeze", ACCOUNT_ID)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:invalid-state"))
                    .andExpect(jsonPath("$.title").value("Invalid State"))
                    .andExpect(jsonPath("$.detail").value("Cannot freeze a closed account"));
        }

        @Test
        @DisplayName("Should return 200 OK with ACTIVE status when activating account")
        void shouldActivateAccount() throws Exception {
            when(accountService.activateAccount(ACCOUNT_ID)).thenReturn(activeAccount);

            mockMvc.perform(put("/api/v1/accounts/{id}/activate", ACCOUNT_ID)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.accountNumber").value(ACCOUNT_NUMBER))
                    .andExpect(jsonPath("$.currency").value("USD")).andExpect(jsonPath("$.status").value("ACTIVE"));

            verify(accountService).activateAccount(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Should return 404 Not Found when activating non-existent account")
        void shouldReturn404WhenActivatingNonExistentAccount() throws Exception {
            when(accountService.activateAccount(ACCOUNT_ID)).thenThrow(new AccountNotFoundException(ACCOUNT_ID));

            mockMvc.perform(put("/api/v1/accounts/{id}/activate", ACCOUNT_ID)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:account-not-found"))
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()));
        }

        @Test
        @DisplayName("Should return 409 Conflict when attempting to activate a closed account")
        void shouldReturn409WhenActivatingClosedAccount() throws Exception {
            when(accountService.activateAccount(ACCOUNT_ID))
                    .thenThrow(new IllegalStateException("Cannot activate a closed account"));

            mockMvc.perform(put("/api/v1/accounts/{id}/activate", ACCOUNT_ID)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:invalid-state"))
                    .andExpect(jsonPath("$.title").value("Invalid State"))
                    .andExpect(jsonPath("$.detail").value("Cannot activate a closed account"));
        }

        @Test
        @DisplayName("Should return 204 No Content with empty body when closing account successfully")
        void shouldCloseAccount() throws Exception {
            when(accountService.closeAccount(ACCOUNT_ID)).thenReturn(activeAccount);

            mockMvc.perform(delete("/api/v1/accounts/{id}", ACCOUNT_ID)).andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            verify(accountService).closeAccount(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Should return 404 Not Found when closing non-existent account")
        void shouldReturn404WhenClosingNonExistentAccount() throws Exception {
            when(accountService.closeAccount(ACCOUNT_ID)).thenThrow(new AccountNotFoundException(ACCOUNT_ID));

            mockMvc.perform(delete("/api/v1/accounts/{id}", ACCOUNT_ID)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:account-not-found"))
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()));
        }

        @Test
        @DisplayName("Should return 409 Conflict when attempting to close account with non-zero balance")
        void shouldReturn409WhenClosingWithNonZeroBalance() throws Exception {
            when(accountService.closeAccount(ACCOUNT_ID))
                    .thenThrow(new IllegalStateException("Cannot close account with non-zero balance: 500"));

            mockMvc.perform(delete("/api/v1/accounts/{id}", ACCOUNT_ID)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:invalid-state"))
                    .andExpect(jsonPath("$.title").value("Invalid State"))
                    .andExpect(jsonPath("$.detail").value("Cannot close account with non-zero balance: 500"));
        }
    }
}
