package io.conddo.core.social;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM at-rest encryption for the Ayrshare {@code profileKey}
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §2). Envelope key (32 bytes,
 * base64-encoded) lives in the {@code CONDDO_SOCIAL_TOKEN_KEY} env var.
 *
 * <p>Wire format: {@code base64( versionByte || nonce(12) || ciphertext+tag )}.
 * The version byte lets us rotate keys later by accepting the old version
 * during a wind-down window.
 *
 * <p>When the env var is missing the cipher operates in pass-through mode
 * with a {@code "plain:"} prefix — useful for tests + local dev. Production
 * boot must set the key; {@link #isConfigured()} surfaces the state.
 */
@Component
public class SocialTokenCipher {

    private static final byte CURRENT_VERSION = 1;
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String PLAIN_PREFIX = "plain:";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SocialTokenCipher(@Value("${conddo.social.token-key:}") String envKey) {
        if (envKey == null || envKey.isBlank()) {
            this.key = null;
            return;
        }
        // Ops generate this with either `openssl rand -base64 32` (44 chars,
        // ends with `=`) or `openssl rand -hex 32` (64 chars, [0-9a-f] only).
        // Both produce 32 bytes — accept either format so the deploy doesn't
        // hinge on remembering which flag was used.
        byte[] raw = decodeKey(envKey.trim());
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "conddo.social.token-key must decode to 32 bytes (AES-256); got " + raw.length
                            + " — generate with `openssl rand -base64 32` or `openssl rand -hex 32`.");
        }
        this.key = new SecretKeySpec(raw, ALGORITHM);
    }

    /** Try hex first (unambiguous 64-char [0-9a-f] pattern), then base64. */
    private static byte[] decodeKey(String raw) {
        if (raw.length() == 64 && raw.matches("[0-9a-fA-F]+")) {
            try {
                return java.util.HexFormat.of().parseHex(raw);
            } catch (IllegalArgumentException ignored) {
                // fall through to base64
            }
        }
        return Base64.getDecoder().decode(raw);
    }

    public boolean isConfigured() {
        return key != null;
    }

    /** Encrypt plaintext → base64(version || nonce || ciphertext). */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        if (key == null) {
            return PLAIN_PREFIX + plaintext;   // pass-through for tests / local dev
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(1 + nonce.length + ct.length);
            buf.put(CURRENT_VERSION).put(nonce).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM encrypt failed", ex);
        }
    }

    /** Decrypt base64(version || nonce || ciphertext) → plaintext. */
    public String decrypt(String envelope) {
        if (envelope == null) {
            return null;
        }
        if (envelope.startsWith(PLAIN_PREFIX)) {
            return envelope.substring(PLAIN_PREFIX.length());
        }
        if (key == null) {
            throw new IllegalStateException(
                    "conddo.social.token-key not configured — cannot decrypt stored profileKey");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(envelope);
            byte version = raw[0];
            if (version != CURRENT_VERSION) {
                throw new IllegalStateException("Unknown SocialTokenCipher version " + version);
            }
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(raw, 1, nonce, 0, NONCE_BYTES);
            byte[] ct = new byte[raw.length - 1 - NONCE_BYTES];
            System.arraycopy(raw, 1 + NONCE_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM decrypt failed", ex);
        }
    }
}
