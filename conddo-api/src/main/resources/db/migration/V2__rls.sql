-- =====================================================================
-- V2 — Row Level Security (PRD §6.1, §12.2)
--
-- Run by Flyway as the OWNER role. Grants runtime privileges to the
-- application role and enables tenant-isolation policies. The application
-- connects as ${app_role} (a NON-owner role), so these policies are
-- enforced by PostgreSQL itself and cannot be bypassed from app code.
--
-- ${app_role} is a Flyway placeholder, resolved from CONDDO_DB_APP_USER
-- (see application.yml -> spring.flyway.placeholders.app_role).
-- =====================================================================

-- Runtime DML privileges for the application role.
GRANT SELECT, INSERT, UPDATE, DELETE ON tenants        TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON users          TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_tokens TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON audit_log      TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON customers      TO ${app_role};

-- Enable RLS on tenant-scoped tables.
ALTER TABLE users     ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;

-- Tenant-isolation policy.
--   app.tenant_id is a per-transaction GUC the application sets via
--   SELECT set_config('app.tenant_id', '<uuid>', true).
--   current_setting(..., true) returns NULL when unset, so a request with
--   no tenant context matches NO rows — isolation fails closed, never open.
CREATE POLICY tenant_isolation ON customers
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE POLICY tenant_isolation ON users
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- NOTE: refresh_tokens and audit_log follow the same pattern when their
-- access paths are built out; left without RLS in Phase 0 as they are not
-- yet exercised by the API.
