package io.github.ahmedmmordi.ledgerly.identity.auth;

public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String email) {
        super("An account with that email address already exists");
    }
}
