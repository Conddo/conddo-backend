package io.conddo.core.auth;

/**
 * Signup attempted with an email that already belongs to a platform user.
 * Mapped to 409 {@code USER_ALREADY_EXISTS}.
 */
public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException() {
        super("A user with that email already exists");
    }
}
