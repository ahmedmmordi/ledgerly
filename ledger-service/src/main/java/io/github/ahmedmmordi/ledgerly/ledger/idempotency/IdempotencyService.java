package io.github.ahmedmmordi.ledgerly.ledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.ahmedmmordi.ledgerly.ledger.transfer.dto.TransferResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@NullMarked
public class IdempotencyService {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String X_SERVED_FROM_CACHE_HEADER = "X-Served-From-Cache";

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final Duration ttl;

    @Autowired
    public IdempotencyService(IdempotencyKeyRepository repository, PlatformTransactionManager transactionManager,
            @Nullable ObjectMapper objectMapper, @Value("${ledger.idempotency.ttl:24h}") Duration ttl) {
        this.repository = Objects.requireNonNull(repository, "Repository must not be null");
        Objects.requireNonNull(transactionManager, "Transaction manager must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ttl = Objects.requireNonNull(ttl, "TTL must not be null");
    }

    private static ObjectMapper createDefaultObjectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    }

    public <T> ResponseEntity<T> execute(UUID userId, String idempotencyKey, String endpoint, Object requestPayload,
            Class<T> responseType, Supplier<ResponseEntity<T>> action) {
        Objects.requireNonNull(userId, "User ID must not be null");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must not be null");
        Objects.requireNonNull(endpoint, "Endpoint must not be null");
        Objects.requireNonNull(requestPayload, "Request payload must not be null");
        Objects.requireNonNull(responseType, "Response type must not be null");
        Objects.requireNonNull(action, "Action must not be null");

        String trimmedKey = idempotencyKey.trim();
        if (trimmedKey.isEmpty()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }

        String requestHash = computeHash(requestPayload);
        ReservationResult reservation = reserveKey(userId, trimmedKey, endpoint, requestHash, this.ttl);

        if (reservation.isCached()) {
            return buildCachedResponse(reservation.key(), responseType);
        }

        try {
            ResponseEntity<T> response = action.get();
            completeKey(reservation.key().getId(), response);
            return response;
        } catch (RuntimeException ex) {
            failKey(reservation.key().getId());
            throw ex;
        }
    }

    public ReservationResult reserveKey(UUID userId, String key, String endpoint, String requestHash, Duration ttl) {
        Objects.requireNonNull(userId, "User ID must not be null");
        Objects.requireNonNull(key, "Idempotency key must not be null");
        Objects.requireNonNull(endpoint, "Endpoint must not be null");
        Objects.requireNonNull(requestHash, "Request hash must not be null");
        Objects.requireNonNull(ttl, "TTL must not be null");

        String trimmedKey = key.trim();

        ReservationResult result;
        try {
            result = requiresNewTransactionTemplate.execute(_ -> {
                Optional<IdempotencyKey> existingOpt = repository.findByUserIdAndIdempotencyKey(userId, trimmedKey);
                if (existingOpt.isPresent()) {
                    IdempotencyKey existing = existingOpt.get();
                    if (existing.isExpired(Instant.now()) || existing.isFailed()) {
                        repository.delete(existing);
                        repository.flush();
                    } else if (existing.isProcessing()) {
                        throw new IdempotencyConflictException(trimmedKey);
                    } else if (existing.isCompleted()) {
                        return validateCompletedKey(existing, trimmedKey, endpoint, requestHash);
                    }
                }

                Instant expiresAt = Instant.now().plus(ttl);
                IdempotencyKey newKey = IdempotencyKey.create(userId, trimmedKey, endpoint, requestHash, expiresAt);
                IdempotencyKey saved = repository.saveAndFlush(newKey);
                return ReservationResult.reserved(saved);
            });
        } catch (DataIntegrityViolationException ex) {
            // Transaction was aborted and rolled back. In a fresh transaction, inspect the concurrent key.
            result = requiresNewTransactionTemplate.execute(_ -> {
                IdempotencyKey concurrent = repository.findByUserIdAndIdempotencyKey(userId, trimmedKey)
                        .orElseThrow(() -> ex);
                if (concurrent.isProcessing()) {
                    throw new IdempotencyConflictException(trimmedKey);
                }
                if (concurrent.isCompleted()) {
                    return validateCompletedKey(concurrent, trimmedKey, endpoint, requestHash);
                }
                throw new IdempotencyConflictException(trimmedKey);
            });
        }

        return Objects.requireNonNull(result, "Transaction failed to return reservation result");
    }

