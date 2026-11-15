package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import io.github.ahmedmmordi.ledgerly.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "idempotency_keys", uniqueConstraints = {
        @UniqueConstraint(name = "uq_idempotency_keys_user_key", columnNames = { "user_id", "idempotency_key" }) })
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class IdempotencyKey extends BaseEntity {

    private static final String KEY_REGEX = "^[A-Za-z0-9_.:-]{1,128}$";
    private static final String HASH_REGEX = "^[a-f0-9]{64}$";

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "endpoint", nullable = false, length = 100)
    private String endpoint;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static IdempotencyKey create(UUID userId, String idempotencyKey, String endpoint, String requestHash,
            Instant expiresAt) {
        Objects.requireNonNull(userId, "User ID must not be null");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must not be null");
        Objects.requireNonNull(endpoint, "Endpoint must not be null");
        Objects.requireNonNull(requestHash, "Request hash must not be null");
        Objects.requireNonNull(expiresAt, "Expires at must not be null");

        String trimmedKey = idempotencyKey.trim();
        if (!trimmedKey.matches(KEY_REGEX)) {
            throw new IllegalArgumentException(
                    "Idempotency key must match pattern " + KEY_REGEX + ", got: " + idempotencyKey);
        }

        String trimmedEndpoint = endpoint.trim();
        if (trimmedEndpoint.isEmpty() || trimmedEndpoint.length() > 100) {
            throw new IllegalArgumentException("Endpoint must not be blank and must not exceed 100 characters");
        }

        String normalizedHash = requestHash.trim().toLowerCase();
        if (!normalizedHash.matches(HASH_REGEX)) {
            throw new IllegalArgumentException(
                    "Request hash must be a 64-character hexadecimal SHA-256 digest, got: " + requestHash);
        }

        return IdempotencyKey.builder().userId(userId).idempotencyKey(trimmedKey).endpoint(trimmedEndpoint)
                .requestHash(normalizedHash).status(IdempotencyStatus.PROCESSING).expiresAt(expiresAt).build();
    }

    public void complete(int responseStatus, String responseBody) {
        if (this.status != IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException("Cannot complete idempotency key in status " + this.status);
        }
        validateHttpStatus(responseStatus);
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    public void fail() {
        fail(null, null);
    }

    public void fail(Integer responseStatus, String responseBody) {
        if (this.status != IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException("Cannot fail idempotency key in status " + this.status);
        }
        if (responseStatus != null) {
            validateHttpStatus(responseStatus);
        }
        this.status = IdempotencyStatus.FAILED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "Instant must not be null");
        return now.isAfter(this.expiresAt);
    }

    public boolean isProcessing() {
        return this.status == IdempotencyStatus.PROCESSING;
    }

    public boolean isCompleted() {
        return this.status == IdempotencyStatus.COMPLETED;
    }

    public boolean isFailed() {
        return this.status == IdempotencyStatus.FAILED;
    }

    public boolean matchesHash(String hash) {
        if (hash == null) {
            return false;
        }
        return Objects.equals(this.requestHash, hash.trim().toLowerCase());
    }

    public boolean matchesEndpoint(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        return Objects.equals(this.endpoint, endpoint.trim());
    }

    private static void validateHttpStatus(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Response status must be a valid HTTP status code: " + status);
        }
    }
}
