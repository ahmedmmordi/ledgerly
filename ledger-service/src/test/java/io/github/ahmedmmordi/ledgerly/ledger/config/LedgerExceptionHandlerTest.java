package io.github.ahmedmmordi.ledgerly.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotActiveException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.CurrencyMismatchException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.InsufficientFundsException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.domain.TransferNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.idempotency.IdempotencyConflictException;
import io.github.ahmedmmordi.ledgerly.ledger.idempotency.IdempotencyPayloadMismatchException;
import java.net.URI;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

@DisplayName("LedgerExceptionHandler Unit Tests")
class LedgerExceptionHandlerTest {

    private LedgerExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LedgerExceptionHandler();
    }

    @Test
    @DisplayName("Should handle AccountNotFoundException with accountId")
    void shouldHandleAccountNotFoundWithId() {
        UUID accountId = UUID.randomUUID();
        AccountNotFoundException ex = new AccountNotFoundException(accountId);

        ProblemDetail problem = handler.handleAccountNotFound(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Account Not Found");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:account-not-found"));
        assertThat(problem.getDetail()).contains(accountId.toString());
        assertThat(problem.getProperties()).containsEntry("accountId", accountId.toString());
    }

    @Test
    @DisplayName("Should handle AccountNotFoundException with accountNumber")
    void shouldHandleAccountNotFoundWithNumber() {
        AccountNotFoundException ex = new AccountNotFoundException("ACC12345678");

        ProblemDetail problem = handler.handleAccountNotFound(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Account Not Found");
        assertThat(problem.getProperties()).containsEntry("accountNumber", "ACC12345678");
    }

    @Test
    @DisplayName("Should handle TransferNotFoundException")
    void shouldHandleTransferNotFound() {
        UUID transferId = UUID.randomUUID();
        TransferNotFoundException ex = new TransferNotFoundException(transferId);

        ProblemDetail problem = handler.handleTransferNotFound(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Transfer Not Found");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:transfer-not-found"));
        assertThat(problem.getProperties()).containsEntry("transferId", transferId.toString());
    }

    @Test
    @DisplayName("Should handle InsufficientFundsException")
    void shouldHandleInsufficientFunds() {
        UUID accountId = UUID.randomUUID();
        Money requested = Money.ofMinor(1000L, "USD");
        Money balance = Money.ofMinor(500L, "USD");
        InsufficientFundsException ex = new InsufficientFundsException(accountId, requested, balance);

        ProblemDetail problem = handler.handleInsufficientFunds(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(problem.getTitle()).isEqualTo("Insufficient Funds");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:insufficient-funds"));
        assertThat(problem.getProperties()).containsEntry("accountId", accountId.toString());
        assertThat(problem.getProperties()).containsEntry("requestedAmount", requested.toString());
        assertThat(problem.getProperties()).containsEntry("currentBalance", balance.toString());
    }

    @Test
    @DisplayName("Should handle CurrencyMismatchException")
    void shouldHandleCurrencyMismatch() {
        CurrencyMismatchException ex = new CurrencyMismatchException(Currency.getInstance("USD"),
                Currency.getInstance("EUR"));

        ProblemDetail problem = handler.handleCurrencyMismatch(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Currency Mismatch");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:currency-mismatch"));
        assertThat(problem.getDetail()).contains("USD", "EUR");
    }

    @Test
    @DisplayName("Should handle AccountNotActiveException")
    void shouldHandleAccountNotActive() {
        UUID accountId = UUID.randomUUID();
        AccountNotActiveException ex = new AccountNotActiveException(accountId, "FROZEN");

        ProblemDetail problem = handler.handleAccountNotActive(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Account Not Active");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:account-not-active"));
        assertThat(problem.getProperties()).containsEntry("accountId", accountId.toString());
        assertThat(problem.getProperties()).containsEntry("accountStatus", "FROZEN");
    }

    @Test
    @DisplayName("Should handle IllegalStateException")
    void shouldHandleIllegalState() {
        IllegalStateException ex = new IllegalStateException("Cannot freeze a closed account");

        ProblemDetail problem = handler.handleIllegalState(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid State");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:invalid-state"));
        assertThat(problem.getDetail()).isEqualTo("Cannot freeze a closed account");
    }

    @Test
    @DisplayName("Should handle IllegalArgumentException")
    void shouldHandleIllegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("Currency must not be blank");

        ProblemDetail problem = handler.handleIllegalArgument(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid Argument");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:bad-request"));
        assertThat(problem.getDetail()).isEqualTo("Currency must not be blank");
    }

    @Test
    @DisplayName("Should handle IdempotencyConflictException with idempotencyKey")
    void shouldHandleIdempotencyConflict() {
        IdempotencyConflictException ex = new IdempotencyConflictException("key-conflict-123");

        ProblemDetail problem = handler.handleIdempotencyConflict(ex);

        assertThat(ex.getIdempotencyKey()).isEqualTo("key-conflict-123");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Idempotency Conflict");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:idempotency-conflict"));
        assertThat(problem.getDetail())
                .isEqualTo("Idempotency key 'key-conflict-123' is currently being processed by another request");
        assertThat(problem.getProperties()).containsEntry("idempotencyKey", "key-conflict-123");
    }

    @Test
    @DisplayName("Should handle IdempotencyConflictException with null key")
    void shouldHandleIdempotencyConflictWithNullKey() {
        IdempotencyConflictException ex = new IdempotencyConflictException(null);

        ProblemDetail problem = handler.handleIdempotencyConflict(ex);

        assertThat(ex.getIdempotencyKey()).isNull();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Idempotency Conflict");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:idempotency-conflict"));
        assertThat(problem.getDetail())
                .isEqualTo("Idempotency key 'null' is currently being processed by another request");
        assertThat(problem.getProperties()).isNull();
    }

    @Test
    @DisplayName("Should handle IdempotencyPayloadMismatchException with default message and key")
    void shouldHandleIdempotencyPayloadMismatch() {
        IdempotencyPayloadMismatchException ex = new IdempotencyPayloadMismatchException("key-mismatch-456");

        ProblemDetail problem = handler.handleIdempotencyPayloadMismatch(ex);

        assertThat(ex.getIdempotencyKey()).isEqualTo("key-mismatch-456");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(problem.getTitle()).isEqualTo("Idempotency Payload Mismatch");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:idempotency-payload-mismatch"));
        assertThat(problem.getDetail())
                .isEqualTo("Payload for idempotency key 'key-mismatch-456' does not match the original request");
        assertThat(problem.getProperties()).containsEntry("idempotencyKey", "key-mismatch-456");
    }

    @Test
    @DisplayName("Should handle IdempotencyPayloadMismatchException with custom message and non-null key")
    void shouldHandleIdempotencyPayloadMismatchWithCustomMessageAndKey() {
        IdempotencyPayloadMismatchException ex = new IdempotencyPayloadMismatchException("key-mismatch-789",
                "Endpoint mismatch for idempotency key 'key-mismatch-789'");

        ProblemDetail problem = handler.handleIdempotencyPayloadMismatch(ex);

        assertThat(ex.getIdempotencyKey()).isEqualTo("key-mismatch-789");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(problem.getTitle()).isEqualTo("Idempotency Payload Mismatch");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:idempotency-payload-mismatch"));
        assertThat(problem.getDetail()).isEqualTo("Endpoint mismatch for idempotency key 'key-mismatch-789'");
        assertThat(problem.getProperties()).containsEntry("idempotencyKey", "key-mismatch-789");
    }

    @Test
    @DisplayName("Should handle IdempotencyPayloadMismatchException with custom message and null key")
    void shouldHandleIdempotencyPayloadMismatchWithCustomMessageAndNullKey() {
        IdempotencyPayloadMismatchException ex = new IdempotencyPayloadMismatchException(null,
                "Endpoint mismatch for idempotency key 'null'");

        ProblemDetail problem = handler.handleIdempotencyPayloadMismatch(ex);

        assertThat(ex.getIdempotencyKey()).isNull();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(problem.getTitle()).isEqualTo("Idempotency Payload Mismatch");
        assertThat(problem.getType()).isEqualTo(URI.create("urn:problem-type:idempotency-payload-mismatch"));
        assertThat(problem.getDetail()).isEqualTo("Endpoint mismatch for idempotency key 'null'");
        assertThat(problem.getProperties()).isNull();
    }
}
