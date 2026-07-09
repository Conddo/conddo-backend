-- =====================================================================
-- V63 — Real estate vertical: `deals` table (the sales/rental pipeline).
--
-- A deal is a specific property + prospect pairing that moves through
-- a kanban (Lead → Viewing scheduled → Viewed → Offer → Deposit paid
-- → Documentation → Signed → Closed). Nigerian real estate cadence:
-- deposit-paid is the pivotal moment when commission accrues, NOT
-- signature or close — often the last stages drag on paperwork while
-- the deal is effectively won.
--
-- Tenant-scoped via RLS. Property + prospect are FK'd but nullable
-- (deals can start before a property is picked or before the prospect
-- is a full CRM contact).
-- =====================================================================

CREATE TABLE deals (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,

    -- Optional at creation time — agents create the deal with just a
    -- prospect name; property + customer link in later.
    property_id             UUID REFERENCES properties (id) ON DELETE SET NULL,
    customer_id             UUID REFERENCES customers (id) ON DELETE SET NULL,

    -- When customer isn't yet a full CRM contact.
    prospect_name           TEXT,
    prospect_phone          TEXT,
    prospect_email          TEXT,

    -- Kanban.
    stage                   TEXT NOT NULL DEFAULT 'lead',
                            -- lead | viewing_scheduled | viewed | offer_made
                            -- | deposit_paid | documentation | signed | closed | lost
    stage_changed_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Financial. `deal_value` may differ from the property's price
    -- (negotiated). Commission_pct captured PER DEAL because it gets
    -- negotiated mid-flow (5-10% is standard but often reduces).
    deal_value              NUMERIC(14, 2),
    currency                TEXT NOT NULL DEFAULT 'NGN',
    commission_pct          NUMERIC(5, 2),                  -- e.g. 7.50
    commission_amount       NUMERIC(14, 2),                 -- computed on save
    deposit_amount          NUMERIC(14, 2),
    deposit_paid_at         TIMESTAMPTZ,

    -- Assignment
    primary_agent_id        UUID,                           -- staff user
    introducer_agent_id     UUID,                           -- sub-agent / referrer (optional)

    -- Notes trail
    notes                   TEXT,

    -- Loss reason (when stage = lost)
    lost_reason             TEXT,

    -- Expected close date — agent-set, used by the pipeline widget.
    expected_close_at       TIMESTAMPTZ,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT stage_valid CHECK (stage IN (
        'lead', 'viewing_scheduled', 'viewed', 'offer_made',
        'deposit_paid', 'documentation', 'signed', 'closed', 'lost'
    ))
);

CREATE INDEX deals_tenant_stage_idx   ON deals (tenant_id, stage);
CREATE INDEX deals_tenant_created_idx ON deals (tenant_id, created_at DESC);
CREATE INDEX deals_tenant_agent_idx   ON deals (tenant_id, primary_agent_id);

ALTER TABLE deals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON deals
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
