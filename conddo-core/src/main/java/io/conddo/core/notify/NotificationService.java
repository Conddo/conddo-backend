package io.conddo.core.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The central notifications engine (PRD §6.4): the single entry point modules
 * use to notify users. It owns message content and routes to the right channel
 * ({@link SmsSender} / {@link EmailSender}), each of which is a stub or a real
 * provider depending on configuration. Emails render the branded HTML templates
 * ({@link EmailTemplates}) with a plain-text fallback.
 *
 * <p>Delivery is synchronous for now; when the Redis event bus lands (item 6)
 * this becomes the place that consumes notification events asynchronously,
 * without changing callers.
 */
@Service
public class NotificationService {

    private final SmsSender smsSender;
    private final EmailSender emailSender;
    private final EmailTemplates templates;
    private final String appBaseUrl;
    private final String otpExpiryMinutes;

    public NotificationService(SmsSender smsSender, EmailSender emailSender, EmailTemplates templates,
                               @Value("${conddo.app.base-url:https://app.conddo.io}") String appBaseUrl,
                               @Value("${conddo.security.otp.ttl:10m}") String otpTtl) {
        this.smsSender = smsSender;
        this.emailSender = emailSender;
        this.templates = templates;
        this.appBaseUrl = appBaseUrl;
        this.otpExpiryMinutes = otpTtl.replaceAll("[^0-9]", "").isBlank() ? "10" : otpTtl.replaceAll("[^0-9]", "");
    }

    /** Signup verification code by SMS (needs a funded SMS provider, e.g. Brevo credits). */
    public void sendOtp(String phone, String code) {
        smsSender.send(phone, "Your Conddo verification code is " + code);
    }

    /** Signup verification code by email — the branded template with a text fallback. */
    public void sendOtpEmail(String toEmail, String code) {
        String subject = "Your Conddo verification code";
        String text = "Your Conddo verification code is " + code + ". It expires in "
                + otpExpiryMinutes + " minutes. If you didn't request this, you can ignore this email.";
        String html = templates.render("verification-code.html",
                Map.of("CODE", code, "EXPIRY_MINUTES", otpExpiryMinutes));
        if (html.isBlank()) {
            emailSender.send(toEmail, subject, text);
        } else {
            emailSender.sendHtml(toEmail, subject, html, text);
        }
    }

    /**
     * Alerts a merchant that a new order landed on their public website
     * (WEBSITE_INTEGRATION_SPEC §3 / merchant-readiness slice 2). Both
     * channels are best-effort and independent — one provider failing
     * never blocks the other, and never bubbles to the caller (the
     * checkout response has already gone back to the customer).
     */
    public void sendOrderAlert(String toEmail, String toPhone, String businessName,
                               String customerName, String orderReference, String totalNgn) {
        String subject = "New order on your conddo.io site";
        String text = "Hi " + nullSafe(businessName) + ",\n\n"
                + nullSafe(customerName) + " just placed order " + nullSafe(orderReference)
                + " for ₦" + nullSafe(totalNgn) + " on your conddo.io website.\n\n"
                + "View it on your dashboard: " + appBaseUrl + "/orders/" + nullSafe(orderReference) + "\n\n"
                + "— Conddo";
        if (toEmail != null && !toEmail.isBlank()) {
            try {
                emailSender.send(toEmail, subject, text);
            } catch (RuntimeException ignored) {
                // Provider blip — SMS is the fallback channel; swallow.
            }
        }
        if (toPhone != null && !toPhone.isBlank()) {
            String sms = "New conddo.io order " + nullSafe(orderReference)
                    + " — ₦" + nullSafe(totalNgn) + " from " + nullSafe(customerName);
            try {
                smsSender.send(toPhone, sms);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Password reset — delivers the reset token (and a reset link) by email. */
    public void sendPasswordReset(String toEmail, String resetToken) {
        String subject = "Reset your Conddo password";
        String resetUrl = appBaseUrl + "/reset-password?token=" + resetToken;
        String text = "Reset your Conddo password with this link: " + resetUrl
                + "\n\nOr paste this code on the reset page: " + resetToken
                + "\n\nIf you didn't request this, you can safely ignore this email.";
        String html = templates.render("password-reset.html",
                Map.of("RESET_URL", resetUrl, "RESET_TOKEN", resetToken, "EXPIRY_MINUTES", "60"));
        if (html.isBlank()) {
            emailSender.send(toEmail, subject, text);
        } else {
            emailSender.sendHtml(toEmail, subject, html, text);
        }
    }
}
