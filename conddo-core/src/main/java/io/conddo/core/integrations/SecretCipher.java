package io.conddo.core.integrations;

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
 * AES-GCM at-rest encryption for connected-account credentials
 * (Moniepoint / OPay API keys). Same wire format as
 * {@code SocialTokenCipher}: {@code base64( versionByte || nonce(12) ||
 * ciphertext+tag )} with a 32-byte AES-256 envelope key.
 *
 * <p>The key comes from {@code CONDDO_INTEGRATIONS_ENCRYPTION_KEY}
 * (falls back to {@code CONDDO_SOCIAL_TOKEN_KEY} so a single generated
 * key covers both). When no key is configured the cipher runs in
 * pass-through mode ({@code "plain:"} prefix) for tests + local dev —
 * production boot must set the key.
 */
@Component
public class SecretCipher {

    private static final byte CURRENT_VERSION = 1;
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String PLAIN_PREFIX = "plain:";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(
            @Value("${conddo.integrations.encryption-key:${conddo.social.token-key:}}") String envKey) {
        if (envKey == null || envKey.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = decodeKey(envKey.trim());
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "conddo.integrations.encryption-key must decode to 32 bytes (AES-256); got "
                            + raw.length + " — generate with `openssl rand -base64 32`.");
        }
        this.key = new SecretKeySpec(raw, ALGORITHM);
    }

    /** Accept either base64 (44 chars) or hex (64 chars) key formats. */
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

    /** Encrypt plaintext → base64(version || nonce || ciphertext). Null-safe. */
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

    /** Decrypt base64(version || nonce || ciphertext) → plaintext. Null-safe. */
    public String decrypt(String envelope) {
        if (envelope == null) {
            return null;
        }
        if (envelope.startsWith(PLAIN_PREFIX)) {
            return envelope.substring(PLAIN_PREFIX.length());
        }
        if (key == null) {
            throw new IllegalStateException(
                    "conddo.integrations.encryption-key not configured — cannot decrypt stored credentials");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(envelope);
            if (raw[0] != CURRENT_VERSION) {
                throw new IllegalStateException("Unknown SecretCipher version " + raw[0]);
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
