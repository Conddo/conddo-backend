-- =====================================================================
-- Tenant Website Integration Phase 1.5 — staff/admin RLS carve-out.
--
-- Phase 1 (V25) shipped tenant_sites with two access paths:
--   1. tenant_id == app.tenant_id  → the dashboard's own read/write
--   2. app.public_resolver=true    → the public traffic resolver (SELECT-only)
--
-- Phase 1.5 adds the staff/admin path for the QA-approval flow. A
-- SUPER_ADMIN reviewing a submitted site has no tenant bound on their JWT,
-- so the existing policies would deny every read and reject every UPDATE.
-- A third carve-out — set transactionally in TenantSession.bindCrossTenant()
-- before any admin service method runs — widens both USING and WITH CHECK.
--
-- The flag is intentionally separate from app.public_resolver: the public
-- carve-out is SELECT-only (anyone can resolve a subdomain), the admin
-- carve-out covers writes (only the dashboard SUPER_ADMIN flow flips it).
-- =====================================================================

DROP POLICY tenant_isolation ON tenant_sites;

CREATE POLICY tenant_isolation ON tenant_sites
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.public_resolver', true) = 'true'
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
