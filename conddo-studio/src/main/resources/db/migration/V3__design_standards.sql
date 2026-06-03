-- =====================================================================
-- Conddo Studio — Design Standard Library (Infrastructure §8).
-- Reference content ADMINs curate per vertical: brand palettes, section
-- layouts, copy patterns, typography. Read by the AI assistant (§8 §9)
-- when generating copy or palettes; read by the QA Scanner when looking
-- for tone drift. ADMIN-only writes; everyone reads (it's project context,
-- not credentials).
-- =====================================================================

CREATE TABLE studio.design_standards (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vertical      TEXT,                              -- null = global / cross-vertical
    kind          TEXT NOT NULL
                    CHECK (kind IN ('PALETTE','LAYOUT','COPY_PATTERN','TYPOGRAPHY')),
    name          TEXT NOT NULL,                     -- e.g. "Pharmacy — calming greens"
    description   TEXT,
    content       JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_design_standards_lookup
    ON studio.design_standards (kind, vertical, is_active);
