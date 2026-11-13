package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransferId(UUID transferId);

    List<LedgerEntry> findByTransferIdOrderByTypeAsc(UUID transferId);

    List<LedgerEntry> findByAccountId(UUID accountId);

    Page<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    Optional<LedgerEntry> findFirstByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