    private ReservationResult validateCompletedKey(IdempotencyKey key, String trimmedKey, String endpoint,
            String requestHash) {
        if (!key.matchesEndpoint(endpoint)) {
            throw new IdempotencyPayloadMismatchException(trimmedKey, "Endpoint mismatch for idempotency key '"
                    + trimmedKey + "': expected " + key.getEndpoint() + ", got " + endpoint);
        }
        if (!key.matchesHash(requestHash)) {
            throw new IdempotencyPayloadMismatchException(trimmedKey,
                    "Payload hash mismatch for idempotency key '" + trimmedKey + "'");
        }
        return ReservationResult.cached(key);
    }

    public void completeKey(UUID keyId, int responseStatus, @Nullable String responseBody) {
        Objects.requireNonNull(keyId, "Key ID must not be null");
        requiresNewTransactionTemplate.executeWithoutResult(_ -> {
            IdempotencyKey key = repository.findById(keyId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key not found: " + keyId));
            key.complete(responseStatus, responseBody);
            repository.saveAndFlush(key);
        });
    }

    public <T> void completeKey(UUID keyId, ResponseEntity<T> response) {
        Objects.requireNonNull(keyId, "Key ID must not be null");
        Objects.requireNonNull(response, "Response must not be null");
        String responseBody = null;
        if (response.getBody() != null) {
            try {
                responseBody = objectMapper.writeValueAsString(response.getBody());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize response body for idempotency key", e);
            }
        }
        completeKey(keyId, response.getStatusCode().value(), responseBody);
    }

    public void failKey(@Nullable UUID keyId) {
        failKey(keyId, null, null);
    }

    public void failKey(@Nullable UUID keyId, @Nullable Integer responseStatus, @Nullable String responseBody) {
        if (keyId == null) {
            return;
        }
        try {
            requiresNewTransactionTemplate.executeWithoutResult(
                    _ -> repository.findById(keyId).filter(IdempotencyKey::isProcessing).ifPresent(key -> {
                        key.fail(responseStatus, responseBody);
                        repository.saveAndFlush(key);
                    }));
        } catch (Exception ex) {
            log.warn("Failed to mark idempotency key {} as FAILED: {}", keyId, ex.getMessage());
        }
    }

    public String computeHash(Object payload) {
        Objects.requireNonNull(payload, "Payload must not be null");
        try {
            byte[] bytes;
            if (payload instanceof String s) {
                bytes = s.getBytes(StandardCharsets.UTF_8);
            } else if (payload instanceof byte[] b) {
                bytes = b;
            } else {
                bytes = objectMapper.writeValueAsBytes(payload);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize payload to JSON for hashing", e);
        }
    }

    private <T> ResponseEntity<T> buildCachedResponse(IdempotencyKey key, Class<T> responseType) {
        try {
            T body = null;
            if (key.getResponseBody() != null && !key.getResponseBody().isBlank()) {
                body = objectMapper.readValue(key.getResponseBody(), responseType);
            }
            int status = key.getResponseStatus() != null ? key.getResponseStatus() : 200;
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).header(X_SERVED_FROM_CACHE_HEADER,
                    "true");

            if (status == 201 && body instanceof TransferResponse tr) {
                builder.location(URI.create("/api/v1/transfers/" + tr.id()));
            }

            return builder.body(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
        }
    }
}
