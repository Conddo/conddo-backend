package io.conddo.core.auth;

/**
 * The fields a verified Google ID token exposes — only those Conddo needs.
 * {@code sub} is Google's immutable subject id (used as {@code google_sub} on
 * the user row); {@code email} + {@code emailVerified} drive first-time linking;
 * {@code name} populates the new user's {@code full_name} during signup.
 *
 * @param sub Google's immutable subject id for the user
 * @param email Last-seen Google email (may change over time)
 * @param emailVerified Whether Google has verified the email — must be {@code true} to link/sign in
 * @param name Google profile display name (may be null/blank — fall back to email at the caller)
 */
public record GoogleIdentity(String sub, String email, boolean emailVerified, String name) {
}
