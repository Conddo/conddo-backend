package io.conddo.studio.ai;

import java.util.Optional;

/**
 * Port for the Claude (Anthropic) text-completion call used by the AI assistant
 * (§8). Implemented with the official Anthropic Java SDK; mockable in tests.
 *
 * <p>Per the §20 AI rules, this <b>never throws</b>: a failed call, a timeout, or
 * an unconfigured key all return {@link Optional#empty()} so the assistant can
 * degrade to "no suggestions" instead of failing the request.
 */
public interface ClaudeClient {

    /**
     * One non-streaming completion. Returns the concatenated text, or empty on any
     * failure / when not configured.
     *
     * @param think enable adaptive thinking (for genuinely complex tasks like the QA scan)
     */
    Optional<String> complete(String systemPrompt, String userPrompt, int maxTokens, boolean think);

    /** Whether a Claude API key is configured (the assistant is live). */
    boolean isConfigured();
}
