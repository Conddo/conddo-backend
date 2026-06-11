-- =====================================================================
-- V45 — Paystack subscription tracking (HANDOFF_2026-06-11 §8).
--
-- The Conddo plan billing flow now goes through Paystack's hosted
-- checkout + Subscriptions API. Two new persistent things to track:
--   1. The Paystack-side subscription_code / customer_code stamped
--      onto the active tenant_subscriptions row so renewal webhooks
--      land back on the right tenant.
--   2. A dedicated audit log of every billing transaction we initiated
--      via /api/v1/billing/checkout so /verify can resolve a status
--      by reference even if the webhook lands first.
-- =====================================================================

ALTER TABLE tenant_subscriptions
    ADD COLUMN paystack_subscription_code VARCHAR(64),
    ADD COLUMN paystack_customer_code     VARCHAR(64);

CREATE INDEX idx_tenant_subscriptions_paystack_sub
    ON tenant_subscriptions (paystack_subscription_code)
    WHERE paystack_subscription_code IS NOT NULL;

-- Per-transaction record. One row per /checkout invocation; updated
-- by the webhook + /verify path. Cross-tenant carve-out for the
-- webhook (no JWT, sets cross_tenant before lookups).
CREATE TABLE billing_paystack_transactions (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants (id),
    reference         VARCHAR(80)  NOT NULL UNIQUE,
    plan_id           UUID         NOT NULL REFERENCES subscription_plans (id),
    billing_cycle     VARCHAR(20)  NOT NULL,
    amount_kobo       BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'success', 'failed', 'abandoned')),
    paystack_subscription_code VARCHAR(64),
    failure_reason    TEXT,
    paid_at           TIMESTAMPTZ,
    initiated_by      UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_paystack_tx_tenant_created
    ON billing_paystack_transactions (tenant_id, created_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON billing_paystack_transactions TO ${app_role};

ALTER TABLE billing_paystack_transactions ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON billing_paystack_transactions
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
