package io.conddo.core.auth;

/**
 * The Google ID token verified, but {@code email_verified} was false — we won't
 * link an unverified Google identity to a user. Mapped to 400
 * {@code GOOGLE_EMAIL_UNVERIFIED}.
 */
public class GoogleEmailUnverifiedException extends RuntimeException {
    public GoogleEmailUnverifiedException() {
        super("Google has not verified the email on this account");
    }
}
