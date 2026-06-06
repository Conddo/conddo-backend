package io.conddo.api.studio;

import io.conddo.core.common.ApiError;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.CreativeServiceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Studio → main API callback for creative-service delivery
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §5 step 6). Authenticated by the
 * same {@code STUDIO_JWT_SECRET} (treated as a shared service token here)
 * so the cross-service link doesn't need a second secret.
 *
 * <p>Permitted in {@link io.conddo.api.security.SecurityConfig}; the token
 * compare is constant-time (via {@link java.security.MessageDigest#isEqual}).
 */
@RestController
@RequestMapping("/api/v1/internal/creative-services")
public class InternalCreativeServiceController {

    public static final String SERVICE_TOKEN_HEADER = "X-Studio-Service-Token";
    private static final Logger log = LoggerFactory.getLogger(InternalCreativeServiceController.class);

    private final CreativeServiceService creativeServiceService;
    private final String serviceToken;

    public InternalCreativeServiceController(CreativeServiceService creativeServiceService,
                                             @Value("${studio.service-token:${studio.jwt-secret:}}") String serviceToken) {
        this.creativeServiceService = creativeServiceService;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    @PostMapping("/{id}/delivered")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delivered(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody DeliveredRequest body) {

        if (serviceToken.isBlank() || !constantTimeEquals(serviceToken, request.getHeader(SERVICE_TOKEN_HEADER))) {
            log.warn("Rejected creative-service delivered callback — bad or missing {}", SERVICE_TOKEN_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResponse.fail(ApiError.of("UNAUTHENTICATED",
                            "Missing or invalid " + SERVICE_TOKEN_HEADER)));
        }

        try {
            creativeServiceService.handleDelivered(id, body == null ? List.of() : body.media());
            log.info("Creative-service request {} marked delivered ({} media items)",
                    id, body == null || body.media() == null ? 0 : body.media().size());
            return ResponseEntity.ok(ApiResponse.ok(Map.of("received", true, "requestId", id)));
        } catch (RuntimeException ex) {
            log.error("Failed to process creative-service delivery for {}: {}", id, ex.getMessage());
            // 200 anyway — Studio shouldn't retry on our internal error; ops monitors logs.
            return ResponseEntity.ok(ApiResponse.ok(Map.of("received", false, "error", ex.getMessage())));
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public record DeliveredRequest(@NotEmpty List<Map<String, Object>> media) {
    }
}
