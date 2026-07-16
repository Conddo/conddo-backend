-- =====================================================================
-- V68 — Complete the tenant brand columns (secondary_color, font_pairing).
--
-- V14 already added tagline, description, primary_color, logo_url onto the
-- `tenants` row. This finishes the set with the two brand fields the
-- website renderer needs to style every section end-to-end.
--
-- Kept on `tenants` (not a separate `tenant_brand` table) because:
--   1. brand is a 1:1 attribute of the tenant, never queried without it,
--      and never carries history — a join is pure overhead.
--   2. SettingsService already reads/writes tagline/description/primary_color
--      on this row; a split would fork the write path and drift.
--
-- Defaults match the spec: primary Conddo violet, near-black secondary,
-- Inter font. Applied backfill so existing tenants get sensible values
-- for immediate rendering.
-- =====================================================================

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS secondary_color TEXT,
    ADD COLUMN IF NOT EXISTS font_pairing    TEXT;

-- Backfill: give every existing tenant a working starting brand so the
-- website renderer doesn't render an unstyled page for legacy rows.
UPDATE tenants
   SET primary_color   = COALESCE(primary_color,   '#785DCD'),
       secondary_color = COALESCE(secondary_color, '#111111'),
       font_pairing    = COALESCE(font_pairing,    'inter');
