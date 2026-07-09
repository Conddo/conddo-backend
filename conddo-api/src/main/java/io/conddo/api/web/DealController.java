package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Deal;
import io.conddo.core.repository.DealRepository;
import io.conddo.core.service.CommissionService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real estate vertical — deals module. Kanban-driven pipeline with a
 * few Nigerian-specific behaviors: commission_pct is per-deal (renegotiated
 * mid-flow); deposit_paid stage timestamps depositPaidAt automatically
 * (that's the commission trigger, not signature).
 */
@RestController
@RequestMapping("/api/v1/deals")
public class DealController {

    private static final String READ = "@staffAccess.canRead('deals')";
    private static final String WRITE = "@staffAccess.canWrite('deals')";

    private final DealRepository repository;
    private final TenantSession tenantSession;
    private final CommissionService commissionService;

    public DealController(DealRepository repository, TenantSession tenantSession,
                          CommissionService commissionService) {
        this.repository = repository;
        this.tenantSession = tenantSession;
        this.commissionService = commissionService;
    }

    /** Kanban board — grouped by stage. */
    @GetMapping("/board")
    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, List<DealRow>>> board() {
        tenantSession.bind();
        Map<String, List<DealRow>> byStage = new LinkedHashMap<>();
        // Seed with the canonical stage order so empty columns still render.
        for (String s : List.of(Deal.STAGE_LEAD, Deal.STAGE_VIEWING_SCHEDULED,
                Deal.STAGE_VIEWED, Deal.STAGE_OFFER_MADE, Deal.STAGE_DEPOSIT_PAID,
                Deal.STAGE_DOCUMENTATION, Deal.STAGE_SIGNED, Deal.STAGE_CLOSED,
                Deal.STAGE_LOST)) {
            byStage.put(s, new java.util.ArrayList<>());
        }
        for (Deal d : repository.findAllByOrderByStageChangedAtDesc()) {
            byStage.get(d.getStage()).add(DealRow.from(d));
        }
        return ApiResponse.ok(byStage);
    }

    /** Pipeline widget — count + value per stage. */
    @GetMapping("/pipeline")
    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public ApiResponse<List<Map<String, Object>>> pipeline() {
        tenantSession.bind();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (DealRepository.StageCount row : repository.countByStage()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stage", row.getStage());
            item.put("count", row.getCount());
            item.put("pipelineValue", row.getPipelineValue());
            out.add(item);
        }
        return ApiResponse.ok(out);
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public ApiResponse<DealDetail> get(@PathVariable UUID id) {
        tenantSession.bind();
        Deal deal = repository.findById(id).orElseThrow(() -> new NotFoundException("Deal not found"));
        return ApiResponse.ok(DealDetail.from(deal));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @Transactional
    public ResponseEntity<ApiResponse<DealDetail>> create(@Valid @RequestBody CreateDealRequest req) {
        tenantSession.bind();
        Deal deal = new Deal(TenantContext.require(), req.prospectName());
        req.applyTo(deal);
        deal.recomputeCommission();
        Deal saved = repository.save(deal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(DealDetail.from(saved)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    public ApiResponse<DealDetail> update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateDealRequest req) {
        tenantSession.bind();
        Deal deal = repository.findById(id).orElseThrow(() -> new NotFoundException("Deal not found"));
        req.applyTo(deal);
        deal.recomputeCommission();
        return ApiResponse.ok(DealDetail.from(repository.save(deal)));
    }

    @PatchMapping("/{id}/stage")
    @PreAuthorize(WRITE)
    @Transactional
    public ApiResponse<DealDetail> moveStage(@PathVariable UUID id,
                                             @Valid @RequestBody MoveStageRequest req) {
        tenantSession.bind();
        Deal deal = repository.findById(id).orElseThrow(() -> new NotFoundException("Deal not found"));
        if (Deal.STAGE_LOST.equals(req.stage())) {
            deal.markLost(req.reason(), OffsetDateTime.now());
        } else {
            deal.moveToStage(req.stage(), OffsetDateTime.now());
        }
        Deal saved = repository.save(deal);
        // Deposit-paid is the pivotal moment — accrue commission entries
        // for the agents involved. Idempotent (won't double-accrue).
        if (Deal.STAGE_DEPOSIT_PAID.equals(req.stage())) {
            commissionService.accrueForDeposit(saved);
        }
        return ApiResponse.ok(DealDetail.from(saved));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        tenantSession.bind();
        if (!repository.existsById(id)) {
            throw new NotFoundException("Deal not found");
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ----- DTOs -------------------------------------------------------------

    public record DealRow(UUID id, String prospectName, UUID propertyId, String stage,
                          BigDecimal dealValue, BigDecimal commissionAmount,
                          UUID primaryAgentId, OffsetDateTime stageChangedAt) {
        static DealRow from(Deal d) {
            return new DealRow(d.getId(), d.getProspectName(), d.getPropertyId(), d.getStage(),
                    d.getDealValue(), d.getCommissionAmount(), d.getPrimaryAgentId(),
                    d.getStageChangedAt());
        }
    }

    public record DealDetail(UUID id, UUID propertyId, UUID customerId,
                             String prospectName, String prospectPhone, String prospectEmail,
                             String stage, OffsetDateTime stageChangedAt,
                             BigDecimal dealValue, String currency,
                             BigDecimal commissionPct, BigDecimal commissionAmount,
                             BigDecimal depositAmount, OffsetDateTime depositPaidAt,
                             UUID primaryAgentId, UUID introducerAgentId,
                             String notes, String lostReason,
                             OffsetDateTime expectedCloseAt) {
        static DealDetail from(Deal d) {
            return new DealDetail(d.getId(), d.getPropertyId(), d.getCustomerId(),
                    d.getProspectName(), d.getProspectPhone(), d.getProspectEmail(),
                    d.getStage(), d.getStageChangedAt(),
                    d.getDealValue(), d.getCurrency(),
                    d.getCommissionPct(), d.getCommissionAmount(),
                    d.getDepositAmount(), d.getDepositPaidAt(),
                    d.getPrimaryAgentId(), d.getIntroducerAgentId(),
                    d.getNotes(), d.getLostReason(),
                    d.getExpectedCloseAt());
        }
    }

    public record CreateDealRequest(
            @NotBlank String prospectName,
            String prospectPhone, String prospectEmail,
            UUID propertyId, UUID customerId,
            BigDecimal dealValue, String currency,
            BigDecimal commissionPct, BigDecimal depositAmount,
            UUID primaryAgentId, UUID introducerAgentId,
            String notes, OffsetDateTime expectedCloseAt
    ) {
        void applyTo(Deal d) {
            if (prospectPhone != null) d.setProspectPhone(prospectPhone);
            if (prospectEmail != null) d.setProspectEmail(prospectEmail);
            if (propertyId != null) d.setPropertyId(propertyId);
            if (customerId != null) d.setCustomerId(customerId);
            if (dealValue != null) d.setDealValue(dealValue);
            if (currency != null) d.setCurrency(currency);
            if (commissionPct != null) d.setCommissionPct(commissionPct);
            if (depositAmount != null) d.setDepositAmount(depositAmount);
            if (primaryAgentId != null) d.setPrimaryAgentId(primaryAgentId);
            if (introducerAgentId != null) d.setIntroducerAgentId(introducerAgentId);
            if (notes != null) d.setNotes(notes);
            if (expectedCloseAt != null) d.setExpectedCloseAt(expectedCloseAt);
        }
    }

    public record UpdateDealRequest(
            String prospectName, String prospectPhone, String prospectEmail,
            UUID propertyId, UUID customerId,
            BigDecimal dealValue, String currency,
            BigDecimal commissionPct, BigDecimal depositAmount,
            UUID primaryAgentId, UUID introducerAgentId,
            String notes, OffsetDateTime expectedCloseAt
    ) {
        void applyTo(Deal d) {
            if (prospectName != null) d.setProspectName(prospectName);
            if (prospectPhone != null) d.setProspectPhone(prospectPhone);
            if (prospectEmail != null) d.setProspectEmail(prospectEmail);
            if (propertyId != null) d.setPropertyId(propertyId);
            if (customerId != null) d.setCustomerId(customerId);
            if (dealValue != null) d.setDealValue(dealValue);
            if (currency != null) d.setCurrency(currency);
            if (commissionPct != null) d.setCommissionPct(commissionPct);
            if (depositAmount != null) d.setDepositAmount(depositAmount);
            if (primaryAgentId != null) d.setPrimaryAgentId(primaryAgentId);
            if (introducerAgentId != null) d.setIntroducerAgentId(introducerAgentId);
            if (notes != null) d.setNotes(notes);
            if (expectedCloseAt != null) d.setExpectedCloseAt(expectedCloseAt);
        }
    }

    public record MoveStageRequest(@NotBlank String stage, String reason) {
    }
}
