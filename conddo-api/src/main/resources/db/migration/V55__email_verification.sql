-- Email verification (Onboarding v2, post-onboarding link).
--
-- Adds three columns to users:
--   email_verified                bool  — whether the user has clicked the
--                                        link we emailed after tenant provisioning.
--                                        Defaults FALSE for new rows; existing rows are
--                                        backfilled TRUE (grandfathered — they signed
--                                        up via the OTP path which already proved the
--                                        email exists).
--   email_verification_token_hash text — BCrypt hash of the token embedded in the
--                                        verification link. NULL when no link is
--                                        outstanding.
--   email_verification_expires_at timestamptz — link lifetime.
--
-- Also adds a partial index on the token hash so the /auth/verify-email
-- lookup is fast without bloating the general users index footprint.

ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verification_token_hash TEXT,
    ADD COLUMN email_verification_expires_at TIMESTAMPTZ;

-- Grandfather existing users: they either went through OTP verification or
-- were invited by an already-verified admin. Either way, forcing them to
-- click a fresh verify link would be a regression, not a security win.
UPDATE users SET email_verified = TRUE;

-- Partial index — only rows with a live token get indexed.
CREATE INDEX users_email_verification_token_hash_idx
    ON users (email_verification_token_hash)
    WHERE email_verification_token_hash IS NOT NULL;
