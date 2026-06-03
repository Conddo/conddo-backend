package io.conddo.core.auth;

/**
 * No user matches the lookup (Google sub or email + tenant) — intentionally
 * single error code so we don't leak whether the email exists in a different
 * tenant. Mapped to 404 {@code USER_NOT_FOUND}.
 */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException() {
        super("No user found for the supplied credentials");
    }
}
