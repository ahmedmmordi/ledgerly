package io.github.ahmedmmordi.ledgerly.ledger.transfer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findByFromAccountId(UUID fromAccountId);

    List<Transfer> findByToAccountId(UUID toAccountId);

    @Query("SELECT t FROM Transfer t WHERE t.fromAccountId = :accountId OR t.toAccountId = :accountId ORDER BY t.createdAt DESC")
    Page<Transfer> findByAccountId(@Param("accountId") UUID accountId, Pageable pageable);

    Page<Transfer> findByInitiatedByUserId(UUID initiatedByUserId, Pageable pageable);

    List<Transfer> findByStatus(TransferStatus status);

    List<Transfer> findByStatusAndCreatedAtBefore(TransferStatus status, Instant timestamp);
}
