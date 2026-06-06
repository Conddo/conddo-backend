package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.CreativeServiceOffering;
import io.conddo.core.domain.CreativeServiceRequest;
import io.conddo.core.service.CreativeServiceService;
import io.conddo.core.service.CreativeServiceService.CreateResult;
import io.conddo.core.service.CreativeServiceService.RequestView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * Tenant-facing creative services surface
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §5). Per §9 the marketplace is
 * available on Launcher + Growth + Scaler (pay-per-job revenue from
 * smallest tenants is desirable) — no plan gate.
 *
 * <p>The catalog ({@code GET /offerings}) is auth-only but otherwise open;
 * a Launcher tenant browsing the prices isn't a problem. The actual
 * request (and the RoutePay checkout) requires a connected RoutePay
 * sub-account on conddo-payments — that wiring is shared with the
 * existing booking-deposit flow.
 */
@RestController
@RequestMapping("/api/v1/creative-services")
@PreAuthorize("hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class CreativeServicesController {

    private final CreativeServiceService service;

    public CreativeServicesController(CreativeServiceService service) {
        this.service = service;
    }

    @GetMapping("/offerings")
    public ApiResponse<List<OfferingResponse>> offerings() {
        return ApiResponse.ok(service.catalog().stream()
                .map(CreativeServicesController::toOfferingResponse)
                .toList());
    }

    @PostMapping("/requests")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CreateResponse>> createRequest(
            @Valid @RequestBody CreateRequestBody body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        CreateResult created = service.createRequest(userId, body.offeringCode(), body.brief(),
                body.attachedMedia(), body.socialPostId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                new CreateResponse(toRequestResponse(created.request(), created.offering()),
                        created.checkoutUrl())));
    }

    @GetMapping("/requests")
    public ApiResponse<List<RequestResponse>> listRequests() {
        return ApiResponse.ok(service.listRequests().stream()
                .map(v -> toRequestResponse(v.request(), v.offering()))
                .toList());
    }

    @GetMapping("/requests/{id}")
    public ApiResponse<RequestResponse> getRequest(@PathVariable UUID id) {
        RequestView v = service.getRequest(id);
        return ApiResponse.ok(toRequestResponse(v.request(), v.offering()));
    }

    // ----- DTOs --------------------------------------------------------------

    public record CreateRequestBody(
            @NotBlank String offeringCode,
            @NotBlank String brief,
            List<UUID> attachedMedia,
            UUID socialPostId) {
    }

    public record CreateResponse(RequestResponse request, String checkoutUrl) {
    }

    public record OfferingResponse(String code, String name, String description,
                                   int priceKobo, int turnaroundHours, String jobType) {
    }

    public record RequestResponse(UUID id, String status, OfferingResponse offering, String brief,
                                  List<UUID> attachedMedia, UUID socialPostId, int priceKobo,
                                  String paymentReference, UUID studioJobId, String studioJobNumber,
                                  List<?> deliveryMedia, OffsetDateTime deliveredAt,
                                  OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private static OfferingResponse toOfferingResponse(CreativeServiceOffering o) {
        return new OfferingResponse(o.getCode(), o.getName(), o.getDescription(),
                o.getPriceKobo(), o.getTurnaroundHours(), o.getJobType());
    }

    private static RequestResponse toRequestResponse(CreativeServiceRequest r,
                                                     CreativeServiceOffering offering) {
        return new RequestResponse(r.getId(), r.getStatus(),
                offering == null ? null : toOfferingResponse(offering),
                r.getBrief(), r.getAttachedMedia(), r.getSocialPostId(), r.getPriceKobo(),
                r.getPaymentReference(), r.getStudioJobId(), r.getStudioJobNumber(),
                r.getDeliveryMedia(), r.getDeliveredAt(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
