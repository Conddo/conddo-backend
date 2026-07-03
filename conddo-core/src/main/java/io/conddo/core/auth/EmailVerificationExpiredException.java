package io.conddo.core.auth;

/** Verification token was found but has passed its expiry. Distinguished
 *  from Invalid so the FE can offer a clean "send a new link" affordance. */
public class EmailVerificationExpiredException extends RuntimeException {
    public EmailVerificationExpiredException() {
        super("Verification link has expired");
    }
}
