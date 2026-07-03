-- V58 — Per-registration classify attempt counter (PR 2f, abuse guards).
--
-- Every call to POST /auth/register/classify hits the AI provider (OpenRouter
-- → DeepSeek). Without a cap, an attacker sitting on the /processing screen
-- could refresh 1,000 times per second and burn our OpenRouter tokens
-- without ever creating a tenant. Cap it at 5 per registration.
ALTER TABLE pending_registrations
    ADD COLUMN classify_attempts INTEGER NOT NULL DEFAULT 0;
