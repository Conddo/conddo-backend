-- =====================================================================
-- V56 — Credit system (Billing PR 2a).
--
-- Every tenant gets a credit account at signup with the Free tier
-- allocation (100 credits/month). Credits are consumed by:
--   - AI provisioning at signup             10 credits (one-time)
--   - Every processed order                  1 credit
--   - Every automation trigger fired         2 credits
--   - Every AI marketing message sent        3 credits
--   - Every website (re)generation           5 credits
--   - AI copy regeneration per section       2 credits
-- Customer records + payments + dashboard views are NEVER gated
-- (see BILLING_TIERS_SPEC.md §"Never gated").
--
-- Concurrency is enforced with an atomic decrement query
--   UPDATE tenant_credit_accounts
--   SET credits_used = credits_used + $cost
--   WHERE tenant_id = $t
--     AND (monthly_quota + topup_credits - credits_used - reserved_credits) >= $cost
-- so two racing sync consumers cannot both succeed against a shared pool.
--
-- Async paths (workflow triggers, AI calls that may fail mid-way) use
-- reserve → confirm|release via credit_transactions.status. Reservations
-- carry an expires_at so a stuck workflow never permanently ties up credits.
-- =====================================================================

CREATE TABLE tenant_credit_accounts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL UNIQUE REFERENCES tenants (id) ON DELETE CASCADE,

    -- 'free' | 'starter' | 'growth' — the doc's three tiers. Feature-flat;
    -- differentiation is credit volume only.
    tier                    TEXT NOT NULL DEFAULT 'free',

    -- Renewable monthly quota + running counter + purchased top-ups
    -- (top-ups roll over between months; monthly quota does NOT).
    monthly_quota           INTEGER NOT NULL DEFAULT 100,
    credits_used            INTEGER NOT NULL DEFAULT 0,
    topup_credits           INTEGER NOT NULL DEFAULT 0,

    -- Sum of live RESERVED transactions. Denormalised for the atomic
    -- availability check; kept in sync with credit_transactions inside
    -- the same tx that inserts/updates a RESERVED row.
    reserved_credits        INTEGER NOT NULL DEFAULT 0,

    -- Per-tenant billing anchor (subscription cycle for paid tiers, or
    -- signup anchor for Free). Cycle rollover is lazy — the next consume
    -- after billing_cycle_end triggers a reset before checking availability.
    billing_cycle_start     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    billing_cycle_end       TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '30 days'),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT tier_valid CHECK (tier IN ('free', 'starter', 'growth')),
    CONSTRAINT quota_non_negative CHECK (monthly_quota >= 0),
    CONSTRAINT used_non_negative CHECK (credits_used >= 0),
    CONSTRAINT topup_non_negative CHECK (topup_credits >= 0),
    CONSTRAINT reserved_non_negative CHECK (reserved_credits >= 0)
);

CREATE INDEX tenant_credit_accounts_cycle_end_idx
    ON tenant_credit_accounts (billing_cycle_end);

ALTER TABLE tenant_credit_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_credit_accounts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

-- ------------------------------------------------------------------ --
-- credit_transactions — one row per event (reserve/consume/release/topup).
-- The reference_id points at the domain row that triggered the debit
-- (order id, workflow_run id, ai_generation id, etc.) so a tenant can
-- reconcile "why did I lose these credits?" from their dashboard.
-- ------------------------------------------------------------------ --
CREATE TABLE credit_transactions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,

    -- Canonical action type strings live in the Java layer's CreditActions
    -- constants ('order.processed', 'ai.provisioning', etc.). Not FK'd
    -- because new modules add their own action strings without a migration.
    action_type             TEXT NOT NULL,
    credits_consumed        INTEGER NOT NULL,

    -- RESERVED  — credits earmarked but not yet consumed (async paths)
    -- CONSUMED  — final state; counted in credits_used
    -- RELEASED  — reservation returned, no charge (workflow errored, etc.)
    -- Sync paths write directly as CONSUMED and skip RESERVED entirely.
    status                  TEXT NOT NULL DEFAULT 'CONSUMED',

    -- Only set for RESERVED rows. A reservation past its expires_at is
    -- ignored by availability checks and swept back into the quota by
    -- the next consume call (or a housekeeping job later).
    reserved_expires_at     TIMESTAMPTZ,

    -- Domain row that caused this debit — nullable so the schema doesn't
    -- fight ad-hoc admin debits.
    reference_id            UUID,
    reference_type          TEXT,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at             TIMESTAMPTZ,

    CONSTRAINT status_valid CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    CONSTRAINT credits_positive CHECK (credits_consumed > 0)
);

CREATE INDEX credit_transactions_tenant_created_idx
    ON credit_transactions (tenant_id, created_at DESC);

-- Partial index — the "find stale reservations" query is the hot path
-- and only cares about live RESERVED rows.
CREATE INDEX credit_transactions_reserved_expires_idx
    ON credit_transactions (reserved_expires_at)
    WHERE status = 'RESERVED';

ALTER TABLE credit_transactions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON credit_transactions
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

-- ------------------------------------------------------------------ --
-- credit_topups — one-time Paystack charges (₦500 per 50 credits).
-- Separate table (not a credit_transactions status) because top-ups
-- have money attached and get their own reporting surface.
-- ------------------------------------------------------------------ --
CREATE TABLE credit_topups (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    credits_added           INTEGER NOT NULL,
    amount_paid             NUMERIC(10, 2) NOT NULL,

    -- Paystack transaction reference. Unique so the webhook is idempotent
    -- when Paystack retries.
    payment_ref             TEXT UNIQUE,
    status                  TEXT NOT NULL DEFAULT 'PENDING',

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at              TIMESTAMPTZ,

    CONSTRAINT topup_status_valid CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT topup_credits_positive CHECK (credits_added > 0),
    CONSTRAINT topup_amount_positive CHECK (amount_paid > 0)
);

CREATE INDEX credit_topups_tenant_created_idx
    ON credit_topups (tenant_id, created_at DESC);

ALTER TABLE credit_topups ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON credit_topups
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

-- ------------------------------------------------------------------ --
-- Backfill — every existing tenant gets a Free-tier credit account.
-- The 30-day cycle starts NOW; existing tenants get their first cycle
-- from this migration date. Legacy usage isn't retroactively counted.
-- ------------------------------------------------------------------ --
INSERT INTO tenant_credit_accounts (tenant_id, tier, monthly_quota, billing_cycle_start, billing_cycle_end)
SELECT id, 'free', 100, NOW(), NOW() + INTERVAL '30 days'
FROM tenants
ON CONFLICT (tenant_id) DO NOTHING;
