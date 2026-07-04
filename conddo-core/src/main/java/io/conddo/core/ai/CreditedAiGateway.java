package io.conddo.core.ai;

import io.conddo.core.credits.CreditActions;
import io.conddo.core.credits.CreditService;
import io.conddo.core.domain.CreditTransaction;
import io.conddo.core.domain.User;
import io.conddo.core.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The one entry point every AI-consuming feature in the app goes through
 * (see {@link AiGateway}). Wraps {@link AnthropicGateway} with the credit
 * reserve→confirm/release loop and the verified-email gate.
 */
@Service
public class CreditedAiGateway implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(CreditedAiGateway.class);

    private final AnthropicGateway llm;
    private final CreditService creditService;
    private final UserRepository userRepository;
    private final AiModelSelector modelSelector;

    public CreditedAiGateway(AnthropicGateway llm,
                             CreditService creditService,
                             UserRepository userRepository,
                             AiModelSelector modelSelector) {
        this.llm = llm;
        this.creditService = creditService;
        this.userRepository = userRepository;
        this.modelSelector = modelSelector;
    }

    @Override
    public String chatText(AiCallContext ctx, String prompt) {
        // Route per action — website builder gets Sonnet, classifier gets
        // DeepSeek, marketing copy gets Gemini. Null = adapter's default.
        String model = modelSelector.modelFor(ctx.actionType());

        if (ctx.tenantId() == null) {
            // Pre-tenant path — onboarding classifier. No credit gate, no
            // verified-email gate; the AI provisioning charge is booked at
            // tenant creation via CreditService.provisionAccount.
            return llm.chatText(prompt, model);
        }

        requireVerifiedEmail(ctx);
        CreditTransaction reservation = creditService.reserve(
                ctx.tenantId(), ctx.actionType(), ctx.referenceId(), ctx.referenceType());
        try {
            String response = llm.chatText(prompt, model);
            creditService.confirm(ctx.tenantId(), reservation.getId());
            return response;
        } catch (RuntimeException ex) {
            // Any failure (provider down, timeout, malformed response later
            // parsed by the caller) hands the credits back — the tenant
            // shouldn't be charged for a failed call.
            try {
                creditService.release(ctx.tenantId(), reservation.getId());
            } catch (RuntimeException releaseEx) {
                log.error("Failed to release reservation {} after AI call error", reservation.getId(), releaseEx);
            }
            throw ex;
        }
    }

    private void requireVerifiedEmail(AiCallContext ctx) {
        if (ctx.userId() == null) {
            // System-triggered call (scheduled Daily Brief, webhook, etc.) —
            // no interactive user to gate on. Trust the caller.
            return;
        }
        User user = userRepository.findById(ctx.userId()).orElse(null);
        if (user == null || !user.isEmailVerified()) {
            throw new EmailVerificationRequiredException();
        }
    }

    /** Convenience for the Daily Brief and other scheduled surfaces —
     *  binds a fresh AiCallContext at each call site so callers can't forget
     *  the actionType. Uses SOCIAL_AI_SCHEDULE as the closest existing
     *  action; callers of this shortcut should pass their real action once
     *  the moat surface (Daily Brief, Nudge Queue, etc.) has a canonical
     *  credit-charged entry. */
    public String daily(UUID tenantId, String prompt) {
        AiCallContext ctx = AiCallContext.forTenant(tenantId, null, CreditActions.SOCIAL_AI_SCHEDULE);
        return chatText(ctx, prompt);
    }
}
