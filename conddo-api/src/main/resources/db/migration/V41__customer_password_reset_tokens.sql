-- =====================================================================
-- V41 — Customer-side password reset tokens (PHARMACY_PUBLIC_API_SPEC
-- §2 forgot-password / reset-password).
--
-- Distinct from the staff/admin password_reset_tokens table because
-- customers live in a separate identity scope (per-tenant). Tokens
-- are looked up unauthenticated by selector, so RLS is OFF — every
-- row carries its own tenant_id which the service binds before
-- updating the customer row.
-- =====================================================================

CREATE TABLE customer_password_reset_tokens (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   UUID         NOT NULL REFERENCES customers (id),
    tenant_id     UUID         NOT NULL REFERENCES tenants (id),
    selector      VARCHAR(64)  NOT NULL UNIQUE,
    token_hash    TEXT         NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_pw_reset_customer
    ON customer_password_reset_tokens (customer_id, created_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON customer_password_reset_tokens TO ${app_role};
