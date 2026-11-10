package io.github.ahmedmmordi.ledgerly.identity.auth.dto;

import io.github.ahmedmmordi.ledgerly.identity.user.Role;
import io.github.ahmedmmordi.ledgerly.identity.user.User;
import java.util.UUID;

public record AuthResponse(UUID id, String email, String firstName, String lastName, Role role) {

    public static AuthResponse from(User user) {
        return new AuthResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.getRole());
    }
}
