package io.github.ahmedmmordi.ledgerly.identity.auth;

public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("Invalid or expired token");
    }
}
