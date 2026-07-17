package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.TenantRequest;
import io.conddo.core.service.SupportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-facing support endpoints. Any authenticated tenant user may
 * submit a request and read their tenant's history — the RLS policy on
 * {@code tenant_requests} scopes reads to the caller's tenant.
 */
@RestController
@RequestMapping("/api/v1/support/requests")
@PreAuthorize("isAuthenticated()")
public class SupportController {

    private final SupportService service;

    public SupportController(SupportService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RequestRow>> submit(@Valid @RequestBody SubmitRequest body,
                                                          @AuthenticationPrincipal Object principal) {
        UUID userId = userIdFromPrincipal(principal);
        TenantRequest saved = service.submit(userId, body.kind(), body.title(), body.body());
        return ResponseEntity
                .status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.ok(RequestRow.of(saved)));
    }

    @GetMapping
    public ApiResponse<List<RequestRow>> mine() {
        return ApiResponse.ok(service.mine().stream().map(RequestRow::of).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<RequestRow> one(@PathVariable UUID id) {
        return ApiResponse.ok(RequestRow.of(service.one(id)));
    }

    // ----- wire records ----------------------------------------------------

    public record SubmitRequest(
            @NotBlank String kind,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 5_000) String body) {}

    public record RequestRow(
            UUID id, String kind, String title, String body,
            String status, String priority,
            String adminResponse,
            OffsetDateTime respondedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        public static RequestRow of(TenantRequest r) {
            return new RequestRow(
                    r.getId(), r.getKind(), r.getTitle(), r.getBody(),
                    r.getStatus(), r.getPriority(),
                    r.getAdminResponse(),
                    r.getRespondedAt(),
                    r.getCreatedAt(),
                    r.getUpdatedAt());
        }
    }

    private static UUID userIdFromPrincipal(Object principal) {
        if (principal == null) return null;
        if (principal instanceof UUID id) return id;
        try {
            return UUID.fromString(principal.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
