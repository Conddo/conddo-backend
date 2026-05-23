package io.conddo.core.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STUB implementation of {@link NotificationPort} until the notifications engine
 * (Phase 1 item 5) is built. It logs the reset token instead of emailing it, so
 * the reset flow is exercisable end-to-end in development. Replace/relegate this
 * once Resend/Termii delivery exists.
 */
@Component
public class LoggingNotificationPort implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationPort.class);

    @Override
    public void sendPasswordReset(String toEmail, String resetToken) {
        log.warn("[notifications:STUB] Password-reset token for {} = {}  "
                + "(wire real delivery in Phase 1 item 5, PRD §6.4)", toEmail, resetToken);
    }
}
