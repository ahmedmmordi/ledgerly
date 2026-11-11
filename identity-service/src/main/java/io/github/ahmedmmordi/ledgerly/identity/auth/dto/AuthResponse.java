package io.github.ahmedmmordi.ledgerly.identity.auth.dto;

import io.github.ahmedmmordi.ledgerly.identity.user.Role;
import io.github.ahmedmmordi.ledgerly.identity.user.User;
import java.util.UUID;

public record AuthResponse(UUID id, String email, String firstName, String lastName, Role role, String token) {

    public static AuthResponse from(User user, String token) {
        return new AuthResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.getRole(),
                token);
    }
}
