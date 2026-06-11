package io.conddo.api.pharmacy;

import io.conddo.core.service.PharmacyFollowupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily sweep that flips PENDING follow-ups whose due_date passed
 * 48h+ ago to MISSED (Pharmacy Roadmap Beta 2). Cross-tenant via the
 * V42 RLS carve-out. Configurable cron for tests.
 */
@Component
public class PharmacyFollowupMissedScheduler {

    private static final Logger log = LoggerFactory.getLogger(PharmacyFollowupMissedScheduler.class);

    private final PharmacyFollowupService service;

    public PharmacyFollowupMissedScheduler(PharmacyFollowupService service) {
        this.service = service;
    }

    @Scheduled(cron = "${conddo.pharmacy.followup-missed-cron:0 0 6 * * *}", zone = "UTC")
    public void runOnce() {
        try {
            int swept = service.sweepMissed();
            if (swept > 0) {
                log.info("Pharmacy follow-up missed sweep: {} flipped to MISSED", swept);
            }
        } catch (RuntimeException ex) {
            log.error("Pharmacy follow-up missed sweep failed: {}", ex.getMessage());
        }
    }
}
