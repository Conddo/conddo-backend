package io.conddo.api.web;

import io.conddo.core.common.ApiError;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.PharmacyDiscount;
import io.conddo.core.domain.Product;
import io.conddo.core.service.PharmacyDiscountService;
import io.conddo.core.service.PharmacyDiscountService.DiscountWithProduct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped discount surface (Pharmacy Spec v2 §12B). All roles
 * can read the discount list and POST a discount (it lands as
 * {@code PENDING_APPROVAL}). Only {@code TENANT_ADMIN}/{@code SUPER_ADMIN}
 * can approve or reject. Delete rule (spec §12B):
 * <ul>
 *   <li>Admin can delete anything.</li>
 *   <li>Creator can delete their own pending row.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/pharmacy/discounts")
public class PharmacyDiscountController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String ADMIN = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final PharmacyDiscountService service;

    public PharmacyDiscountController(PharmacyDiscountService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PharmacyDiscount> result = service.list(status, productId,
                PageRequest.of(page, size));
        List<Map<String, Object>> rows = result.getContent().stream()
                .map(d -> toRow(service.enrich(d))).toList();
        return ApiResponse.ok(rows, ApiResponse.Meta.page(
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @Valid @RequestBody CreateDiscountRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID createdBy = UUID.fromString(jwt.getSubject());
        PharmacyDiscount created = service.create(body.productId(),
                body.discountType(), body.discountValue(), body.label(),
                body.startsAt(), body.endsAt(), createdBy);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("discount", toRow(service.enrich(created)));
        resp.put("message", "Discount submitted for admin approval.");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(resp));
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize(ADMIN)
    public ApiResponse<Map<String, Object>> approveOrReject(
            @PathVariable UUID id,
            @Valid @RequestBody ApproveRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID approver = UUID.fromString(jwt.getSubject());
        PharmacyDiscount updated = switch (body.action().toUpperCase()) {
            case "APPROVE" -> service.approve(id, approver);
            case "REJECT" -> service.reject(id, approver, body.note());
            default -> throw new IllegalArgumentException("action must be APPROVE or REJECT");
        };
        return ApiResponse.ok(Map.of(
                "success", true,
                "discount", toRow(service.enrich(updated))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        PharmacyDiscount discount = service.get(id);
        boolean isAdmin = jwt.getClaimAsStringList("authorities") != null
                && jwt.getClaimAsStringList("authorities").stream()
                        .anyMatch(a -> a.equals("ROLE_TENANT_ADMIN") || a.equals("ROLE_SUPER_ADMIN"));
        String role = jwt.getClaimAsString("role");
        if (role != null) {
            isAdmin = isAdmin || "TENANT_ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        }
        UUID currentUserId = UUID.fromString(jwt.getSubject());
        boolean isCreatorOfPending = discount.getCreatedBy() != null
                && discount.getCreatedBy().equals(currentUserId)
                && PharmacyDiscount.STATUS_PENDING.equals(discount.getStatus());
        if (!isAdmin && !isCreatorOfPending) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(
                    ApiError.of("FORBIDDEN",
                            "Only the discount's creator can delete it while pending; "
                                    + "approved discounts can only be deleted by an admin.")));
        }
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("success", true)));
    }

    private static Map<String, Object> toRow(DiscountWithProduct view) {
        PharmacyDiscount d = view.discount();
        Product p = view.product();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", d.getId());
        Map<String, Object> productSummary = new LinkedHashMap<>();
        productSummary.put("id", d.getProductId());
        productSummary.put("nameGeneric", p == null ? null
                : (p.getNameGeneric() == null ? p.getName() : p.getNameGeneric()));
        productSummary.put("price", p == null ? null : p.getPrice());
        row.put("product", productSummary);
        row.put("discountType", d.getDiscountType());
        row.put("discountValue", d.getDiscountValue());
        row.put("discountedPrice", p == null || p.getPrice() == null
                ? null : d.applyTo(p.getPrice()));
        row.put("label", d.getLabel());
        row.put("startsAt", d.getStartsAt());
        row.put("endsAt", d.getEndsAt());
        row.put("status", d.getStatus());
        row.put("createdBy", d.getCreatedBy());
        row.put("approvedBy", d.getApprovedBy());
        row.put("approvedAt", d.getApprovedAt());
        row.put("rejectionNote", d.getRejectionNote());
        row.put("createdAt", d.getCreatedAt());
        return row;
    }

    // ----- request DTOs ------------------------------------------------------

    public record CreateDiscountRequest(@NotNull UUID productId,
                                        @NotBlank String discountType,
                                        @NotNull @Positive BigDecimal discountValue,
                                        String label,
                                        @NotNull OffsetDateTime startsAt,
                                        OffsetDateTime endsAt) {
    }

    public record ApproveRequest(@NotBlank String action, String note) {
    }
}
