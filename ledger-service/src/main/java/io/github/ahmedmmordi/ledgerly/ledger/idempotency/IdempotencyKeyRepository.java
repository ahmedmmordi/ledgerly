package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({ @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000") })
    @Query("SELECT k FROM IdempotencyKey k WHERE k.userId = :userId AND k.idempotencyKey = :idempotencyKey")
    Optional<IdempotencyKey> findByUserIdAndIdempotencyKeyForUpdate(@Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey);

    boolean existsByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    List<IdempotencyKey> findByStatusAndCreatedAtBefore(IdempotencyStatus status, Instant threshold);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") Instant threshold);
}
