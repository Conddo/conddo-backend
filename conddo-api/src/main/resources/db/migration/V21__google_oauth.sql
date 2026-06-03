-- =====================================================================
-- Google Sign-in (Slice H / ACTION_LIST §1a). Adds the columns needed
-- to authenticate a tenant user via a Google ID token alongside (or
-- instead of) the existing email + password credential.
--
-- google_sub is Google's immutable opaque subject id — never the email,
-- which a user can change. Globally UNIQUE because Google issues one
-- sub per Google account; that account can be linked to at most one
-- platform user (per-tenant lookup is via the existing tenant-scoped
-- RLS policy on users).
-- =====================================================================

ALTER TABLE users
    ADD COLUMN google_sub        TEXT,
    ADD COLUMN google_email      TEXT,
    ADD COLUMN google_linked_at  TIMESTAMPTZ;

-- Unique only where present — a user with no Google link is the norm.
CREATE UNIQUE INDEX idx_users_google_sub
    ON users (google_sub) WHERE google_sub IS NOT NULL;

-- pending_registrations stashes the Google sub between start-google and
-- complete, so the platform user that gets created at completion has it
-- written in atomically (no second-step "link" race).
ALTER TABLE pending_registrations
    ADD COLUMN google_sub TEXT;
