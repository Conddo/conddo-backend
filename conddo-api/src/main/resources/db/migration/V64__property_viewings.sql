-- =====================================================================
-- V64 — Real estate vertical: `property_viewings` table.
--
-- Distinct from generic `bookings` because:
--   - Every viewing points at a specific property + agent
--   - Party size (how many people showing up) matters for prep
--   - Outcome is captured (interested / not / needs another) so the
--     deals kanban can auto-advance from viewed → offer_made without
--     the agent double-entering
--
-- Tenant-scoped via RLS.
-- =====================================================================

CREATE TABLE property_viewings (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,

    property_id             UUID NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    deal_id                 UUID REFERENCES deals (id) ON DELETE SET NULL,
    agent_id                UUID,                       -- staff running the viewing

    -- Prospect (may not yet be a full CRM contact)
    customer_id             UUID REFERENCES customers (id) ON DELETE SET NULL,
    prospect_name           TEXT NOT NULL,
    prospect_phone          TEXT,
    prospect_email          TEXT,

    party_size              INTEGER NOT NULL DEFAULT 1,

    -- Timing
    scheduled_at            TIMESTAMPTZ NOT NULL,
    duration_minutes        INTEGER NOT NULL DEFAULT 30,

    -- Lifecycle
    status                  TEXT NOT NULL DEFAULT 'scheduled',
                            -- scheduled | confirmed | completed | no_show | cancelled

    -- Outcome (populated post-viewing)
    outcome                 TEXT,   -- interested | not_interested | needs_another
    outcome_notes           TEXT,

    -- Confirmation channels
    confirmed_at            TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,

    -- Free notes for agent prep
    notes                   TEXT,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT viewing_status_valid CHECK (status IN (
        'scheduled', 'confirmed', 'completed', 'no_show', 'cancelled'
    )),
    CONSTRAINT viewing_outcome_valid CHECK (outcome IS NULL OR outcome IN (
        'interested', 'not_interested', 'needs_another'
    ))
);

CREATE INDEX viewings_tenant_scheduled_idx ON property_viewings (tenant_id, scheduled_at);
CREATE INDEX viewings_property_idx         ON property_viewings (tenant_id, property_id);
CREATE INDEX viewings_agent_upcoming_idx
    ON property_viewings (tenant_id, agent_id, scheduled_at)
    WHERE status IN ('scheduled', 'confirmed');

ALTER TABLE property_viewings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON property_viewings
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
