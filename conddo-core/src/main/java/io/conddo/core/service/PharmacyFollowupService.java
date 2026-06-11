package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PharmacyFollowup;
import io.conddo.core.repository.PharmacyFollowupRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Clinical follow-up lifecycle (Pharmacy Roadmap Beta 2). The
 * pharmacist schedules a check, Conddo reminds them on the due date,
 * they record the outcome. Completion auto-appends an EMR note when
 * the patient has a {@code pharmacy_emr} row (Beta 4); when EMR
 * isn't built yet the auto-append is a no-op — controlled by the
 * absence of any EMR row, not a feature flag.
 */
@Service
public class PharmacyFollowupService {

    private static final Logger log = LoggerFactory.getLogger(PharmacyFollowupService.class);

    /** A follow-up is "due today" if its due_date falls in the next 24h. */
    private static final Duration DUE_TODAY_WINDOW = Duration.ofHours(24);

    /** PENDING + due_date older than this becomes MISSED on the cron. */
    private static final Duration MISSED_THRESHOLD = Duration.ofHours(48);

    private final PharmacyFollowupRepository repository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PharmacyFollowupService(PharmacyFollowupRepository repository,
                                   TenantSession tenantSession,
                                   Clock clock) {
        this.repository = repository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    @Transactional
    public PharmacyFollowup create(UUID customerId, UUID orderId, UUID productId,
                                    OffsetDateTime dueDate, String checkNote, UUID createdBy) {
        tenantSession.bind();
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (dueDate == null) {
            throw new IllegalArgumentException("dueDate is required");
        }
        if (checkNote == null || checkNote.isBlank()) {
            throw new IllegalArgumentException("checkNote is required");
        }
        return repository.save(new PharmacyFollowup(TenantContext.require(), customerId,
                orderId, productId, dueDate, checkNote, createdBy));
    }

    @Transactional(readOnly = true)
    public Page<PharmacyFollowup> list(String status, UUID customerId, Pageable pageable) {
        tenantSession.bind();
        Specification<PharmacyFollowup> spec = (root, query, cb) -> {
            Predicate predicate = cb.conjunction();
            if (status != null && !status.isBlank()) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }
            if (customerId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("customerId"), customerId));
            }
            return predicate;
        };
        return repository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public List<PharmacyFollowup> dueToday() {
        tenantSession.bind();
        OffsetDateTime now = OffsetDateTime.now(clock);
        return repository.findDueWithin(now, now.plus(DUE_TODAY_WINDOW));
    }

    @Transactional
    public PharmacyFollowup complete(UUID id, String outcome, String outcomeType, UUID completedBy) {
        tenantSession.bind();
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("outcome is required");
        }
        if (outcomeType == null || !isValidOutcomeType(outcomeType)) {
            throw new IllegalArgumentException("outcomeType must be one of: "
                    + "RECOVERED | REFERRED | SIDE_EFFECT | NO_RESPONSE | OTHER");
        }
        PharmacyFollowup followup = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Follow-up not found"));
        if (!PharmacyFollowup.STATUS_PENDING.equals(followup.getStatus())
                && !PharmacyFollowup.STATUS_MISSED.equals(followup.getStatus())) {
            throw new IllegalArgumentException("Only PENDING or MISSED follow-ups can be completed");
        }
        followup.complete(outcome, outcomeType, completedBy, OffsetDateTime.now(clock));
        PharmacyFollowup saved = repository.save(followup);
        // Beta 4 hook — append an immutable EMR note onto the customer's
        // record if one exists. Implemented on a separate listener once
        // pharmacy_emr lands so this slice has no compile-time dep on
        // Beta 4.
        return saved;
    }

    @Transactional
    public PharmacyFollowup cancel(UUID id) {
        tenantSession.bind();
        PharmacyFollowup followup = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Follow-up not found"));
        if (!PharmacyFollowup.STATUS_PENDING.equals(followup.getStatus())) {
            throw new IllegalArgumentException("Only PENDING follow-ups can be cancelled");
        }
        followup.cancel();
        return repository.save(followup);
    }

    @Transactional(readOnly = true)
    public PharmacyFollowup get(UUID id) {
        tenantSession.bind();
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Follow-up not found"));
    }

    /**
     * Cross-tenant daily sweep — flips PENDING follow-ups whose
     * due_date passed 48h+ ago to MISSED. Pharmacist can still
     * complete a MISSED row (they ran late). Idempotent.
     */
    @Transactional
    public int sweepMissed() {
        tenantSession.bindCrossTenant();
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(MISSED_THRESHOLD);
        List<PharmacyFollowup> due = repository.findPendingPastCutoff(cutoff);
        for (PharmacyFollowup f : due) {
            f.markMissed();
            repository.save(f);
        }
        if (!due.isEmpty()) {
            log.info("Pharmacy follow-up sweep: {} flipped PENDING → MISSED", due.size());
        }
        return due.size();
    }

    private static boolean isValidOutcomeType(String type) {
        return "RECOVERED".equals(type) || "REFERRED".equals(type)
                || "SIDE_EFFECT".equals(type) || "NO_RESPONSE".equals(type)
                || "OTHER".equals(type);
    }
}
