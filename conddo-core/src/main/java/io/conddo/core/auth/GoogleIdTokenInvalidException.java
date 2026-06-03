package io.conddo.core.auth;

/**
 * The Google ID token failed verification: bad signature, wrong audience,
 * expired, malformed, or the verifier isn't configured. Mapped to 400
 * {@code GOOGLE_ID_TOKEN_INVALID} by the global exception handler.
 */
public class GoogleIdTokenInvalidException extends RuntimeException {
    public GoogleIdTokenInvalidException() {
        super("Google ID token could not be verified");
    }
}
