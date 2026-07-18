package io.conddo.api.web;

import io.conddo.api.security.InMemoryRateLimiter;
import io.conddo.core.auth.RegistrationService;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.RateLimitExceededException;
import io.conddo.core.service.KeywordModuleClassifier;
import io.conddo.core.service.ModuleSuggestionService;
import io.conddo.core.service.ModuleSuggestionService.Result;
import io.conddo.core.service.ModuleSuggestionService.Score;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public AI classification for the pre-tenant onboarding flow. The
 * authenticated {@link ModuleSuggestionController} at
 * {@code /api/v1/tenant/modules/suggest} requires a tenant; users mid-signup
 * don't have one yet. This endpoint takes a valid {@code registrationId}
 * (proving the caller is in the middle of a live signup) plus their free-text
 * business description and returns the same scored/recommended module list.
 *
 * <p><b>Abuse guards</b> (PR 2f) — every call hits the AI provider, so:
 * <ul>
 *   <li>Max {@value #MAX_ATTEMPTS_PER_REGISTRATION} calls per registrationId
 *       (persisted counter on {@code pending_registrations})</li>
 *   <li>{@link InMemoryRateLimiter}'s default 20/min per client IP —
 *       shared across all classify calls from the same source, so a scripted
 *       attacker rotating registrationIds still hits the IP cap</li>
 * </ul>
 */
@RestController
@RequestMapping("/auth/register")
public class OnboardingClassifyController {

    /** Realistic ceiling — an owner refining their description twice + one
     *  re-classify after adjusting business type = 3 uses. 5 gives headroom
     *  for slow-typers without letting a bot burn our AI budget. */
    static final int MAX_ATTEMPTS_PER_REGISTRATION = 5;

    private final ModuleSuggestionService suggestionService;
    private final KeywordModuleClassifier keywordClassifier;
    private final RegistrationService registrationService;
    private final InMemoryRateLimiter rateLimiter;
    /** {@code keyword} (default) = deterministic classifier, no AI spend.
     *  {@code ai} = call the LLM. {@code hybrid} = keyword first, LLM only when
     *  keyword vertical confidence is below the 0.5 threshold. */
    private final String mode;

    public OnboardingClassifyController(ModuleSuggestionService suggestionService,
                                        KeywordModuleClassifier keywordClassifier,
                                        RegistrationService registrationService,
                                        InMemoryRateLimiter rateLimiter,
                                        @Value("${conddo.classify.mode:keyword}") String mode) {
        this.suggestionService = suggestionService;
        this.keywordClassifier = keywordClassifier;
        this.registrationService = registrationService;
        this.rateLimiter = rateLimiter;
        this.mode = mode == null ? "keyword" : mode.trim().toLowerCase();
    }

    @PostMapping("/classify")
    public ApiResponse<Map<String, Object>> classify(@Valid @RequestBody ClassifyRequest body,
                                                     HttpServletRequest request) {
        // IP guard first — cheapest check, blocks scripted attackers rotating
        // registrationIds.
        if (!rateLimiter.tryAcquire(clientIp(request) + ":classify")) {
            throw new RateLimitExceededException("Too many classify requests — please try again shortly");
        }

        // Registration must exist and not be completed. Throws
        // RegistrationNotFoundException (mapped to 404) otherwise.
        int attempts = registrationService.recordClassifyAttempt(body.registrationId());
        if (attempts > MAX_ATTEMPTS_PER_REGISTRATION) {
            throw new RateLimitExceededException(
                    "This signup has reached the classification limit. Continue with the current suggestions.");
        }

        Result result = classifyByMode(body.description(), body.verticalHint());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("vertical", result.vertical());
        resp.put("verticalConfidence", result.verticalConfidence());
        resp.put("scores", result.scores().stream().map(OnboardingClassifyController::toRow).toList());
        resp.put("recommended", result.recommended().stream()
                .map(OnboardingClassifyController::toRow).toList());
        return ApiResponse.ok(resp);
    }

    /** Route the request through the configured classifier. Keyword is the
     *  default: deterministic, zero AI spend, indistinguishable wire shape.
     *  Hybrid keeps AI as a low-confidence fallback so we never pay for
     *  descriptions that keyword handles fine. */
    private Result classifyByMode(String description, String verticalHint) {
        switch (mode) {
            case "ai":
                return suggestionService.suggest(description, verticalHint);
            case "hybrid":
                Result kw = keywordClassifier.classify(description, verticalHint);
                if (kw.verticalConfidence() < 0.5) {
                    return suggestionService.suggest(description, verticalHint);
                }
                return kw;
            case "keyword":
            default:
                return keywordClassifier.classify(description, verticalHint);
        }
    }

    private static Map<String, Object> toRow(Score s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.id());
        row.put("confidence", s.confidence());
        row.put("reason", s.reason());
        return row;
    }

    /** Trust X-Forwarded-For only when the request came through Caddy — Caddy
     *  strips the client-supplied header before setting a trusted one. Falls
     *  back to the socket peer otherwise. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    /** {@code description} may be blank when {@code verticalHint} is set —
     *  the picker-first onboarding flow lets tenants pick a vertical without
     *  writing a description. The classifier requires at least one signal. */
    public record ClassifyRequest(
            @NotNull UUID registrationId,
            String description,
            String verticalHint
    ) {
    }
}
