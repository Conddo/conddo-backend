package io.conddo.core.notify;

/**
 * Outbound notifications seam (PRD §6.4). Phase 1 item 1 only needs password-
 * reset delivery; the full notifications engine (Resend email / Termii SMS,
 * event-driven) is item 5 and will provide the real implementation behind this
 * port. Keeping it an interface lets auth depend on the capability, not the
 * channel.
 */
public interface NotificationPort {

    /** Delivers a password-reset token to the user out-of-band (email link). */
    void sendPasswordReset(String toEmail, String resetToken);
}
