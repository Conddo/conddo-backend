package io.conddo.core.ai;

/**
 * Thrown by the AI gateway when a tenant-scoped call is attempted by a
 * user whose email has not been verified. The FE surfaces this as a
 * pointer to the amber verify banner rather than a raw error toast.
 */
public class EmailVerificationRequiredException extends RuntimeException {
    public EmailVerificationRequiredException() {
        super("Verify your email to use AI features. Check your inbox for the verification link.");
    }
}
