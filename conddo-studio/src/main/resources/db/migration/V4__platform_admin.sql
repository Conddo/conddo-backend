-- =====================================================================
-- Conddo Studio — Platform Admin audit log (Infrastructure §23.4).
-- Studio ADMINs can manage every platform tenant + user from one console.
-- Every PATCH/DELETE on the platform-admin endpoints writes a row here so
-- there's a tamper-evident trail of who suspended whom and when.
--
-- The audit row is created in Phase 13a (this slice) even though no
-- mutators write to it yet — that lets Phase 13b ship its mutation code
-- without a second migration. GET endpoints intentionally do NOT audit
-- (the platform's existing audit_log already records every HTTP path).
-- =====================================================================

CREATE TABLE studio.platform_admin_audit_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id     UUID NOT NULL REFERENCES studio.staff(id),
    action       TEXT NOT NULL,           -- TENANT_SUSPEND / USER_ROLE_CHANGE / USER_PASSWORD_RESET / …
    target_kind  TEXT NOT NULL,           -- TENANT | USER
    target_id    UUID NOT NULL,
    before       JSONB,
    after        JSONB,
    correlation  TEXT,                    -- optional request id for join with Studio activity log
    at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_admin_audit_actor
    ON studio.platform_admin_audit_log (actor_id, at DESC);
CREATE INDEX idx_platform_admin_audit_target
    ON studio.platform_admin_audit_log (target_kind, target_id, at DESC);
