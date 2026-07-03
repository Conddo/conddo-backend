package io.conddo.core.auth;

/** Verification token doesn't match any live invitation. Public — returned
 *  as a 400 by the controller. */
public class EmailVerificationInvalidException extends RuntimeException {
    public EmailVerificationInvalidException(String message) {
        super(message);
    }
}
