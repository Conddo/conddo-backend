-- =====================================================================
-- V49 — Staff invite acceptance (HANDOFF_2026-06-12 §5).
--
-- Adds the invite-token lifecycle to `users`:
--   status                  — 'INVITED' until accept-invite, then 'ACTIVE'
--   invite_token_hash       — SHA-256 hex of the raw token (raw never stored)
--   invite_token_expires_at — TTL (72h per handoff)
--   invited_by_user_id      — who sent the invite (for preview "invited by X")
--
-- All four are NULL on accept and on pre-existing rows.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INVITED')),
    ADD COLUMN invite_token_hash       VARCHAR(80),
    ADD COLUMN invite_token_expires_at TIMESTAMPTZ,
    ADD COLUMN invited_by_user_id      UUID;

-- Backfill: every existing row is ACTIVE (the column default), no
-- token. Future invites mint a token + flip to INVITED in one
-- transaction.
UPDATE users SET status = 'ACTIVE' WHERE status IS NULL;

-- Token hash is unique when present (a hash collision would mean a
-- compromised invite). Partial index so non-invite rows don't
-- contend on it.
CREATE UNIQUE INDEX uq_users_invite_token_hash
    ON users (invite_token_hash)
    WHERE invite_token_hash IS NOT NULL;

CREATE INDEX idx_users_status_invited
    ON users (tenant_id, status)
    WHERE status = 'INVITED';

-- Extend the users RLS policy with the cross_tenant carve-out so the
-- pre-auth invite-acceptance flow (no JWT, no bound tenant) can look
-- up a row by globally-unique invite_token_hash. Matches the pattern
-- V26+ added for newer tables.
DROP POLICY tenant_isolation ON users;
CREATE POLICY tenant_isolation ON users
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
