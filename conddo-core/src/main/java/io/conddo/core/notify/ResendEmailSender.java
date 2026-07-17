package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Real email channel via Resend. Enabled only when
 * {@code conddo.notifications.email.provider=resend}; otherwise
 * {@link LoggingEmailSender} is used. Posts to Resend's {@code /emails} endpoint.
 *
 * <p><b>Unverified against the live API</b> — built from Resend's documented
 * contract; confirm the verified {@code from} domain when you enable it.
 */
@Component
@Primary
@ConditionalOnProperty(name = "conddo.notifications.email.provider", havingValue = "resend")
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final String DEFAULT_BASE_URL = "https://api.resend.com";

    private final RestClient restClient;
    private final String from;

    public ResendEmailSender(NotificationProperties properties, RestClient.Builder restClientBuilder) {
        NotificationProperties.Email email = properties.email();
        String baseUrl = email.baseUrl() != null ? email.baseUrl() : DEFAULT_BASE_URL;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + email.apiKey())
                .build();
        this.from = email.from();
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        try {
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", from,
                            "to", List.of(toEmail),
                            "subject", subject,
                            "text", body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            log.error("Resend email to {} failed: {}", toEmail, ex.getMessage());
        }
    }

    /**
     * Send a real HTML email with a plain-text alternative. Resend accepts
     * {@code html} and {@code text} as sibling fields on the same payload
     * and the recipient's client picks the appropriate one — HTML for the
     * inbox render, text for accessibility tools + text-only clients.
     *
     * <p>The interface default falls through to {@link #send} with only the
     * text body, which is why every prior HTML email arrived unstyled.
     * Override is required for every real provider adapter — Brevo does
     * the same in {@code BrevoEmailSender}.
     */
    @Override
    public void sendHtml(String toEmail, String subject, String htmlBody, String textBody) {
        try {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("from", from);
            payload.put("to", List.of(toEmail));
            payload.put("subject", subject);
            if (htmlBody != null && !htmlBody.isBlank()) {
                payload.put("html", htmlBody);
            }
            if (textBody != null && !textBody.isBlank()) {
                payload.put("text", textBody);
            }
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            log.error("Resend HTML email to {} failed: {}", toEmail, ex.getMessage());
        }
    }
}
