-- =====================================================================
-- V50 — One email = one account. Tightens the per-tenant UNIQUE on
-- (tenant_id, email) to a global UNIQUE on (email).
--
-- A duplicate email across two tenants meant a stranger could sign up
-- a business name with someone else's email and the platform would
-- accept it. Closes that.
--
-- Strategy:
--   1. Find email duplicates. Keep the earliest user row (oldest
--      created_at) as the canonical owner of the email.
--   2. Rename the email on the other rows to
--      <localPart>+legacy-<userId>@<domain> so the rows stay
--      addressable + auditable but no longer collide. The renamed
--      accounts can't log in until the owner moves them to a real
--      address (they were already collisions, so login was already
--      ambiguous in practice).
--   3. Drop the old (tenant_id, email) UNIQUE; add the global UNIQUE
--      on (email).
-- =====================================================================

UPDATE users u
   SET email = SUBSTRING(email FROM '^[^@]+')
                || '+legacy-' || u.id::text
                || '@' || SUBSTRING(email FROM '@(.+)$')
 WHERE EXISTS (
   SELECT 1 FROM users e
    WHERE e.email = u.email
      AND e.id <> u.id
      AND e.created_at < u.created_at
 );

ALTER TABLE users DROP CONSTRAINT users_tenant_id_email_key;
ALTER TABLE users ADD CONSTRAINT users_email_key UNIQUE (email);
