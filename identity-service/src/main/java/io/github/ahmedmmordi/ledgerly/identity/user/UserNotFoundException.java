package io.github.ahmedmmordi.ledgerly.identity.user;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID userId) {
        super("User not found");
    }
}
