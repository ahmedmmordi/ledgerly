package io.github.ahmedmmordi.ledgerly.ledger.config;

import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotActiveException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.AccountNotFoundException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.CurrencyMismatchException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.InsufficientFundsException;
import io.github.ahmedmmordi.ledgerly.ledger.domain.TransferNotFoundException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LedgerExceptionHandler {

    private static final URI ACCOUNT_NOT_FOUND_TYPE = URI.create("urn:problem-type:account-not-found");
    private static final URI TRANSFER_NOT_FOUND_TYPE = URI.create("urn:problem-type:transfer-not-found");
    private static final URI INSUFFICIENT_FUNDS_TYPE = URI.create("urn:problem-type:insufficient-funds");
    private static final URI CURRENCY_MISMATCH_TYPE = URI.create("urn:problem-type:currency-mismatch");
    private static final URI ACCOUNT_NOT_ACTIVE_TYPE = URI.create("urn:problem-type:account-not-active");
    private static final URI INVALID_STATE_TYPE = URI.create("urn:problem-type:invalid-state");
    private static final URI BAD_REQUEST_TYPE = URI.create("urn:problem-type:bad-request");

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Account Not Found");
        problem.setType(ACCOUNT_NOT_FOUND_TYPE);
        if (ex.getAccountId() != null) {
            problem.setProperty("accountId", ex.getAccountId().toString());
        }
        if (ex.getAccountNumber() != null) {
            problem.setProperty("accountNumber", ex.getAccountNumber());
        }
        return problem;
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ProblemDetail handleTransferNotFound(TransferNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Transfer Not Found");
        problem.setType(TRANSFER_NOT_FOUND_TYPE);
        if (ex.getTransferId() != null) {
            problem.setProperty("transferId", ex.getTransferId().toString());
        }
        return problem;
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Insufficient Funds");
        problem.setType(INSUFFICIENT_FUNDS_TYPE);
        if (ex.getAccountId() != null) {
            problem.setProperty("accountId", ex.getAccountId().toString());
        }
        if (ex.getRequestedAmount() != null) {
            problem.setProperty("requestedAmount", ex.getRequestedAmount().toString());
        }
        if (ex.getCurrentBalance() != null) {
            problem.setProperty("currentBalance", ex.getCurrentBalance().toString());
        }
        return problem;
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Currency Mismatch");
        problem.setType(CURRENCY_MISMATCH_TYPE);
        return problem;
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public ProblemDetail handleAccountNotActive(AccountNotActiveException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Account Not Active");
        problem.setType(ACCOUNT_NOT_ACTIVE_TYPE);
        if (ex.getAccountId() != null) {
            problem.setProperty("accountId", ex.getAccountId().toString());
        }
        if (ex.getStatus() != null) {
            problem.setProperty("accountStatus", ex.getStatus());
        }
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid State");
        problem.setType(INVALID_STATE_TYPE);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Argument");
        problem.setType(BAD_REQUEST_TYPE);
        return problem;
    }
}
