-- =====================================================================
-- V72 — Bookable services catalogue.
--
-- Before this table, the public /book/{slug} form let a customer type
-- arbitrary text into a "what for?" field. That gave the tenant no
-- structure to plan around and no price signal to the customer.
--
-- Now: the tenant defines a small menu of services (haircut, tuning,
-- consultation, etc.). Each has a display name, an optional description,
-- a duration in minutes (drives slot length + calendar block width),
-- and a price in kobo. The customer picks one on the public page;
-- everything else — end time, price, notes — is derived from the
-- service row.
--
-- Prices are stored in kobo (₦1 = 100 kobo) to match Paystack + the
-- rest of the codebase. Duration is minutes; slots snap to it.
--
-- No ON DELETE CASCADE from tenants → services because tenants are
-- soft-deleted (V71). A hard tenant purge is a manual op.
-- =====================================================================

CREATE TABLE bookable_services (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants(id),

    name              TEXT NOT NULL,
    description       TEXT,
    duration_minutes  INTEGER NOT NULL CHECK (duration_minutes > 0),
    price_kobo        BIGINT  NOT NULL DEFAULT 0 CHECK (price_kobo >= 0),

    active            BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order        INTEGER NOT NULL DEFAULT 0,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bookable_services_tenant
    ON bookable_services (tenant_id)
    WHERE active = TRUE;

-- Standard tenant isolation policy — mirrors the shape used across
-- V56+ tables so the public resolver and cross-tenant admin carve-outs
-- both work.
ALTER TABLE bookable_services ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bookable_services
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true'
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

GRANT SELECT, INSERT, UPDATE, DELETE ON bookable_services TO ${app_role};
