package io.github.ahmedmmordi.ledgerly.ledger.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record CreateAccountRequest(@NotNull(message = "User ID must not be null") UUID userId,

        @NotBlank(message = "Currency must not be blank") @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a valid 3-letter uppercase ISO 4217 code") String currency) {

    public CreateAccountRequest {
        if (currency != null) {
            currency = currency.trim().toUpperCase();
        }
    }
}
