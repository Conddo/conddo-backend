-- =====================================================================
-- Tenant Website Integration Phase 1 (WEBSITE_INTEGRATION_SPEC.md).
--
-- Storage shape deviates from the source product doc: api_key_hash
-- (bcrypt) NOT plaintext. Plaintext only exists in-memory at the moment
-- of generation; subsequent reads return only the masked form
-- (`sk_live_••••••••` + last 4 chars). Same pattern AWS / Stripe use.
--
-- One row per tenant for now. RLS is enforced because tenant dashboards
-- read /api/v1/website/site — RLS scopes the read to "this tenant's row".
-- =====================================================================

CREATE TABLE tenant_sites (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants (id),

    subdomain         VARCHAR(100) UNIQUE,
    custom_domain     VARCHAR(255) UNIQUE,

    hosting_provider  VARCHAR(50),                    -- conddo | vercel | 9stacks
    site_type         VARCHAR(50),                    -- custom_built | template

    api_key_hash      VARCHAR(255) NOT NULL,          -- bcrypt of the key
    api_key_last4     VARCHAR(4)   NOT NULL,          -- for masked display
    is_active         BOOLEAN      NOT NULL DEFAULT false,
    qa_approved       BOOLEAN      NOT NULL DEFAULT false,
    qa_approved_by    UUID         REFERENCES staff_users (id),
    qa_approved_at    TIMESTAMPTZ,
    submitted_url     VARCHAR(500),

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One site per tenant for now.
CREATE UNIQUE INDEX idx_tenant_sites_tenant ON tenant_sites (tenant_id);

-- Public traffic resolves by subdomain; bcrypt-compare happens after lookup.
CREATE INDEX idx_tenant_sites_active
    ON tenant_sites (subdomain)
    WHERE is_active = true AND qa_approved = true;

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_sites TO ${app_role};

ALTER TABLE tenant_sites ENABLE ROW LEVEL SECURITY;

-- Dashboard reads/writes are tenant-scoped via app.tenant_id (the usual RLS
-- path). The public-traffic resolver in TenantSiteService runs BEFORE a tenant
-- context is bound — the whole point of the lookup is to find which tenant
-- owns the requested subdomain. The carve-out: when app.public_resolver is
-- 'true' the SELECT is permitted; WITH CHECK still requires a bound tenant_id
-- so the resolver path can only READ, never INSERT/UPDATE.
CREATE POLICY tenant_isolation ON tenant_sites
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
