package io.conddo.core.ai;

import io.conddo.core.credits.CreditActions;
import io.conddo.core.credits.CreditService;
import io.conddo.core.domain.CreditTransaction;
import io.conddo.core.domain.User;
import io.conddo.core.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one entry point every AI-consuming feature in the app goes through
 * (see {@link AiGateway}). Wraps {@link AnthropicGateway} with the credit
 * reserve→confirm/release loop and the verified-email gate.
 */
@Service
public class CreditedAiGateway implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(CreditedAiGateway.class);

    /** Action ids that should prefer the NVIDIA gateway when it's configured.
     *  Signup classification is the main one — free-tier Nemotron is fast
     *  and structured-JSON reliable, so the onboarding wizard doesn't burn
     *  paid OpenRouter credits before the tenant even exists. Extend this
     *  set deliberately (per-action opt-in), never as a broad "route all AI
     *  through NVIDIA" — the vision path can't (see HttpNvidiaGateway) and
     *  the website generator wants a stronger model. */
    private static final Set<String> NVIDIA_PREFERRED_ACTIONS = Set.of(
            CreditActions.AI_PROVISIONING);

    private final AnthropicGateway llm;
    private final Optional<HttpNvidiaGateway> nvidia;
    private final CreditService creditService;
    private final UserRepository userRepository;
    private final AiModelSelector modelSelector;

    public CreditedAiGateway(AnthropicGateway llm,
                             @Autowired(required = false) HttpNvidiaGateway nvidia,
                             CreditService creditService,
                             UserRepository userRepository,
                             AiModelSelector modelSelector) {
        this.llm = llm;
        this.nvidia = Optional.ofNullable(nvidia);
        this.creditService = creditService;
        this.userRepository = userRepository;
        this.modelSelector = modelSelector;
        if (this.nvidia.isPresent()) {
            log.info("CreditedAiGateway: NVIDIA gateway available; routing {} through it",
                    NVIDIA_PREFERRED_ACTIONS);
        }
    }

    @Override
    public String chatText(AiCallContext ctx, String prompt) {
        // Model name is provider-specific (OpenRouter slugs vs NVIDIA slugs).
        // callWithFallback picks the model right per-provider.
        String openRouterModel = modelSelector.modelFor(ctx.actionType());

        if (ctx.tenantId() == null) {
            // Pre-tenant path — onboarding classifier. No credit gate, no
            // verified-email gate; the AI provisioning charge is booked at
            // tenant creation via CreditService.provisionAccount.
            return callWithFallback(ctx.actionType(), prompt, openRouterModel);
        }

        requireVerifiedEmail(ctx);
        CreditTransaction reservation = creditService.reserve(
                ctx.tenantId(), ctx.actionType(), ctx.referenceId(), ctx.referenceType());
        try {
            String response = callWithFallback(ctx.actionType(), prompt, openRouterModel);
            creditService.confirm(ctx.tenantId(), reservation.getId());
            return response;
        } catch (RuntimeException ex) {
            // Any failure hands the credits back — the tenant shouldn't be
            // charged for a failed call.
            try {
                creditService.release(ctx.tenantId(), reservation.getId());
            } catch (RuntimeException releaseEx) {
                log.error("Failed to release reservation {} after AI call error", reservation.getId(), releaseEx);
            }
            throw ex;
        }
    }

    /** Dispatches to NVIDIA (if opted in for this action AND configured)
     *  with a null model override so NVIDIA uses its own default Nemotron
     *  slug — the {@code openRouterModel} passed in wouldn't be recognised
     *  by NIM. On NVIDIA failure, falls back once to the primary provider
     *  with the OpenRouter-flavored model so a transient NIM outage during
     *  a signup wizard doesn't abort the flow. */
    private String callWithFallback(String actionType, String prompt, String openRouterModel) {
        if (nvidia.isPresent() && NVIDIA_PREFERRED_ACTIONS.contains(actionType)) {
            try {
                return nvidia.get().chatText(prompt, null);
            } catch (RuntimeException ex) {
                log.warn("NVIDIA call failed for {}; falling back to primary provider: {}",
                        actionType, ex.getMessage());
                // Fall through to primary
            }
        }
        return llm.chatText(prompt, openRouterModel);
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
