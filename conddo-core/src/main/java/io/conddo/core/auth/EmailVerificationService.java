package io.conddo.core.auth;

import io.conddo.core.audit.AuditActions;
import io.conddo.core.audit.AuditService;
import io.conddo.core.domain.User;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Post-onboarding email verification (Onboarding v2). Issues a single-use
 * link at tenant-creation time (or on resend), redeems it against the
 * user's {@code email_verification_token_hash} column, and flips the
 * account to verified.
 *
 * <p>Follows the {@link StaffInviteService} pattern: raw token in the URL,
 * SHA-256 hash on the row, cross-tenant lookup by hash. No selector split
 * because the hash column has a UNIQUE-safe partial index (V55).
 */
@Service
public class EmailVerificationService {

    /** Verification links stay valid for a week — long enough for an owner
     *  to find the email in a busy inbox but short enough that leaked links
     *  don't stay dangerous forever. Resending supersedes the prior link. */
    public static final Duration TOKEN_TTL = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final TenantSession tenantSession;
    private final AuditService auditService;
    private final Clock clock;
    private final SecureRandom rng = new SecureRandom();

    public EmailVerificationService(UserRepository userRepository,
                                    NotificationService notificationService,
                                    TenantSession tenantSession,
                                    AuditService auditService,
                                    Clock clock) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.tenantSession = tenantSession;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Issue a fresh verification link for {@code user} and deliver it by
     * email. Overwrites any outstanding link — a resend invalidates the
     * previous one. Callers hold the tenant context so the update passes RLS.
     *
     * <p>No-op if the user is already verified; the caller doesn't need to
     * guard against double-issue.
     */
    @Transactional
    public void issueLink(User user) {
        if (user == null || user.isEmailVerified()) {
            return;
        }
        String rawToken = generateToken();
        String tokenHash = sha256Hex(rawToken);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(TOKEN_TTL);
        user.issueEmailVerificationToken(tokenHash, expiresAt);
        userRepository.save(user);

        notificationService.sendEmailVerification(user.getEmail(), rawToken, user.getFullName());
        auditService.record(AuditActions.EMAIL_VERIFICATION_ISSUED, "USER", user.getId(),
                user.getTenantId(), user.getId(), null, null);
    }

    /**
     * Redeem a verification token. Public — the token itself is the
     * credential. Cross-tenant lookup; returns silently on already-verified
     * so a link clicked twice from an inbox doesn't feel like an error.
     */
    @Transactional
    public void verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new EmailVerificationInvalidException("Token is required");
        }
        tenantSession.bindCrossTenant();
        User user = userRepository.findByEmailVerificationTokenHashCrossTenant(sha256Hex(rawToken))
                .orElseThrow(() -> new EmailVerificationInvalidException("Verification link not found"));
        tenantSession.clearCrossTenant();

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (user.getEmailVerificationExpiresAt() == null
                || user.getEmailVerificationExpiresAt().isBefore(now)) {
            throw new EmailVerificationExpiredException();
        }

        user.markEmailVerified();
        userRepository.save(user);
        auditService.record(AuditActions.EMAIL_VERIFIED, "USER", user.getId(),
                user.getTenantId(), user.getId(), null, null);
    }

    /** Reissue a link for the logged-in user. Rate-limited on the controller. */
    @Transactional
    public void resendForUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EmailVerificationInvalidException("User not found"));
        issueLink(user);
    }

    // ----- internals --------------------------------------------------------

    private String generateToken() {
        byte[] bytes = new byte[32];
        rng.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashed = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
