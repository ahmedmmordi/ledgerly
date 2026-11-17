package io.github.ahmedmmordi.ledgerly.statement.statement;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ahmedmmordi.ledgerly.common.exception.GlobalExceptionHandler;
import io.github.ahmedmmordi.ledgerly.statement.statement.dto.StatementEntryResponse;
import io.github.ahmedmmordi.ledgerly.statement.statement.dto.StatementResponse;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@NullMarked
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementController WebMvc Standalone Tests")
@SuppressWarnings({ "null", "ConstantConditions" })
class StatementControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("0193297a-9a90-7888-8123-000000000001");
    private static final YearMonth FROM = YearMonth.of(2026, 1);
    private static final YearMonth TO = YearMonth.of(2026, 3);
    private static final Instant TIMESTAMP = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private StatementService statementService;

    @InjectMocks
    private StatementController statementController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(statementController)
                .setControllerAdvice(new StatementExceptionHandler(), new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    }

    @Nested
    @DisplayName("GET /api/v1/accounts/{accountId}/statements - Query Paged Statements")
    class QueryPagedStatementsTests {

        @Test
        @DisplayName("Should return 200 OK with paginated JSON and exact BigDecimal values when query is valid")
        void shouldReturn200WithPagedJsonWhenQueryIsValid() throws Exception {
            StatementEntryResponse entry = new StatementEntryResponse(StatementEntryType.CREDIT,
                    BigDecimal.valueOf(250_00L, 2), BigDecimal.valueOf(1250_00L, 2), TIMESTAMP);
            StatementResponse statementResponse = new StatementResponse(ACCOUNT_ID, "2026-01", "USD",
                    BigDecimal.valueOf(1000_00L, 2), BigDecimal.valueOf(1250_00L, 2), List.of(entry));

            Page<StatementResponse> page = new PageImpl<>(List.of(statementResponse), PageRequest.of(0, 12), 1);
            when(statementService.getStatements(eq(ACCOUNT_ID), eq(FROM), eq(TO), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements", ACCOUNT_ID).param("from", "2026-01")
                    .param("to", "2026-03").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].accountId").value(ACCOUNT_ID.toString()))
                    .andExpect(jsonPath("$.content[0].yearMonth").value("2026-01"))
                    .andExpect(jsonPath("$.content[0].currency").value("USD"))
                    .andExpect(jsonPath("$.content[0].openingBalanceMajorUnits").value(1000.00))
                    .andExpect(jsonPath("$.content[0].closingBalanceMajorUnits").value(1250.00))
                    .andExpect(jsonPath("$.content[0].entries", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].entries[0].type").value("CREDIT"))
                    .andExpect(jsonPath("$.content[0].entries[0].amountMajorUnits").value(250.00))
                    .andExpect(jsonPath("$.content[0].entries[0].balanceAfterMajorUnits").value(1250.00))
                    .andExpect(jsonPath("$.content[0].entries[0].occurredAt").value(TIMESTAMP.toString()))
                    .andExpect(jsonPath("$.totalElements").value(1));

            verify(statementService).getStatements(eq(ACCOUNT_ID), eq(FROM), eq(TO), eq(PageRequest.of(0, 12)));
        }

        @Test
        @DisplayName("Should pass custom pagination parameters (page and size) to service")
        void shouldPassCustomPaginationParametersToService() throws Exception {
            when(statementService.getStatements(eq(ACCOUNT_ID), eq(FROM), eq(TO), eq(PageRequest.of(2, 5))))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements", ACCOUNT_ID).param("from", "2026-01")
                    .param("to", "2026-03").param("page", "2").param("size", "5").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            verify(statementService).getStatements(eq(ACCOUNT_ID), eq(FROM), eq(TO), eq(PageRequest.of(2, 5)));
        }

        @Test
        @DisplayName("Should return 200 OK when 'from' equals 'to' for a single-month query")
        void shouldReturn200WhenFromEqualsToForSingleMonthQuery() throws Exception {
            when(statementService.getStatements(eq(ACCOUNT_ID), eq(FROM), eq(FROM), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 12), 0));

            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements", ACCOUNT_ID).param("from", "2026-01")
                    .param("to", "2026-01").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

            verify(statementService).getStatements(eq(ACCOUNT_ID), eq(FROM), eq(FROM), any(Pageable.class));
        }

        @Test
        @DisplayName("Should return 200 OK with empty content when no statements exist in range")
        void shouldReturn200WithEmptyPageWhenNoStatementsExist() throws Exception {
            when(statementService.getStatements(eq(ACCOUNT_ID), eq(FROM), eq(TO), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 12), 0));

            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements", ACCOUNT_ID).param("from", "2026-01")
                    .param("to", "2026-03").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0))).andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when 'from' parameter is missing")
        void shouldReturn400WhenFromParameterIsMissing() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements", ACCOUNT_ID).param("to", "2026-03"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when 'to' parameter is missing")
        void shouldReturn400WhenToParameterIsMissing() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements", ACCOUNT_ID).param("from", "2026-01"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when date format is invalid")
        void shouldReturn400WhenDateFormatIsInvalid() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements", ACCOUNT_ID).param("from", "invalid-month")
                    .param("to", "2026-03")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when account ID is not a valid UUID")
        void shouldReturn400WhenAccountIdIsNotValidUuid() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements", "not-a-uuid").param("from", "2026-01")
                    .param("to", "2026-03")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request RFC 7807 ProblemDetail when 'from' is after 'to'")
        void shouldReturn400ProblemDetailWhenFromIsAfterTo() throws Exception {
            when(statementService.getStatements(eq(ACCOUNT_ID), eq(YearMonth.of(2026, 12)), eq(YearMonth.of(2026, 1)),
                    any(Pageable.class))).thenThrow(
                            new IllegalArgumentException("From date (2026-12) must not be after to date (2026-01)"));

            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements", ACCOUNT_ID).param("from", "2026-12")
                    .param("to", "2026-01")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:bad-request"))
                    .andExpect(jsonPath("$.title").value("Invalid Argument")).andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail",
                            containsString("From date (2026-12) must not be after to date (2026-01)")));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/accounts/{accountId}/statements.csv - Stream CSV Export")
    class StreamCsvExportTests {

        @Test
        @DisplayName("Should return 200 OK with CSV headers and streamed content")
        void shouldReturn200WithCsvHeadersAndStreamedContent() throws Exception {
            String csvContent = "date,type,amount,currency,balanceAfter\n2026-01-15T10:00:00Z,CREDIT,250.00,USD,1250.00\n";
            doAnswer(invocation -> {
                OutputStream os = invocation.getArgument(3);
                os.write(csvContent.getBytes(StandardCharsets.UTF_8));
                return null;
            }).when(statementService).streamCsvEntries(eq(ACCOUNT_ID), eq(FROM), eq(TO), any(OutputStream.class));

            MvcResult mvcResult = mockMvc
                    .perform(get("/api/v1/accounts/{accountId}/statements.csv", ACCOUNT_ID).param("from", "2026-01")
                            .param("to", "2026-03"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            containsString("attachment; filename=\"statements.csv\"")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk())
                    .andExpect(content().contentType("text/csv;charset=UTF-8")).andExpect(content().string(csvContent));

            verify(statementService).streamCsvEntries(eq(ACCOUNT_ID), eq(FROM), eq(TO), any(OutputStream.class));
        }

        @Test
        @DisplayName("Should return 200 OK when using alternative /statements/csv route")
        void shouldReturn200WhenUsingAlternativeSlashCsvRoute() throws Exception {
            String csvHeaderOnly = "date,type,amount,currency,balanceAfter\n";
            doAnswer(invocation -> {
                OutputStream os = invocation.getArgument(3);
                os.write(csvHeaderOnly.getBytes(StandardCharsets.UTF_8));
                return null;
            }).when(statementService).streamCsvEntries(eq(ACCOUNT_ID), eq(FROM), eq(TO), any(OutputStream.class));

            MvcResult mvcResult = mockMvc
                    .perform(get("/api/v1/accounts/{accountId}/statements/csv", ACCOUNT_ID).param("from", "2026-01")
                            .param("to", "2026-03"))
                    .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();

            mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk())
                    .andExpect(content().string(csvHeaderOnly));
        }

        @Test
        @DisplayName("Should return 200 OK when 'from' equals 'to' for a single-month query on CSV endpoint")
        void shouldReturn200WhenFromEqualsToOnCsvEndpoint() throws Exception {
            MvcResult mvcResult = mockMvc
                    .perform(get("/api/v1/accounts/{accountId}/statements.csv", ACCOUNT_ID).param("from", "2026-01")
                            .param("to", "2026-01"))
                    .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();

            mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

            verify(statementService).streamCsvEntries(eq(ACCOUNT_ID), eq(FROM), eq(FROM), any(OutputStream.class));
        }

        @Test
        @DisplayName("Should return 400 Bad Request ProblemDetail when 'from' is after 'to' on CSV endpoint")
        void shouldReturn400ProblemDetailWhenFromIsAfterToOnCsvEndpoint() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements.csv", ACCOUNT_ID).param("from", "2026-12")
                    .param("to", "2026-01")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:bad-request"))
                    .andExpect(jsonPath("$.title").value("Invalid Argument")).andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail",
                            containsString("From date (2026-12) must not be after to date (2026-01)")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when 'from' parameter is missing on CSV endpoint")
        void shouldReturn400WhenFromParamIsMissingOnCsvEndpoint() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements.csv", ACCOUNT_ID).param("to", "2026-03"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when 'to' parameter is missing on CSV endpoint")
        void shouldReturn400WhenToParamIsMissingOnCsvEndpoint() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements.csv", ACCOUNT_ID).param("from", "2026-01"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when date format is invalid on CSV endpoint")
        void shouldReturn400WhenDateFormatIsInvalidOnCsvEndpoint() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{accountId}/statements.csv", ACCOUNT_ID).param("from", "2026-99")
                    .param("to", "2026-03")).andExpect(status().isBadRequest());
        }
    }
}
