package io.github.ahmedmmordi.ledgerly.identity.user.dto;

import io.github.ahmedmmordi.ledgerly.identity.user.Role;
import io.github.ahmedmmordi.ledgerly.identity.user.User;
import java.util.UUID;

public record UserResponse(UUID id, String email, String firstName, String lastName, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.getRole());
    }
}
