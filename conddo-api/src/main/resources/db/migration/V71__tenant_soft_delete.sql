-- =====================================================================
-- V71 — Soft-delete for tenants.
--
-- Adds `tenants.deleted_at` so a platform admin can retire a tenant
-- without cascading destruction across the 70+ dependent tables. The
-- row + every child stays intact; the admin surface hides deleted
-- tenants by default and every session-issuing path treats a deleted
-- tenant as "unauthenticated".
--
-- Hard-purge (actual DELETE) is deliberately NOT introduced here. Most
-- tenant FKs in this schema do not carry ON DELETE CASCADE (58 of 72
-- as of V70), so a raw delete would fail at the first non-cascading
-- child. Hard-purge lands in a future migration that adds the cascades,
-- gated on operational need (GDPR request, test-tenant cleanup).
--
-- Behaviour:
--   - deleted_at IS NULL     → live tenant, full access
--   - deleted_at IS NOT NULL → soft-deleted, hidden from admin lists,
--                              sign-in refused, invite tokens invalid
--
-- Recovery: null out deleted_at. All children were preserved so the
-- tenant is restored to its previous state.
-- =====================================================================

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- Partial index — every read that filters live tenants uses
-- `WHERE deleted_at IS NULL`. Partial keeps the index small since
-- most rows should have deleted_at = NULL forever.
CREATE INDEX IF NOT EXISTS tenants_live_idx
    ON tenants (created_at DESC)
    WHERE deleted_at IS NULL;
