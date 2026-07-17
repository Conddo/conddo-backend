package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.TenantRequest;
import io.conddo.core.service.AdminSupportService;
import io.conddo.core.service.AdminSupportService.RequestWithTenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Studio-facing support triage. SUPER_ADMIN reads across every tenant,
 * files replies, updates status/priority. The list endpoint powers the
 * dashboard summary + the /admin/requests page. Replies stamp
 * {@code responded_by} so the tenant reply history has an audit trail.
 */
@RestController
@RequestMapping("/api/v1/admin/requests")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminSupportController {

    private final AdminSupportService service;

    public AdminSupportController(AdminSupportService service) {
        this.service = service;
    }

    /** filter: OPEN | IN_PROGRESS | RESOLVED | DISMISSED | ALL (default). */
    @GetMapping
    public ApiResponse<List<AdminRequestRow>> list(
            @RequestParam(name = "status", required = false) String status) {
        return ApiResponse.ok(service.list(status).stream()
                .map(AdminRequestRow::of).toList());
    }

    @GetMapping("/counts")
    public ApiResponse<Map<String, Long>> counts() {
        return ApiResponse.ok(service.statusCounts());
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminRequestRow> one(@PathVariable UUID id) {
        return ApiResponse.ok(AdminRequestRow.of(service.one(id)));
    }

    @PostMapping("/{id}/respond")
    public ApiResponse<AdminRequestRow> respond(@PathVariable UUID id,
                                                @Valid @RequestBody RespondRequest body,
                                                @AuthenticationPrincipal Object principal) {
        UUID staffId = staffIdFromPrincipal(principal);
        return ApiResponse.ok(AdminRequestRow.of(service.respond(id, body.response(), staffId)));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<AdminRequestRow> changeStatus(@PathVariable UUID id,
                                                     @Valid @RequestBody StatusRequest body) {
        return ApiResponse.ok(AdminRequestRow.of(service.setStatus(id, body.status())));
    }

    @PostMapping("/{id}/priority")
    public ApiResponse<AdminRequestRow> changePriority(@PathVariable UUID id,
                                                        @Valid @RequestBody PriorityRequest body) {
        return ApiResponse.ok(AdminRequestRow.of(service.setPriority(id, body.priority())));
    }

    // ----- wire records ----------------------------------------------------

    public record RespondRequest(@NotBlank @Size(max = 10_000) String response) {}
    public record StatusRequest(@NotBlank String status) {}
    public record PriorityRequest(@NotBlank String priority) {}

    /** Compact list/detail row that includes the owning tenant identity so
     *  the admin UI can render "who filed this" without a second call. */
    public record AdminRequestRow(
            UUID id,
            UUID tenantId, String tenantSlug, String tenantName,
            String kind, String title, String body,
            String status, String priority,
            String adminResponse,
            UUID respondedBy,
            OffsetDateTime respondedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        static AdminRequestRow of(RequestWithTenant wt) {
            TenantRequest r = wt.request();
            Tenant t = wt.tenant();
            return new AdminRequestRow(
                    r.getId(),
                    r.getTenantId(),
                    t != null ? t.getSlug() : null,
                    t != null ? t.getName() : "(deleted tenant)",
                    r.getKind(), r.getTitle(), r.getBody(),
                    r.getStatus(), r.getPriority(),
                    r.getAdminResponse(),
                    r.getRespondedBy(),
                    r.getRespondedAt(),
                    r.getCreatedAt(),
                    r.getUpdatedAt());
        }
    }

    private static UUID staffIdFromPrincipal(Object principal) {
        if (principal == null) return null;
        if (principal instanceof UUID id) return id;
        try {
            return UUID.fromString(principal.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
