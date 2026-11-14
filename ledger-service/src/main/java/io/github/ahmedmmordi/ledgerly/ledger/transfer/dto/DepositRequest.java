package io.github.ahmedmmordi.ledgerly.ledger.transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record DepositRequest(@NotNull(message = "Account ID must not be null") UUID accountId,

        @NotNull(message = "Amount must not be null") @Positive(message = "Amount must be strictly positive") Long amount,

        @NotBlank(message = "Currency must not be blank") @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a valid 3-letter uppercase ISO 4217 code") String currency,

        @Size(max = 255, message = "Description must not exceed 255 characters") String description,

        @NotNull(message = "Initiated by user ID must not be null") UUID initiatedByUserId) {

    public DepositRequest {
        if (currency != null) {
            currency = currency.trim().toUpperCase();
        }
        if (description != null) {
            description = description.trim();
            if (description.isEmpty()) {
                description = null;
            }
        }
    }
}
