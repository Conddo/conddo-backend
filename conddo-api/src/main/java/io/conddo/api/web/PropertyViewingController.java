package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PropertyViewing;
import io.conddo.core.repository.PropertyViewingRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Real estate vertical — property viewings module.
 */
@RestController
@RequestMapping("/api/v1/viewings")
public class PropertyViewingController {

    private static final String READ = "@staffAccess.canRead('viewings')";
    private static final String WRITE = "@staffAccess.canWrite('viewings')";

    private final PropertyViewingRepository repository;
    private final TenantSession tenantSession;

    public PropertyViewingController(PropertyViewingRepository repository, TenantSession tenantSession) {
        this.repository = repository;
        this.tenantSession = tenantSession;
    }

    @GetMapping("/upcoming")
    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public ApiResponse<List<PropertyViewing>> upcoming() {
        tenantSession.bind();
        return ApiResponse.ok(
                repository.findByScheduledAtGreaterThanEqualOrderByScheduledAtAsc(OffsetDateTime.now()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public ApiResponse<PropertyViewing> get(@PathVariable UUID id) {
        tenantSession.bind();
        PropertyViewing v = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Viewing not found"));
        return ApiResponse.ok(v);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @Transactional
    public ResponseEntity<ApiResponse<PropertyViewing>> create(@Valid @RequestBody CreateViewingRequest req) {
        tenantSession.bind();
        PropertyViewing v = new PropertyViewing(TenantContext.require(), req.propertyId(),
                req.prospectName(), req.scheduledAt());
        if (req.prospectPhone() != null) v.setProspectPhone(req.prospectPhone());
        if (req.prospectEmail() != null) v.setProspectEmail(req.prospectEmail());
        if (req.agentId() != null) v.setAgentId(req.agentId());
        if (req.dealId() != null) v.setDealId(req.dealId());
        if (req.customerId() != null) v.setCustomerId(req.customerId());
        if (req.partySize() != null) v.setPartySize(req.partySize());
        if (req.durationMinutes() != null) v.setDurationMinutes(req.durationMinutes());
        if (req.notes() != null) v.setNotes(req.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(repository.save(v)));
    }

    @PatchMapping("/{id}/confirm")
    @PreAuthorize(WRITE)
    @Transactional
    public ApiResponse<PropertyViewing> confirm(@PathVariable UUID id) {
        tenantSession.bind();
        PropertyViewing v = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Viewing not found"));
        v.confirm(OffsetDateTime.now());
        return ApiResponse.ok(repository.save(v));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    public ApiResponse<PropertyViewing> cancel(@PathVariable UUID id) {
        tenantSession.bind();
        PropertyViewing v = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Viewing not found"));
        v.cancel(OffsetDateTime.now());
        return ApiResponse.ok(repository.save(v));
    }

    @PatchMapping("/{id}/complete")
    @PreAuthorize(WRITE)
    @Transactional
    public ApiResponse<PropertyViewing> complete(@PathVariable UUID id,
                                                  @Valid @RequestBody CompleteViewingRequest req) {
        tenantSession.bind();
        PropertyViewing v = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Viewing not found"));
        v.complete(req.outcome(), req.notes());
        return ApiResponse.ok(repository.save(v));
    }

    // ----- DTOs -------------------------------------------------------------

    public record CreateViewingRequest(
            @NotNull UUID propertyId,
            @NotBlank String prospectName,
            @NotNull OffsetDateTime scheduledAt,
            String prospectPhone, String prospectEmail,
            UUID agentId, UUID dealId, UUID customerId,
            Integer partySize, Integer durationMinutes,
            String notes
    ) {
    }

    public record CompleteViewingRequest(String outcome, String notes) {
    }
}
