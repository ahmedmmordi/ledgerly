package io.github.ahmedmmordi.ledgerly.identity.auth;

import java.util.UUID;

public record UserRegisteredEvent(UUID userId, String email) {
}
