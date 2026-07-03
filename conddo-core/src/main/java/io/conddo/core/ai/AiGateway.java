package io.conddo.core.ai;

/**
 * The one entry point every AI-consuming feature in the app goes through.
 *
 * <p>Contract:
 * <ol>
 *   <li>Tenant-scoped calls check the caller's email is verified. Unverified
 *       accounts get a clean {@link EmailVerificationRequiredException} so the
 *       FE can render the same amber banner instead of a spinner-of-doom.</li>
 *   <li>Credits are reserved on the tenant's account BEFORE the LLM call
 *       fires. Reservation cost comes from {@code CreditActions.costOf}. If
 *       the tenant is broke, {@link io.conddo.core.credits.CreditExhaustedException}
 *       propagates up — no call is made, no tokens burned.</li>
 *   <li>The LLM is called via {@link AnthropicGateway#chatText} (which is
 *       {@code @Primary}-injected — OpenRouter → DeepSeek in prod).</li>
 *   <li>Success confirms the reservation (credits move to consumed).
 *       Failure releases it (credits return to available). Either way an
 *       audit row is written so the tenant can reconcile spend.</li>
 * </ol>
 *
 * <p>Vision calls (Pharmacy Product Assistant) stay on
 * {@link AnthropicGateway#chatWithImage} directly for now — vision needs a
 * different credit calculation (pixels ≠ tokens) and hasn't been priced.
 */
public interface AiGateway {

    /**
     * Fire an AI text call under the credit/verified-email contract.
     *
     * @throws EmailVerificationRequiredException                       tenant-scoped call, caller unverified
     * @throws io.conddo.core.credits.CreditExhaustedException          not enough credits
     * @throws AnthropicGateway.AnthropicUnavailableException           provider failure
     * @throws AnthropicGateway.AnthropicNotConfiguredException         no LLM adapter wired
     */
    String chatText(AiCallContext context, String prompt);
}
