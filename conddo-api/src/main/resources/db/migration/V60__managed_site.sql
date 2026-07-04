-- =====================================================================
-- V60 — Managed website (Path A: Conddo hosts + AI-generates).
--
-- The original tenant_sites (V25) modeled the "external hosting" pattern —
-- tenant builds their own site, we serve JSON at /api/v1/public/{slug}.
-- Path A extends the same row so a single tenant can transition without
-- losing state: keep the api_key + submitted_url columns for existing
-- integrations, add the managed fields for the AI-generated site.
--
-- Distinguishing them:
--   managed = TRUE  → sections + theme served by our public site renderer
--                     at <slug>.getconddo.com (default for new signups).
--   managed = FALSE → legacy submission flow; is_active + submitted_url
--                     drive the "external URL" surface. No change.
--
-- Draft vs live:
--   {draft_sections, draft_theme}  editable, visible in dashboard preview
--   {sections, theme}              live, served to public visitors
--   published_at                   when the draft was last promoted
-- =====================================================================

ALTER TABLE tenant_sites
    ADD COLUMN sections        JSONB,
    ADD COLUMN theme           JSONB,
    ADD COLUMN draft_sections  JSONB,
    ADD COLUMN draft_theme     JSONB,
    ADD COLUMN published_at    TIMESTAMPTZ,
    ADD COLUMN managed         BOOLEAN NOT NULL DEFAULT FALSE;

-- Fast public lookup by hostname. The public site endpoint receives a Host
-- header like "amaka-pharmacy.getconddo.com" or "amakapharmacy.com" and
-- needs to resolve to a tenant in a single index seek.
-- Partial index — only rows serving a live managed site or with a live
-- custom domain get an entry.
CREATE INDEX tenant_sites_public_subdomain_idx
    ON tenant_sites (subdomain)
    WHERE managed = TRUE AND published_at IS NOT NULL;

CREATE INDEX tenant_sites_public_custom_domain_idx
    ON tenant_sites (custom_domain)
    WHERE custom_domain IS NOT NULL AND published_at IS NOT NULL;
