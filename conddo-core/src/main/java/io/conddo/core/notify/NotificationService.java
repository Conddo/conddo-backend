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
    private final String platformAdminEmail;

    public NotificationService(SmsSender smsSender, EmailSender emailSender, EmailTemplates templates,
                               @Value("${conddo.app.base-url:https://app.conddo.io}") String appBaseUrl,
                               @Value("${conddo.security.otp.ttl:10m}") String otpTtl,
                               @Value("${conddo.notify.platform-admin-email:}") String platformAdminEmail) {
        this.smsSender = smsSender;
        this.emailSender = emailSender;
        this.templates = templates;
        this.appBaseUrl = appBaseUrl;
        this.otpExpiryMinutes = otpTtl.replaceAll("[^0-9]", "").isBlank() ? "10" : otpTtl.replaceAll("[^0-9]", "");
        this.platformAdminEmail = platformAdminEmail;
    }

    /**
     * Notifies the platform admin (Conddo staff mailbox) that a new tenant
     * just signed up. No-op when {@code conddo.notify.platform-admin-email}
     * is unset. Fires from the {@code TenantActivatedEvent} after-commit
     * listener so it never blocks signup.
     */
    public void sendPlatformSignupAlert(String businessName, String vertical, String planId,
                                         String ownerEmail, String ownerFullName,
                                         String tenantSlug) {
        if (platformAdminEmail == null || platformAdminEmail.isBlank()) {
            return;
        }
        String subject = "New Conddo signup: " + safe(businessName);
        String workspaceUrl = safe(tenantSlug) + ".getconddo.com";
        String text = "A new tenant just signed up on Conddo.\n\n"
                + "  Business:      " + safe(businessName) + "\n"
                + "  Vertical:      " + safe(vertical) + "\n"
                + "  Plan:          " + safe(planId) + "\n"
                + "  Owner:         " + safe(ownerFullName) + " <" + safe(ownerEmail) + ">\n"
                + "  Workspace URL: " + workspaceUrl + "\n\n"
                + "Review in the admin dashboard: " + appBaseUrl.replace("app.", "studio.")
                + "/admin/tenants\n";
        String html = templates.render("platform-signup-alert.html", Map.of(
                "BUSINESS_NAME",  safe(businessName),
                "VERTICAL",       safe(vertical),
                "PLAN",           safe(planId),
                "OWNER_NAME",     safe(ownerFullName),
                "OWNER_EMAIL",    safe(ownerEmail),
                "WORKSPACE_URL",  workspaceUrl,
                "ADMIN_URL",      appBaseUrl.replace("app.", "studio.") + "/admin/tenants"));
        if (html.isBlank()) {
            emailSender.send(platformAdminEmail, subject, text);
        } else {
            emailSender.sendHtml(platformAdminEmail, subject, html, text);
        }
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
        String dashboardUrl = appBaseUrl + "/orders/" + nullSafe(orderReference);
        String text = "Hi " + nullSafe(businessName) + ",\n\n"
                + nullSafe(customerName) + " just placed order " + nullSafe(orderReference)
                + " for ₦" + nullSafe(totalNgn) + " on your conddo.io website.\n\n"
                + "View it on your dashboard: " + dashboardUrl + "\n\n"
                + "— Conddo";
        String html = templates.render("order-alert.html", Map.of(
                "BUSINESS_NAME",   nullSafe(businessName),
                "CUSTOMER_NAME",   nullSafe(customerName),
                "ORDER_REFERENCE", nullSafe(orderReference),
                "TOTAL_NGN",       nullSafe(totalNgn),
                "DASHBOARD_URL",   dashboardUrl));
        if (toEmail != null && !toEmail.isBlank()) {
            try {
                if (html.isBlank()) {
                    emailSender.send(toEmail, subject, text);
                } else {
                    emailSender.sendHtml(toEmail, subject, html, text);
                }
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

    /**
     * Booking parity to {@link #sendOrderAlert} — fired by the
     * BookingNotificationListener when a customer self-books on the
     * merchant's public booking link. Both channels best-effort.
     */
    public void sendBookingAlert(String toEmail, String toPhone, String businessName,
                                 String customerName, String service, String when,
                                 String contactPhone) {
        String subject = "New booking request on your conddo.io site";
        String dashboardUrl = appBaseUrl + "/bookings";
        String text = "Hi " + nullSafe(businessName) + ",\n\n"
                + nullSafe(customerName) + " just requested a booking"
                + (service == null || service.isBlank() ? "" : " for " + service)
                + (when == null || when.isBlank() ? "" : " at " + when)
                + (contactPhone == null || contactPhone.isBlank() ? "" : " (contact: " + contactPhone + ")")
                + ".\n\nReview it on your dashboard: " + dashboardUrl + "\n\n"
                + "— Conddo";
        String html = templates.render("booking-alert.html", Map.of(
                "BUSINESS_NAME", nullSafe(businessName),
                "CUSTOMER_NAME", nullSafe(customerName),
                "SERVICE",       service == null || service.isBlank() ? "an appointment" : service,
                "STARTS_AT",     when == null || when.isBlank() ? "the requested time" : when,
                "CONTACT_PHONE", contactPhone == null || contactPhone.isBlank() ? "not provided" : contactPhone,
                "DASHBOARD_URL", dashboardUrl));
        if (toEmail != null && !toEmail.isBlank()) {
            try {
                if (html.isBlank()) {
                    emailSender.send(toEmail, subject, text);
                } else {
                    emailSender.sendHtml(toEmail, subject, html, text);
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (toPhone != null && !toPhone.isBlank()) {
            String sms = "New booking on conddo.io — " + nullSafe(customerName)
                    + (service == null || service.isBlank() ? "" : " — " + service)
                    + (when == null || when.isBlank() ? "" : " — " + when);
            try {
                smsSender.send(toPhone, sms);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Alerts a merchant when their subscription transitions
     * (BILLING_TIERS_SPEC §6). Two transitions are merchant-visible:
     * {@code trialing/active → grace} (kindly: "your trial ended, here's a
     * grace window to add payment") and {@code grace → expired} (sharper:
     * "your access is paused; reactivate to resume"). Both channels
     * best-effort; the cron has already committed the state change.
     */
    public void sendPlanTransition(String toEmail, String toPhone, String businessName,
                                   String planName, String toStatus, int gracePeriodDays) {
        if ((toEmail == null || toEmail.isBlank()) && (toPhone == null || toPhone.isBlank())) {
            return;
        }
        String subject;
        String text;
        String sms;
        String eyebrow;
        String headline;
        String body;
        String preheader;
        // Amber for grace (still recoverable), rose for expired (paused).
        String accent;
        String biz = nullSafe(businessName);
        String plan = nullSafe(planName);
        String billingUrl = appBaseUrl + "/settings/billing";
        switch (toStatus) {
            case "grace" -> {
                subject = "Your conddo.io trial just ended";
                eyebrow = "Trial ended";
                headline = "You're in your grace period";
                body = "Your " + plan + " trial just ended for <strong>" + biz
                        + "</strong>. You have " + gracePeriodDays
                        + " days to add a payment method before access pauses.";
                preheader = "Your " + plan + " trial ended. Add payment in the next "
                        + gracePeriodDays + " days to keep going.";
                accent = "#B45309"; // amber-700
                text = "Hi " + biz + ",\n\n"
                        + "Your " + plan + " trial just ended. You're in a "
                        + gracePeriodDays + "-day grace period — add a payment method "
                        + "to keep your conddo.io features running.\n\n"
                        + "Add payment: " + billingUrl + "\n\n"
                        + "— Conddo";
                sms = "Your conddo.io " + plan + " trial ended. " + gracePeriodDays
                        + "-day grace period — add payment at " + billingUrl;
            }
            case "expired" -> {
                subject = "Your conddo.io subscription has expired";
                eyebrow = "Subscription paused";
                headline = "Reactivate to restore access";
                body = "Your " + plan + " subscription has expired and <strong>" + biz
                        + "</strong>'s Conddo site is paused. Reactivate to restore access "
                        + "to your customers.";
                preheader = "Your " + plan
                        + " subscription expired. Reactivate to bring your site back.";
                accent = "#BE123C"; // rose-700
                text = "Hi " + biz + ",\n\n"
                        + "Your " + plan + " subscription has expired and your conddo.io "
                        + "site is paused. Reactivate to restore access to your customers.\n\n"
                        + "Reactivate: " + billingUrl + "\n\n"
                        + "— Conddo";
                sms = "Your conddo.io " + plan + " subscription expired. Reactivate at "
                        + billingUrl;
            }
            default -> {
                // Other states (cancelled-completion etc.) stay silent.
                return;
            }
        }
        String html = templates.render("plan-transition.html", Map.of(
                "EYEBROW",      eyebrow,
                "HEADLINE",     headline,
                "BODY",         body,
                "PREHEADER",    preheader,
                "ACCENT_COLOR", accent,
                "BILLING_URL",  billingUrl));
        if (toEmail != null && !toEmail.isBlank()) {
            try {
                if (html.isBlank()) {
                    emailSender.send(toEmail, subject, text);
                } else {
                    emailSender.sendHtml(toEmail, subject, html, text);
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (toPhone != null && !toPhone.isBlank()) {
            try {
                smsSender.send(toPhone, sms);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Post-onboarding email verification — delivers the tokenized verify link.
     *  The FE lands on /verify-email?token=…, calls GET /auth/verify-email, and
     *  flips the user's email_verified flag. */
    public void sendEmailVerification(String toEmail, String verifyToken, String displayName) {
        String subject = "Verify your Conddo account";
        String verifyUrl = appBaseUrl + "/verify-email?token=" + verifyToken;
        String friendly = (displayName != null && !displayName.isBlank()) ? displayName : "there";
        String text = "Hi " + friendly + ",\n\n"
                + "Welcome to Conddo. Verify your email to unlock publishing, payments, and automations:\n"
                + verifyUrl + "\n\n"
                + "This link expires in 7 days. If you didn't sign up, you can safely ignore this email.";
        String html = templates.render("email-verification.html",
                Map.of("VERIFY_URL", verifyUrl, "NAME", friendly, "EXPIRY_DAYS", "7"));
        if (html.isBlank()) {
            emailSender.send(toEmail, subject, text);
        } else {
            emailSender.sendHtml(toEmail, subject, html, text);
        }
    }

    /**
     * Tenant-invite email — sent by the admin dashboard when a Conddo staff
     * member provisions a workspace on behalf of a customer. Guides the
     * owner to set their password, then add brand, then publish. Best-effort:
     * a delivery failure is logged inside the sender but never thrown.
     */
    public void sendTenantInvite(String toEmail, String firstName, String businessName,
                                  String tenantSlug, String inviteUrl, int expiryDays) {
        String subject = "Welcome to Conddo — " + businessName + " is ready";
        String workspaceUrl = tenantSlug + ".getconddo.com";
        String text = "Hi " + safe(firstName) + ",\n\n"
                + "We've created a Conddo workspace for " + businessName + ".\n\n"
                + "Your sign-in details:\n"
                + "  Workspace:     " + workspaceUrl + "\n"
                + "  Sign-in email: " + safe(toEmail) + "\n\n"
                + "Set your password to get started: " + inviteUrl + "\n\n"
                + "Your first three steps:\n"
                + "  1. Set your password using the link above\n"
                + "  2. Add your logo and brand colours (30 seconds)\n"
                + "  3. Publish your site — already live at " + workspaceUrl + "\n\n"
                + "This invite link expires in " + expiryDays + " days.\n";
        String html = templates.render("tenant-invite.html", Map.of(
                "FIRST_NAME",    safe(firstName),
                "BUSINESS_NAME", safe(businessName),
                "TENANT_SLUG",   safe(tenantSlug),
                "OWNER_EMAIL",   safe(toEmail),
                "ACCEPT_URL",    safe(inviteUrl),
                "EXPIRY_DAYS",   String.valueOf(expiryDays)));
        if (html.isBlank()) {
            emailSender.send(toEmail, subject, text);
        } else {
            emailSender.sendHtml(toEmail, subject, html, text);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Customer-facing booking confirmation. Fires on public self-book when
     * the customer supplied an email address. Kept brand-neutral (Conddo
     * chrome only, not the tenant's) — the tenant's own site renderer
     * carries their brand, but this is a transactional receipt whose
     * primary job is legibility and trust.
     */
    public void sendBookingConfirmation(String toEmail, String customerName,
                                         String businessName, String service,
                                         String whenStr, String tenantPhone,
                                         String tenantEmail) {
        String subject = "Booking confirmed — " + safe(businessName);
        String text = "Hi " + safe(customerName) + ",\n\n"
                + "Your booking with " + safe(businessName) + " is confirmed.\n\n"
                + "  Service: " + safe(service) + "\n"
                + "  When:    " + safe(whenStr) + "\n\n"
                + "Need to reach " + safe(businessName) + "?\n"
                + (tenantPhone == null || tenantPhone.isBlank() ? ""
                        : "  Phone: " + tenantPhone + "\n")
                + (tenantEmail == null || tenantEmail.isBlank() ? ""
                        : "  Email: " + tenantEmail + "\n")
                + "\nThanks for using Conddo.";
        String html = templates.render("booking-confirmation.html", Map.of(
                "CUSTOMER_NAME", safe(customerName),
                "BUSINESS_NAME", safe(businessName),
                "SERVICE",       safe(service),
                "WHEN_STR",      safe(whenStr),
                "TENANT_PHONE",  safe(tenantPhone),
                "TENANT_EMAIL",  safe(tenantEmail)));
        if (html.isBlank()) {
            emailSender.send(toEmail, subject, text);
        } else {
            emailSender.sendHtml(toEmail, subject, html, text);
        }
    }

    /**
     * Send an invoice link to the customer. The email is intentionally
     * short — one CTA button that opens the branded public page
     * ({@code /i/{token}}) where the full document lives. Anything richer
     * (line-item breakdown, PDF attachment) would duplicate what the
     * link already shows and bloat the message size.
     */
    public void sendInvoiceEmail(String toEmail, String customerName,
                                  String businessName, String invoiceNumber,
                                  String totalDisplay, String dueDateDisplay,
                                  String publicUrl, String notes,
                                  String tenantPhone, String tenantEmail) {
        String subject = "Invoice " + safe(invoiceNumber) + " — " + safe(businessName);
        String text = "Hi " + safe(customerName) + ",\n\n"
                + safe(businessName) + " sent you invoice " + safe(invoiceNumber) + ".\n\n"
                + "  Amount:   " + safe(totalDisplay) + "\n"
                + (dueDateDisplay == null || dueDateDisplay.isBlank() ? ""
                        : "  Due:      " + dueDateDisplay + "\n")
                + "\nView + pay online: " + publicUrl + "\n"
                + (notes == null || notes.isBlank() ? "" : "\n" + notes + "\n")
                + "\nNeed to reach " + safe(businessName) + "?\n"
                + (tenantPhone == null || tenantPhone.isBlank() ? ""
                        : "  Phone: " + tenantPhone + "\n")
                + (tenantEmail == null || tenantEmail.isBlank() ? ""
                        : "  Email: " + tenantEmail + "\n")
                + "\nSent via Conddo.";
        String html = templates.render("invoice-share.html", Map.of(
                "CUSTOMER_NAME",  safe(customerName),
                "BUSINESS_NAME",  safe(businessName),
                "INVOICE_NUMBER", safe(invoiceNumber),
                "TOTAL_DISPLAY",  safe(totalDisplay),
                "DUE_DATE",       safe(dueDateDisplay),
                "PUBLIC_URL",     safe(publicUrl),
                "NOTES",          safe(notes),
                "TENANT_PHONE",   safe(tenantPhone),
                "TENANT_EMAIL",   safe(tenantEmail)));
        if (html.isBlank()) {
            emailSender.send(toEmail, subject, text);
        } else {
            emailSender.sendHtml(toEmail, subject, html, text);
        }
    }

    /**
     * Receipt email — fires automatically when an invoice flips to
     * {@code paid} (manual mark or gateway webhook). Different template
     * + green PAID stamp instead of the invoice's amber pending block.
     */
    public void sendInvoiceReceiptEmail(String toEmail, String customerName,
                                         String businessName, String invoiceNumber,
                                         String totalDisplay, String paidDateDisplay,
                                         String paidMethod, String publicUrl,
                                         String tenantPhone, String tenantEmail) {
        String subject = "Receipt " + safe(invoiceNumber) + " — " + safe(businessName);
        String text = "Hi " + safe(customerName) + ",\n\n"
                + "Thanks — we received your payment of " + safe(totalDisplay)
                + " for invoice " + safe(invoiceNumber) + ".\n\n"
                + "  Paid on: " + safe(paidDateDisplay) + "\n"
                + (paidMethod == null || paidMethod.isBlank() ? ""
                        : "  Method:  " + paidMethod + "\n")
                + "\nYour receipt is at: " + publicUrl + "\n"
                + "\nNeed a copy for your records? Open the link above and Print → Save as PDF.\n"
                + "\nNeed to reach " + safe(businessName) + "?\n"
                + (tenantPhone == null || tenantPhone.isBlank() ? ""
                        : "  Phone: " + tenantPhone + "\n")
                + (tenantEmail == null || tenantEmail.isBlank() ? ""
                        : "  Email: " + tenantEmail + "\n")
                + "\nSent via Conddo.";
        String html = templates.render("invoice-receipt.html", Map.of(
                "CUSTOMER_NAME",  safe(customerName),
                "BUSINESS_NAME",  safe(businessName),
                "INVOICE_NUMBER", safe(invoiceNumber),
                "TOTAL_DISPLAY",  safe(totalDisplay),
                "PAID_DATE",      safe(paidDateDisplay),
                "PAID_METHOD",    safe(paidMethod),
                "PUBLIC_URL",     safe(publicUrl),
                "TENANT_PHONE",   safe(tenantPhone),
                "TENANT_EMAIL",   safe(tenantEmail)));
        if (html.isBlank()) {
            emailSender.send(toEmail, subject, text);
        } else {
            emailSender.sendHtml(toEmail, subject, html, text);
        }
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
