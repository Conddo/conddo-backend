-- =====================================================================
-- V77 — Connected accounts (Moniepoint / OPay) + incoming transfer feed.
--
-- The Moniepoint/OPay model: Paystack is only for billing tenants for
-- their Conddo subscription. Tenant-facing customer payments land in the
-- tenant's OWN Moniepoint / OPay / bank account, and Conddo matches the
-- money to the invoice.
--
-- Two new tables:
--
--   1) tenant_integrations — the tenant's connected payment accounts.
--      One row per (tenant, provider). Credentials are stored encrypted
--      at rest (AES-GCM via SecretCipher) inside a JSONB column so each
--      provider can carry its own field set. The provider-returned
--      terminal / business snapshot is denormalised onto the row so the
--      "connected accounts" screen renders without a provider call.
--
--   2) incoming_transfers — the money feed. Every transfer / terminal
--      sale pushed by a webhook or pulled by the poller lands here with
--      a matched/unmatched flag. Matching is idempotent: a matched row
--      stays matched and re-matching returns the existing match.
--
-- Money is in kobo (BIGINT), consistent with V56 / V73 / V74.
-- =====================================================================

-- ---- tenant_integrations -------------------------------------------
CREATE TABLE tenant_integrations (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    -- moniepoint | opay
    provider               TEXT NOT NULL
        CHECK (provider IN ('moniepoint','opay')),

    -- connected = verified + live; error = last verification / sync
    -- failed; disconnected = tenant removed the account.
    status                 TEXT NOT NULL DEFAULT 'connected'
        CHECK (status IN ('connected','error','disconnected')),

    -- Human label + provider-returned merchant snapshot. Kept so the
    -- list screen never needs a provider round-trip.
    label                  TEXT,
    business_name          TEXT,
    account_name           TEXT,
    account_number         TEXT,
    bank_name              TEXT,
    terminal_serial        TEXT,

    -- Provider credentials, each field encrypted at rest with
    -- SecretCipher (AES-GCM). e.g. {"apiKey":"<enc>"} for Moniepoint,
    -- {"merchantId":"<enc>","privateKey":"<enc>","publicKey":"<enc>"}
    -- for OPay. NEVER returned by any API.
    credentials            JSONB NOT NULL DEFAULT '{}'::jsonb,

    -- Non-secret lookup key used to resolve the tenant from a webhook
    -- payload: the OPay merchantId as-is, or the SHA-256 of the
    -- Moniepoint api key. Lookup-only — never the raw secret.
    merchant_reference    TEXT,

    verified_at            TIMESTAMPTZ,
    last_checked_at        TIMESTAMPTZ,
    last_error             TEXT,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, provider)
);

CREATE INDEX idx_integrations_tenant
    ON tenant_integrations (tenant_id);
CREATE INDEX idx_integrations_provider_status
    ON tenant_integrations (provider, status);
CREATE INDEX idx_integrations_provider_ref
    ON tenant_integrations (provider, merchant_reference);

-- ---- incoming_transfers ---------------------------------------------
-- The money feed. A row per inbound transfer / terminal sale from a
-- connected account. provider_reference is the provider's own
-- transaction id — deduped so webhook retries + poller runs can't
-- double-insert.
CREATE TABLE incoming_transfers (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    -- moniepoint | opay
    provider               TEXT NOT NULL
        CHECK (provider IN ('moniepoint','opay')),

    -- Provider's transaction reference (their id, not ours).
    provider_reference     TEXT NOT NULL,

    -- Payer snapshot — denormalised from the provider payload.
    sender_name            TEXT,
    sender_account_number  TEXT,
    sender_bank            TEXT,

    -- Money.
    amount_kobo            BIGINT NOT NULL CHECK (amount_kobo > 0),
    currency               TEXT NOT NULL DEFAULT 'NGN',

    -- When the money actually moved (provider time), not when we saw it.
    received_at            TIMESTAMPTZ NOT NULL,

    -- unmatched = waiting in the MatchTransfer sheet; matched = the
    -- tenant confirmed it against an invoice / order.
    status                 TEXT NOT NULL DEFAULT 'unmatched'
        CHECK (status IN ('unmatched','matched')),

    -- Match target. Exactly zero or one is set.
    matched_invoice_id     UUID REFERENCES invoices(id) ON DELETE SET NULL,
    matched_order_id       UUID REFERENCES orders(id) ON DELETE SET NULL,
    matched_at             TIMESTAMPTZ,
    matched_by             UUID REFERENCES users(id) ON DELETE SET NULL,
    note                   TEXT,

    -- Verbatim provider payload for audit / re-reconciliation.
    raw_payload            JSONB,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (provider, provider_reference)
);

CREATE INDEX idx_incoming_transfers_tenant_status
    ON incoming_transfers (tenant_id, status);
CREATE INDEX idx_incoming_transfers_tenant_received
    ON incoming_transfers (tenant_id, received_at DESC);
CREATE INDEX idx_incoming_transfers_unmatched
    ON incoming_transfers (tenant_id)
    WHERE status = 'unmatched';

-- ---- RLS -------------------------------------------------------------
-- Standard tenant isolation, matching V74. The poller + webhook handler
-- run with app.cross_tenant='true' so they can read the provider
-- reference across tenants for dedupe; every row is tenant-scoped.
ALTER TABLE tenant_integrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE incoming_transfers   ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenant_integrations
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON incoming_transfers
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_integrations TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON incoming_transfers   TO ${app_role};

-- ---- plan_features: connected accounts + transfer matching -----------
-- Free to use on every tier that can already take customer payments —
-- matching inbound transfers to invoices is core to the owner-led
-- small-business persona, not a Growth upsell.
INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT sp.id, 'transfer_matching', 'true'
  FROM subscription_plans sp
 WHERE sp.name IN ('free','student','starter','growth','pro')
ON CONFLICT (plan_id, feature_key) DO NOTHING;
