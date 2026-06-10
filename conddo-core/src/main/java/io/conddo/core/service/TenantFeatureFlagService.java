package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.TenantFeatureFlag;
import io.conddo.core.features.FeatureCatalogue;
import io.conddo.core.repository.TenantFeatureFlagRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pharmacy Roadmap — tenant feature flag lifecycle. Two surfaces:
 * <ul>
 *   <li>{@link #listForCurrentTenant} merges the {@link
 *       FeatureCatalogue} (every known key) with the calling tenant's
 *       interaction rows so the dashboard can render every locked card
 *       even on a fresh signup.</li>
 *   <li>{@link #registerInterest} / {@link #requestBetaAccess} flip
 *       the same {@code interest} bit (the spec doesn't differentiate
 *       at this layer — the FE labels the button differently per
 *       Beta vs Coming Soon) on a per-tenant row.</li>
 *   <li>{@link #grantAccess} is the SUPER_ADMIN side; flips
 *       {@code enabled = true} so the corresponding Beta endpoint
 *       stops returning 403 {@code FEATURE_NOT_ENABLED}.</li>
 *   <li>{@link #isEnabled} is the runtime gate every Beta endpoint
 *       will call once they land in code.</li>
 * </ul>
 */
@Service
public class TenantFeatureFlagService {

    private final TenantFeatureFlagRepository repository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public TenantFeatureFlagService(TenantFeatureFlagRepository repository,
                                    TenantSession tenantSession,
                                    Clock clock) {
        this.repository = repository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    /**
     * Returns one entry per catalogue key. Merges the canonical status
     * + any per-tenant interaction (interest / enabled / grant
     * timestamps). Catalogue order is preserved so the FE renders
     * cards in a stable order across loads.
     */
    @Transactional(readOnly = true)
    public List<FlagView> listForCurrentTenant() {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        Map<String, TenantFeatureFlag> byKey = new HashMap<>();
        for (TenantFeatureFlag row : repository.findByTenantId(tenantId)) {
            byKey.put(row.getFeatureKey(), row);
        }
        List<FlagView> out = new ArrayList<>();
        for (Map.Entry<String, FeatureCatalogue.Status> entry : FeatureCatalogue.all().entrySet()) {
            String key = entry.getKey();
            FeatureCatalogue.Status status = entry.getValue();
            TenantFeatureFlag row = byKey.get(key);
            out.add(new FlagView(
                    key,
                    status.wire(),
                    row != null && row.isEnabled(),
                    row != null && row.isInterest(),
                    row == null ? null : row.getInterestAt(),
                    row == null ? null : row.getGrantedAt(),
                    row == null ? null : row.getGrantedBy()));
        }
        return out;
    }

    @Transactional
    public TenantFeatureFlag registerInterest(String featureKey) {
        return interestStamp(featureKey, false);
    }

    @Transactional
    public TenantFeatureFlag requestBetaAccess(String featureKey) {
        return interestStamp(featureKey, true);
    }

    private TenantFeatureFlag interestStamp(String featureKey, boolean expectBeta) {
        if (!FeatureCatalogue.isKnown(featureKey)) {
            throw new IllegalArgumentException("Unknown feature key: " + featureKey);
        }
        FeatureCatalogue.Status canonical = FeatureCatalogue.statusOf(featureKey);
        if (expectBeta && canonical != FeatureCatalogue.Status.BETA) {
            throw new IllegalArgumentException(
                    "Beta access can only be requested for beta features (status of '"
                            + featureKey + "' is " + canonical.wire() + ")");
        }
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        TenantFeatureFlag row = repository.findByTenantIdAndFeatureKey(tenantId, featureKey)
                .orElseGet(() -> new TenantFeatureFlag(tenantId, featureKey, canonical.wire()));
        row.recordInterest(OffsetDateTime.now(clock));
        // Keep status in sync with the catalogue in case the canonical
        // stage has moved since the row was first created.
        row.setStatus(canonical.wire());
        return repository.save(row);
    }

    /**
     * SUPER_ADMIN action — grant a tenant access to a Beta feature.
     * Runs cross-tenant; caller should already be gated by role.
     */
    @Transactional
    public TenantFeatureFlag grantAccess(UUID tenantId, String featureKey, UUID grantedBy) {
        if (!FeatureCatalogue.isKnown(featureKey)) {
            throw new IllegalArgumentException("Unknown feature key: " + featureKey);
        }
        tenantSession.bindCrossTenant();
        TenantFeatureFlag row = repository.findByTenantIdAndFeatureKey(tenantId, featureKey)
                .orElseGet(() -> new TenantFeatureFlag(tenantId, featureKey,
                        FeatureCatalogue.statusOf(featureKey).wire()));
        row.grant(grantedBy, OffsetDateTime.now(clock));
        return repository.save(row);
    }

    @Transactional
    public TenantFeatureFlag revokeAccess(UUID tenantId, String featureKey) {
        tenantSession.bindCrossTenant();
        TenantFeatureFlag row = repository.findByTenantIdAndFeatureKey(tenantId, featureKey)
                .orElseThrow(() -> new NotFoundException("Feature flag not found"));
        row.revoke();
        return repository.save(row);
    }

    /**
     * Runtime gate — does this tenant have access to {@code featureKey}?
     * {@code live} features always return true (the catalogue is the
     * authority); beta + coming_soon require an enabled row.
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID tenantId, String featureKey) {
        FeatureCatalogue.Status canonical = FeatureCatalogue.statusOf(featureKey);
        if (canonical == FeatureCatalogue.Status.LIVE) {
            return true;
        }
        tenantSession.bindCrossTenant();
        return repository.findByTenantIdAndFeatureKey(tenantId, featureKey)
                .map(TenantFeatureFlag::isEnabled)
                .orElse(false);
    }

    public record FlagView(String featureKey,
                           String status,
                           boolean enabled,
                           boolean interest,
                           OffsetDateTime interestAt,
                           OffsetDateTime grantedAt,
                           UUID grantedBy) {
    }
}
