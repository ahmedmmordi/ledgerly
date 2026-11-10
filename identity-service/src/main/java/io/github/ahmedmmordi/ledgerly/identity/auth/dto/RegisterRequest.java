package io.github.ahmedmmordi.ledgerly.identity.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email must not be blank") @Email(message = "Email must be a valid email address") String email,

        @NotBlank(message = "Password must not be blank") @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters") String password,

        @NotBlank(message = "First name must not be blank") @Size(max = 100, message = "First name must not exceed 100 characters") String firstName,

        @NotBlank(message = "Last name must not be blank") @Size(max = 100, message = "Last name must not exceed 100 characters") String lastName) {
}
