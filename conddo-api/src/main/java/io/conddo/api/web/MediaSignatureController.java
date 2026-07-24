package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeMap;

/**
 * Issues signed parameters that let the FE upload files DIRECTLY to
 * Cloudinary — bypassing our backend + Caddy + Cloudflare entirely for
 * the file bytes. Only the signing round-trip (a few hundred bytes)
 * touches Conddo.
 *
 * <p>Why direct upload:
 * <ul>
 *   <li>Multipart POSTs through the whole proxy chain were 502'ing on
 *       browsers — a debugging quagmire between Cloudflare, Caddy, and
 *       Spring's multipart parser.</li>
 *   <li>Cloudinary already allows browser CORS uploads on its own domain.</li>
 *   <li>Larger files stop being a Caddy / body-size concern.</li>
 * </ul>
 *
 * <p>The signature is time-bound (Cloudinary rejects signatures &gt; 1h old)
 * and folder-scoped so a KYC upload can't be reused to overwrite a
 * tenant's logo.
 */
@RestController
@RequestMapping("/api/v1/media/upload-signature")
public class MediaSignatureController {

    private static final Set<String> ALLOWED_FOLDERS = Set.of(
            "kyc", "logo", "brand", "website", "product", "media", "social", "creative");

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final boolean enabled;

    public MediaSignatureController(
            // Read either name — the existing .env uses the un-prefixed
            // CLOUDINARY_URL (the Cloudinary SDK reads it directly), so
            // we accept both for compatibility with the current deploy.
            @Value("${conddo.cloudinary.url:${cloudinary.url:}}") String cloudinaryUrl) {
        String parsed = stripQuotes(cloudinaryUrl);
        // CLOUDINARY_URL shape: cloudinary://<key>:<secret>@<cloud>
        String cloud = null, key = null, secret = null;
        if (parsed != null && parsed.startsWith("cloudinary://")) {
            try {
                String rest = parsed.substring("cloudinary://".length());
                int at = rest.indexOf('@');
                if (at > 0) {
                    String creds = rest.substring(0, at);
                    cloud = rest.substring(at + 1);
                    int colon = creds.indexOf(':');
                    if (colon > 0) {
                        key = creds.substring(0, colon);
                        secret = creds.substring(colon + 1);
                    }
                }
            } catch (RuntimeException ignored) {
                // Fall through to disabled.
            }
        }
        this.cloudName = cloud;
        this.apiKey = key;
        this.apiSecret = secret;
        this.enabled = cloud != null && key != null && secret != null;
    }

    /** Docker Compose sometimes preserves surrounding double quotes from
     *  {@code .env} lines like {@code KEY="value"}. Strip them so the URL
     *  parser doesn't treat {@code "cloudinary://...} as a bad scheme. */
    private static String stripQuotes(String v) {
        if (v == null) return null;
        String t = v.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            if (t.length() >= 2) t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    /**
     * GET /api/v1/media/upload-signature?folder=kyc
     *
     * <p>Returns the signed params the browser needs to POST directly to
     * {@code https://api.cloudinary.com/v1_1/{cloudName}/auto/upload}.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
    public ApiResponse<SignatureResponse> sign(
            @RequestParam(defaultValue = "media") String folder) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Cloudinary is not configured on the server");
        }
        if (!ALLOWED_FOLDERS.contains(folder)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown upload folder: " + folder);
        }
        long timestamp = System.currentTimeMillis() / 1000L;

        // Cloudinary signature spec: SHA-1 of the sorted param string +
        // API secret. Only signed params below participate; anything else
        // the client tacks on Cloudinary ignores (or rejects).
        TreeMap<String, String> params = new TreeMap<>();
        params.put("folder", folder);
        params.put("timestamp", String.valueOf(timestamp));

        String signature = sign(params);

        return ApiResponse.ok(new SignatureResponse(
                cloudName, apiKey, timestamp, signature, folder));
    }

    private String sign(TreeMap<String, String> params) {
        StringBuilder toSign = new StringBuilder();
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) toSign.append('&');
            toSign.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        toSign.append(apiSecret);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(toSign.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not sign upload params");
        }
    }

    public record SignatureResponse(
            String cloudName,
            String apiKey,
            long timestamp,
            String signature,
            String folder
    ) {}
}
