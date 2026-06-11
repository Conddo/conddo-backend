package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.PharmacyFollowup;
import io.conddo.core.service.PharmacyFollowupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped follow-up workflow (Pharmacy Roadmap Beta 2). Class-level
 * {@code @PreAuthorize} routes through {@link io.conddo.api.features.FeatureFlagGuard}
 * — tenants without {@code followup_workflow} enabled hit 403
 * FEATURE_NOT_ENABLED before any handler runs.
 */
@RestController
@RequestMapping("/api/v1/pharmacy/followups")
@PreAuthorize("@featureFlagGuard.requiresFlag('followup_workflow') "
        + "and hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class PharmacyFollowupController {

    private final PharmacyFollowupService service;

    public PharmacyFollowupController(PharmacyFollowupService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PharmacyFollowup> result = service.list(status, customerId, PageRequest.of(page, size));
        List<Map<String, Object>> rows = result.getContent().stream()
                .map(PharmacyFollowupController::toRow).toList();
        return ApiResponse.ok(rows, ApiResponse.Meta.page(
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/due-today")
    public ApiResponse<List<Map<String, Object>>> dueToday() {
        return ApiResponse.ok(service.dueToday().stream()
                .map(PharmacyFollowupController::toRow).toList());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @Valid @RequestBody CreateFollowupRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID createdBy = UUID.fromString(jwt.getSubject());
        PharmacyFollowup created = service.create(body.customerId(), body.orderId(),
                body.productId(), body.dueDate(), body.checkNote(), createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(toRow(created)));
    }

    @PatchMapping("/{id}/complete")
    public ApiResponse<Map<String, Object>> complete(@PathVariable UUID id,
                                                     @Valid @RequestBody CompleteFollowupRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        UUID completedBy = UUID.fromString(jwt.getSubject());
        return ApiResponse.ok(toRow(service.complete(id, body.outcome(),
                body.outcomeType(), completedBy)));
    }

    @PatchMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(toRow(service.cancel(id)));
    }

    private static Map<String, Object> toRow(PharmacyFollowup f) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.getId());
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", f.getCustomerId());
        row.put("customer", customer);
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", f.getProductId());
        row.put("product", f.getProductId() == null ? null : product);
        row.put("orderId", f.getOrderId());
        row.put("dueDate", f.getDueDate());
        row.put("checkNote", f.getCheckNote());
        row.put("status", f.getStatus());
        row.put("outcome", f.getOutcome());
        row.put("outcomeType", f.getOutcomeType());
        Map<String, Object> completedBy = new LinkedHashMap<>();
        completedBy.put("id", f.getCompletedBy());
        row.put("completedBy", f.getCompletedBy() == null ? null : completedBy);
        row.put("completedAt", f.getCompletedAt());
        Map<String, Object> createdBy = new LinkedHashMap<>();
        createdBy.put("id", f.getCreatedBy());
        row.put("createdBy", createdBy);
        row.put("createdAt", f.getCreatedAt());
        return row;
    }

    public record CreateFollowupRequest(@NotNull UUID customerId,
                                         UUID productId,
                                         UUID orderId,
                                         @NotNull OffsetDateTime dueDate,
                                         @NotBlank String checkNote) {
    }

    public record CompleteFollowupRequest(@NotBlank String outcome,
                                           @NotBlank String outcomeType) {
    }
}
