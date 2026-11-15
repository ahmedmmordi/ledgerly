package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.idempotency.IdempotencyService;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.DepositRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.LedgerEntryResponse;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfers", description = "Endpoints for money transfers, cash deposits, and ledger history")
@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    public static final String TRANSFER_ENDPOINT = "POST /api/v1/transfers";
    public static final String DEPOSIT_ENDPOINT = "POST /api/v1/transfers/deposit";

    private final TransferService transferService;
    private final IdempotencyService idempotencyService;

    @Operation(summary = "Execute transfer", description = "Transfers money atomically between two accounts with balanced double-entry ledger entries")
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @Parameter(description = "Optional unique idempotency key to prevent duplicate transfers") @RequestHeader(value = IdempotencyService.IDEMPOTENCY_KEY_HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return executeTransfer(request);
        }
        UUID userId = request.initiatedByUserId() != null ? request.initiatedByUserId() : request.fromAccountId();
        return idempotencyService.execute(userId, idempotencyKey, TRANSFER_ENDPOINT, request, TransferResponse.class,
                () -> executeTransfer(request));
    }

    private ResponseEntity<TransferResponse> executeTransfer(TransferRequest request) {
        Money amount = Money.ofMinor(request.amount(), request.currency());
        Transfer transfer = transferService.transfer(request.fromAccountId(), request.toAccountId(), amount,
                request.description(), request.initiatedByUserId());
        return toCreatedResponse(transfer);
    }

    @Operation(summary = "Deposit funds", description = "Deposits cash into an account from the system cash vault")
    @PostMapping("/deposit")
    public ResponseEntity<TransferResponse> deposit(
            @Parameter(description = "Optional unique idempotency key to prevent duplicate deposits") @RequestHeader(value = IdempotencyService.IDEMPOTENCY_KEY_HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return executeDeposit(request);
        }
        return idempotencyService.execute(request.initiatedByUserId(), idempotencyKey, DEPOSIT_ENDPOINT, request,
                TransferResponse.class, () -> executeDeposit(request));
    }

    private ResponseEntity<TransferResponse> executeDeposit(DepositRequest request) {
        Money amount = Money.ofMinor(request.amount(), request.currency());
        Transfer transfer = transferService.deposit(request.accountId(), amount, request.description(),
                request.initiatedByUserId());
        return toCreatedResponse(transfer);
    }

    private ResponseEntity<TransferResponse> toCreatedResponse(Transfer transfer) {
        TransferResponse response = TransferResponse.from(transfer);
        URI location = URI.create("/api/v1/transfers/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "Get transfer by ID", description = "Retrieves transfer status and transaction details by ID")
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransferById(@PathVariable UUID id) {
        Transfer transfer = transferService.getTransfer(id);
        return ResponseEntity.ok(TransferResponse.from(transfer));
    }

    @Operation(summary = "Query transfers", description = "Retrieves paginated transfers filtered by account ID or initiated user ID")
    @GetMapping
    public ResponseEntity<Page<TransferResponse>> getTransfers(@RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID userId, @PageableDefault(size = 20) Pageable pageable) {
        if (accountId != null && userId != null) {
            throw new IllegalArgumentException("Cannot filter transfers by both accountId and userId simultaneously");
        }
        if (accountId != null) {
            Page<TransferResponse> transfers = transferService.getTransfersByAccountId(accountId, pageable)
                    .map(TransferResponse::from);
            return ResponseEntity.ok(transfers);
        }
        if (userId != null) {
            Page<TransferResponse> transfers = transferService.getTransfersByInitiatedUserId(userId, pageable)
                    .map(TransferResponse::from);
            return ResponseEntity.ok(transfers);
        }
        throw new IllegalArgumentException("Either accountId or userId query parameter must be provided");
    }

    @Operation(summary = "Get ledger entries", description = "Retrieves balanced double-entry ledger entries for a completed transfer")
    @GetMapping("/{id}/entries")
    public ResponseEntity<List<LedgerEntryResponse>> getLedgerEntriesByTransferId(@PathVariable UUID id) {
        Transfer transfer = transferService.getTransfer(id);
        List<LedgerEntryResponse> entries = transferService.getLedgerEntriesByTransferId(id).stream()
                .map(entry -> LedgerEntryResponse.from(entry, transfer.getCurrency())).toList();
        return ResponseEntity.ok(entries);
    }
}
