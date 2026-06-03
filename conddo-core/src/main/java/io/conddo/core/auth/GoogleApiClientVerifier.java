package io.conddo.core.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Optional;

/**
 * Real Google ID-token verifier. Active only when
 * {@code conddo.security.oauth.google.client-id} is set in the environment,
 * replacing the {@link DormantGoogleIdTokenVerifier} as {@code @Primary}.
 *
 * <p>Wraps Google's {@link com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier},
 * which performs the four checks Google's guidance prescribes for sign-in:
 * <ol>
 *   <li>Signature against Google's JWKS (cached internally per Google's TTLs)</li>
 *   <li>Audience equals our configured client id</li>
 *   <li>Issuer is {@code accounts.google.com} or {@code https://accounts.google.com}</li>
 *   <li>Expiry not past</li>
 * </ol>
 * Any failure (bad signature, wrong aud, expired, malformed) returns
 * {@link Optional#empty()} — never thrown. The calling service maps that to
 * {@link GoogleIdTokenInvalidException} (a 400).
 */
@Component
@Primary
@ConditionalOnExpression("'${conddo.security.oauth.google.client-id:}' != ''")
public class GoogleApiClientVerifier implements GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleApiClientVerifier.class);

    private final com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier delegate;
    private final String clientId;

    public GoogleApiClientVerifier(
            @org.springframework.beans.factory.annotation.Value("${conddo.security.oauth.google.client-id}")
            String clientId) {
        this.clientId = clientId;
        this.delegate = new com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
        log.info("Google ID-token verifier active (clientId={}…)",
                clientId.length() > 12 ? clientId.substring(0, 12) : clientId);
    }

    @Override
    public Optional<GoogleIdentity> verify(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            return Optional.empty();
        }
        try {
            GoogleIdToken token = delegate.verify(idToken);
            if (token == null) {
                return Optional.empty();
            }
            Payload payload = token.getPayload();
            String name = (String) payload.get("name");
            if (name == null || name.isBlank()) {
                String given = (String) payload.get("given_name");
                String family = (String) payload.get("family_name");
                if (given != null || family != null) {
                    name = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
                }
            }
            return Optional.of(new GoogleIdentity(
                    payload.getSubject(),
                    payload.getEmail(),
                    Boolean.TRUE.equals(payload.getEmailVerified()),
                    name));
        } catch (GeneralSecurityException | java.io.IOException ex) {
            // Per §20 AI rules pattern — fail-safe, never throw out of the port.
            log.debug("Google ID-token verify failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank();
    }
}
