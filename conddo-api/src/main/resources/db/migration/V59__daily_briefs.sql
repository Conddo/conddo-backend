-- V59 — Daily Business Brief cache (AI Moat surface #1).
--
-- The FE requests the current brief on every dashboard open. We cache one
-- row per (tenant, brief_date) so subsequent views hit the DB instead of
-- OpenRouter — a fresh brief only fires once per tenant per day and after
-- 12h has passed since generation (defensive: a bad brief can be manually
-- deleted and the next dashboard open regenerates).
CREATE TABLE daily_briefs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    brief_date      DATE NOT NULL,
    headline        TEXT NOT NULL,
    body            TEXT NOT NULL,
    data_snapshot   JSONB NOT NULL,
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, brief_date)
);

CREATE INDEX daily_briefs_tenant_date_idx
    ON daily_briefs (tenant_id, brief_date DESC);

ALTER TABLE daily_briefs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON daily_briefs
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
