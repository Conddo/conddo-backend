-- =====================================================================
-- V51 — Per-tenant module opt-in/opt-out (Vertical Inference Phase B).
--
-- Today a tenant's active modules are derived purely from their
-- vertical × plan tier (VerticalToolMatrix). That makes vertical a
-- rigid filter — a pharmacy that also runs a small fashion line, or
-- a fashion shop that wants to try POS, has no way to opt in.
--
-- This table records per-tenant overrides on top of the matrix:
--   enabled=true  — opt INTO a module not in the vertical default
--   enabled=false — opt OUT of a module in the vertical default
-- ModuleResolver combines the matrix default + these overrides to
-- produce the final activeModules set that powers the JWT claim and
-- the dashboard manifest.
-- =====================================================================

CREATE TABLE tenant_module_overrides (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    module_id  VARCHAR(80)  NOT NULL,
    enabled    BOOLEAN      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, module_id)
);

CREATE INDEX idx_module_overrides_tenant
    ON tenant_module_overrides (tenant_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_module_overrides TO ${app_role};

ALTER TABLE tenant_module_overrides ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenant_module_overrides
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
