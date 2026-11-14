package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import io.github.ahmedmmordi.ledgerly.ledger.domain.Money;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.DepositRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.LedgerEntryResponse;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferRequest;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        Money amount = Money.ofMinor(request.amount(), request.currency());
        Transfer transfer = transferService.transfer(request.fromAccountId(), request.toAccountId(), amount,
                request.description(), request.initiatedByUserId());
        TransferResponse response = TransferResponse.from(transfer);
        URI location = URI.create("/api/v1/transfers/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransferResponse> deposit(@Valid @RequestBody DepositRequest request) {
        Money amount = Money.ofMinor(request.amount(), request.currency());
        Transfer transfer = transferService.deposit(request.accountId(), amount, request.description(),
                request.initiatedByUserId());
        TransferResponse response = TransferResponse.from(transfer);
        URI location = URI.create("/api/v1/transfers/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransferById(@PathVariable UUID id) {
        Transfer transfer = transferService.getTransfer(id);
        return ResponseEntity.ok(TransferResponse.from(transfer));
    }

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

    @GetMapping("/{id}/entries")
    public ResponseEntity<List<LedgerEntryResponse>> getLedgerEntriesByTransferId(@PathVariable UUID id) {
        Transfer transfer = transferService.getTransfer(id);
        List<LedgerEntryResponse> entries = transferService.getLedgerEntriesByTransferId(id).stream()
                .map(entry -> LedgerEntryResponse.from(entry, transfer.getCurrency())).toList();
        return ResponseEntity.ok(entries);
    }
}
