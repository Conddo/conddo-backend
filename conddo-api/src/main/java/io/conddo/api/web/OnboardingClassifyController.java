package io.conddo.api.web;

import io.conddo.core.auth.RegistrationService;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.ModuleSuggestionService;
import io.conddo.core.service.ModuleSuggestionService.Result;
import io.conddo.core.service.ModuleSuggestionService.Score;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 */
@RestController
@RequestMapping("/auth/register")
public class OnboardingClassifyController {

    private final ModuleSuggestionService suggestionService;
    private final RegistrationService registrationService;

    public OnboardingClassifyController(ModuleSuggestionService suggestionService,
                                        RegistrationService registrationService) {
        this.suggestionService = suggestionService;
        this.registrationService = registrationService;
    }

    @PostMapping("/classify")
    public ApiResponse<Map<String, Object>> classify(@Valid @RequestBody ClassifyRequest body) {
        // Registration must exist and not be completed. Throws
        // RegistrationNotFoundException (mapped to 404) otherwise.
        registrationService.requireActive(body.registrationId());
        Result result = suggestionService.suggest(body.description(), body.verticalHint());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scores", result.scores().stream().map(OnboardingClassifyController::toRow).toList());
        resp.put("recommended", result.recommended().stream()
                .map(OnboardingClassifyController::toRow).toList());
        return ApiResponse.ok(resp);
    }

    private static Map<String, Object> toRow(Score s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.id());
        row.put("confidence", s.confidence());
        row.put("reason", s.reason());
        return row;
    }

    public record ClassifyRequest(
            @NotNull UUID registrationId,
            @NotBlank String description,
            String verticalHint
    ) {
    }
}
