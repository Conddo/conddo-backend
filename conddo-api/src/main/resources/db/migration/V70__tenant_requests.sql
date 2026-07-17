-- =====================================================================
-- V70 — Tenant support requests (feature requests, complaints, bugs, questions).
--
-- Tenants submit through /settings/support. Platform staff triage from
-- studio.getconddo.com/admin/requests. Every row is tenant-scoped via RLS
-- for the tenant-side surface; the admin surface uses the cross-tenant
-- carve-out (app.cross_tenant='true') that our @TenantScoped aspect owns.
--
-- Kept intentionally lean: no attachments, no threading (one reply per
-- request from the platform side). Both can land as follow-up columns
-- once the ticket count justifies the schema growth.
-- =====================================================================

CREATE TABLE tenant_requests (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,

    -- Who filed it (nullable so a request survives a user's deletion).
    created_by        UUID          REFERENCES users (id) ON DELETE SET NULL,

    kind              VARCHAR(20)   NOT NULL,
    title             VARCHAR(200)  NOT NULL,
    body              TEXT          NOT NULL,

    status            VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    -- LOW / NORMAL / HIGH — set by the admin, defaults to NORMAL. Deliberately
    -- not offered to the tenant to avoid every request coming in as HIGH.
    priority          VARCHAR(10)   NOT NULL DEFAULT 'NORMAL',

    -- Admin reply. One reply per request in this version. If we ever need
    -- multi-turn threading, add a tenant_request_comments table rather than
    -- widening this row.
    admin_response    TEXT,
    responded_by      UUID          REFERENCES staff_users (id) ON DELETE SET NULL,
    responded_at      TIMESTAMPTZ,

    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT tenant_requests_kind_valid
        CHECK (kind IN ('FEATURE','COMPLAINT','BUG','QUESTION')),
    CONSTRAINT tenant_requests_status_valid
        CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','DISMISSED')),
    CONSTRAINT tenant_requests_priority_valid
        CHECK (priority IN ('LOW','NORMAL','HIGH'))
);

CREATE INDEX tenant_requests_tenant_created_idx
    ON tenant_requests (tenant_id, created_at DESC);

-- Admin panel filters by status + kind + created_at DESC (latest first).
-- Composite so the "OPEN + latest" view seeks in one index.
CREATE INDEX tenant_requests_status_created_idx
    ON tenant_requests (status, created_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_requests TO ${app_role};

ALTER TABLE tenant_requests ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenant_requests
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
