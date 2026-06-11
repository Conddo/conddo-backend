-- =====================================================================
-- V43 — Pharmacy Roadmap Beta 1: Cashback Loyalty.
--
-- Three tables:
--   pharmacy_loyalty_config        — per-tenant cashback rate + threshold
--   pharmacy_customer_wallets      — one row per (tenant, customer)
--   pharmacy_wallet_transactions   — append-only ledger
--
-- Cashback credits when an order moves to DELIVERED (not on placement —
-- spec is explicit). Redemption deducts at checkout. Listener-driven
-- on the BE; no FE work either side.
-- =====================================================================

CREATE TABLE pharmacy_loyalty_config (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL UNIQUE REFERENCES tenants (id),
    cashback_rate   NUMERIC(5, 2)  NOT NULL DEFAULT 2.00
        CHECK (cashback_rate >= 0 AND cashback_rate <= 100),
    min_redemption  NUMERIC(10, 2) NOT NULL DEFAULT 500.00
        CHECK (min_redemption >= 0),
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE pharmacy_customer_wallets (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    customer_id     UUID         NOT NULL,
    balance         NUMERIC(12, 2) NOT NULL DEFAULT 0.00 CHECK (balance >= 0),
    total_earned    NUMERIC(12, 2) NOT NULL DEFAULT 0.00 CHECK (total_earned >= 0),
    total_redeemed  NUMERIC(12, 2) NOT NULL DEFAULT 0.00 CHECK (total_redeemed >= 0),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, customer_id)
);

CREATE INDEX idx_wallets_tenant_balance
    ON pharmacy_customer_wallets (tenant_id, balance DESC);

CREATE TABLE pharmacy_wallet_transactions (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants (id),
    wallet_id         UUID         NOT NULL REFERENCES pharmacy_customer_wallets (id) ON DELETE CASCADE,
    transaction_type  VARCHAR(20)  NOT NULL
        CHECK (transaction_type IN ('CASHBACK_EARNED', 'REDEMPTION', 'ADJUSTMENT', 'EXPIRY')),
    -- Signed: + credit (CASHBACK_EARNED, ADJUSTMENT+), − debit (REDEMPTION, EXPIRY, ADJUSTMENT−).
    amount            NUMERIC(12, 2) NOT NULL,
    reference_id      UUID,
    note              TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_tx_wallet
    ON pharmacy_wallet_transactions (wallet_id, created_at DESC);
CREATE INDEX idx_wallet_tx_tenant_type
    ON pharmacy_wallet_transactions (tenant_id, transaction_type, created_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_loyalty_config       TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_customer_wallets     TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_wallet_transactions  TO ${app_role};

ALTER TABLE pharmacy_loyalty_config       ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_customer_wallets     ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_wallet_transactions  ENABLE ROW LEVEL SECURITY;

-- Cross-tenant carve-out: the cashback-credit listener runs from the
-- order DELIVERED transition's event thread with the tenant bound,
-- so it doesn't need the carve-out for the common path. Public
-- checkout redemption likewise binds the tenant. The carve-out is
-- here in case a future cron (e.g. balance expiry) lands later.
CREATE POLICY tenant_isolation ON pharmacy_loyalty_config
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pharmacy_customer_wallets
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pharmacy_wallet_transactions
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
