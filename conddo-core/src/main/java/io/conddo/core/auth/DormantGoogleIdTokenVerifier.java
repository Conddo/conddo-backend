package io.conddo.core.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Default {@link GoogleIdTokenVerifier} bean — used when no Google OAuth client
 * id is configured. Every verify call returns {@link Optional#empty()} and the
 * service surfaces a clean 503 / 400 to the caller. Lets the platform boot in
 * environments that don't have Google credentials yet.
 *
 * <p>The real {@link GoogleApiClientVerifier} is {@code @Primary} when
 * {@code conddo.security.oauth.google.client-id} is set, replacing this bean
 * transparently.
 */
@Component
public class DormantGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(DormantGoogleIdTokenVerifier.class);

    public DormantGoogleIdTokenVerifier() {
        log.info("Google ID-token verifier is dormant (no conddo.security.oauth.google.client-id set)");
    }

    @Override
    public Optional<GoogleIdentity> verify(String idToken) {
        return Optional.empty();
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
