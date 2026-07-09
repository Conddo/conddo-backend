-- =====================================================================
-- V65 — Real estate vertical: commission_entries.
--
-- A commission entry ties an agent to a deal with a specific split.
-- Nigerian real estate: multi-agent split is common (introducer +
-- closer). Commission accrues at deposit_paid stage — not at close —
-- so entries are created when the deal moves to that stage.
--
-- Payout tracking is separate from accrual: `accrued` when the deal
-- hits deposit_paid, `paid_out` when the tenant marks it settled from
-- the dashboard.
-- =====================================================================

CREATE TABLE commission_entries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,

    deal_id                 UUID NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    agent_id                UUID NOT NULL,

    -- Role: primary agent or introducer/sub-agent
    role                    TEXT NOT NULL DEFAULT 'primary',

    -- Split percentage of the deal's total commission (e.g. 60 + 40 for
    -- primary + introducer). Independent of the deal's own commission_pct.
    split_pct               NUMERIC(5, 2) NOT NULL DEFAULT 100,
    amount                  NUMERIC(14, 2) NOT NULL,

    -- Lifecycle
    status                  TEXT NOT NULL DEFAULT 'accrued',
                            -- accrued | paid_out | reversed

    accrued_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paid_out_at             TIMESTAMPTZ,
    reversed_at             TIMESTAMPTZ,
    reversal_reason         TEXT,

    -- Payment reference — when the tenant records the payout.
    payment_reference       TEXT,

    notes                   TEXT,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT commission_role_valid CHECK (role IN ('primary', 'introducer')),
    CONSTRAINT commission_status_valid CHECK (status IN ('accrued', 'paid_out', 'reversed')),
    CONSTRAINT commission_amount_non_negative CHECK (amount >= 0)
);

CREATE INDEX commission_tenant_agent_idx  ON commission_entries (tenant_id, agent_id);
CREATE INDEX commission_tenant_status_idx ON commission_entries (tenant_id, status);
CREATE INDEX commission_deal_idx          ON commission_entries (deal_id);

ALTER TABLE commission_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON commission_entries
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
