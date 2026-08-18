package io.conddo.api.billing;

import io.conddo.core.service.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls connected Moniepoint accounts for paid transactions and pushes
 * them into {@code /transfers/incoming} (V77). This is the always-on
 * ingestion path — the provider webhooks are an accelerator, not a
 * dependency, so money shows up in the feed even if a webhook is
 * misconfigured or a payload is unroutable.
 *
 * <p>OPay accounts are webhook-primary (no documented list endpoint),
 * so this job only walks Moniepoint. Interval is configurable for tests
 * via {@code conddo.integrations.sync-interval-ms}.
 */
@Component
public class TransferSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(TransferSyncScheduler.class);

    private final TransferService transferService;

    public TransferSyncScheduler(TransferService transferService) {
        this.transferService = transferService;
    }

    @Scheduled(fixedDelayString = "${conddo.integrations.sync-interval-ms:300000}")
    public void runOnce() {
        try {
            transferService.syncConnectedAccounts();
        } catch (RuntimeException ex) {
            log.error("Transfer sync failed: {}", ex.getMessage());
        }
    }
}
