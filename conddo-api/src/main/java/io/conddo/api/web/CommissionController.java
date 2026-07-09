package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.CommissionEntry;
import io.conddo.core.repository.CommissionEntryRepository;
import io.conddo.core.tenant.TenantSession;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Real estate vertical — commission entries + payout marking.
 */
@RestController
@RequestMapping("/api/v1/commissions")
public class CommissionController {

    private static final String READ = "@staffAccess.canRead('commissions')";
    private static final String WRITE = "@staffAccess.canWrite('commissions')";

    private final CommissionEntryRepository repository;
    private final TenantSession tenantSession;

    public CommissionController(CommissionEntryRepository repository, TenantSession tenantSession) {
        this.repository = repository;
        this.tenantSession = tenantSession;
    }

    /** Outstanding + paid entries, optionally filtered by agent. */
    @GetMapping
    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public ApiResponse<List<CommissionEntry>> list(
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String status) {
        tenantSession.bind();
        if (agentId != null) {
            return ApiResponse.ok(repository.findByAgentIdOrderByAccruedAtDesc(agentId));
        }
        if (status != null && !status.isBlank()) {
            return ApiResponse.ok(repository.findByStatusOrderByAccruedAtDesc(status));
        }
        return ApiResponse.ok(repository.findAll());
    }

    /** Mark a commission entry paid — records the reference number the
     *  tenant used to transfer. */
    @PatchMapping("/{id}/paid")
    @PreAuthorize(WRITE)
    @Transactional
    public ApiResponse<CommissionEntry> markPaid(@PathVariable UUID id,
                                                  @Valid @RequestBody MarkPaidRequest req) {
        tenantSession.bind();
        CommissionEntry entry = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Commission entry not found"));
        entry.markPaidOut(req.paymentReference(), OffsetDateTime.now());
        return ApiResponse.ok(repository.save(entry));
    }

    /** Reverse — used when a deal falls through after deposit. */
    @PatchMapping("/{id}/reverse")
    @PreAuthorize(WRITE)
    @Transactional
    public ApiResponse<CommissionEntry> reverse(@PathVariable UUID id,
                                                 @Valid @RequestBody ReverseRequest req) {
        tenantSession.bind();
        CommissionEntry entry = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Commission entry not found"));
        entry.reverse(req.reason(), OffsetDateTime.now());
        return ApiResponse.ok(repository.save(entry));
    }

    public record MarkPaidRequest(String paymentReference) {
    }

    public record ReverseRequest(String reason) {
    }
}
