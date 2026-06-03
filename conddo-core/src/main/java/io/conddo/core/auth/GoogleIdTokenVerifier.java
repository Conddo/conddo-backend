package io.conddo.core.auth;

import java.util.Optional;

/**
 * Port for verifying a Google ID token (Slice H / ACTION_LIST §1a). The real
 * adapter checks the token's signature against Google's JWKS, verifies the
 * audience against our OAuth client id, the issuer is {@code accounts.google.com},
 * and the expiry hasn't passed — returning a {@link GoogleIdentity}.
 *
 * <p>Verification failures (bad signature, wrong audience, expired) return
 * {@link Optional#empty()}; the caller surfaces them as
 * {@link GoogleIdTokenInvalidException}. Tests mock this interface — no real
 * Google traffic.
 */
public interface GoogleIdTokenVerifier {

    /** Verify a raw ID-token string. {@link Optional#empty()} on any verification failure. */
    Optional<GoogleIdentity> verify(String idToken);

    /** Whether the verifier has a Google OAuth client id configured — false → /auth/google 503s. */
    boolean isConfigured();
}
