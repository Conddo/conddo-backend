package io.conddo.core.paystack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Stand-in for environments without Paystack configured. Every call
 * throws so the API layer maps it to 503; the BE boots fine on local
 * dev / tests without the secret key set.
 */
@Component
public class DormantPaystackGateway implements PaystackGateway {

    private static final Logger log = LoggerFactory.getLogger(DormantPaystackGateway.class);

    public DormantPaystackGateway() {
        log.info("PaystackGateway is dormant — set CONDDO_PAYSTACK_SECRET_KEY to enable billing checkout");
    }

    @Override
    public InitResult initialize(String email, long amountKobo, String reference,
                                 String callbackUrl, Map<String, Object> metadata) {
        throw new PaystackNotConfiguredException();
    }

    @Override
    public VerifyResult verify(String reference) {
        throw new PaystackNotConfiguredException();
    }

    @Override
    public void disableSubscription(String subscriptionCode, String emailToken) {
        throw new PaystackNotConfiguredException();
    }

    @Override
    public boolean verifyWebhookSignature(String body, String signature) {
        // No secret to verify against — refuse all webhooks in dormant
        // mode rather than letting forged payloads through.
        return false;
    }
}
