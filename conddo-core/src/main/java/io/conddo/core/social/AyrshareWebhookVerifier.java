package io.conddo.core.social;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * HMAC-SHA256 verifier for incoming Ayrshare webhooks (§2). The shared
 * secret lives in {@code AYRSHARE_WEBHOOK_SECRET}; Ayrshare sends the hex
 * MAC of the raw request body in a header (typically
 * {@code X-Ayrshare-Signature}). Constant-time compare prevents timing
 * attacks.
 *
 * <p>When the secret isn't configured ({@link #isConfigured()} returns
 * {@code false}), the verifier returns {@code true} only in local-dev
 * pass-through mode — the controller checks first and rejects anything
 * not from configured Ayrshare in production.
 */
@Component
public class AyrshareWebhookVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final byte[] secret;

    public AyrshareWebhookVerifier(@Value("${conddo.social.ayrshare-webhook-secret:}") String secret) {
        this.secret = (secret == null || secret.isBlank())
                ? null
                : secret.trim().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isConfigured() {
        return secret != null;
    }

    /**
     * Verify {@code header} against {@code rawBody} using HMAC-SHA256.
     * Header is treated as hex; an empty header always fails.
     */
    public boolean verify(byte[] rawBody, String headerSignature) {
        if (!isConfigured()) {
            return false;
        }
        if (headerSignature == null || headerSignature.isBlank() || rawBody == null) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            expected = mac.doFinal(rawBody);
        } catch (Exception ex) {
            return false;
        }
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(headerSignature.trim().toLowerCase());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return java.security.MessageDigest.isEqual(expected, supplied);
    }
}
