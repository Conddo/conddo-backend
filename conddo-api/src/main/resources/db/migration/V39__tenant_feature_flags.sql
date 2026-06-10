-- =====================================================================
-- V39 — Pharmacy Roadmap §"Feature Flag System": tenant-scoped flags
-- for Beta + Coming Soon features.
--
-- A row exists per (tenant, feature_key) the moment the tenant
-- interacts — either clicks "Notify me when ready" (creates a row
-- with enabled=false, interest=true) or "Request Beta Access"
-- (interest=true, awaiting Conddo team grant). When the Conddo team
-- grants access, enabled flips to true with granted_at/granted_by
-- stamped.
--
-- {@code status} is denormalised from the catalogue (carried per row
-- per the spec) so a downstream change of stage can be migrated by a
-- simple UPDATE without touching the FE wire shape.
-- =====================================================================

CREATE TABLE tenant_feature_flags (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    feature_key     VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'coming_soon'
        CHECK (status IN ('live', 'beta', 'coming_soon')),
    interest        BOOLEAN      NOT NULL DEFAULT false,
    enabled         BOOLEAN      NOT NULL DEFAULT false,
    interest_at     TIMESTAMPTZ,
    granted_at      TIMESTAMPTZ,
    granted_by      UUID,
    UNIQUE (tenant_id, feature_key)
);

CREATE INDEX idx_feature_flags_tenant
    ON tenant_feature_flags (tenant_id);
CREATE INDEX idx_feature_flags_key_enabled
    ON tenant_feature_flags (feature_key, enabled);

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_feature_flags TO ${app_role};

ALTER TABLE tenant_feature_flags ENABLE ROW LEVEL SECURITY;

-- Tenant isolation + cross_tenant carve-out so SUPER_ADMIN grants can
-- update other tenants' rows without binding to that tenant first.
CREATE POLICY tenant_isolation ON tenant_feature_flags
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
