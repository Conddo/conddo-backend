-- =====================================================================
-- Conddo Studio — Website Builder (Infrastructure §21).
-- Moves the website-build flow into Studio: a Job gets a Site (1:1),
-- which holds typed Pages, which hold typed Sections. Theme + meta are
-- JSONB on the Site itself. Optimistic locking via Site.version — every
-- mutation increments it; the FE sends If-Match: <version>.
--
-- Numbering — spec §21.2 says V3 but V3 is the Design Standard Library
-- and V4 is Platform Admin, so this lands as V5.
-- =====================================================================

CREATE TABLE studio.sites (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL UNIQUE REFERENCES studio.jobs(id) ON DELETE CASCADE,
    theme           JSONB NOT NULL DEFAULT '{}'::jsonb,
    meta            JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          TEXT NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT','PUBLISHED')),
    published_at    TIMESTAMPTZ,
    version         INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE studio.site_pages (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id       UUID NOT NULL REFERENCES studio.sites(id) ON DELETE CASCADE,
    slug          TEXT NOT NULL,
    title         TEXT NOT NULL,
    is_home       BOOLEAN NOT NULL DEFAULT false,
    order_index   INTEGER NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (site_id, slug)
);

CREATE TABLE studio.site_sections (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_id       UUID NOT NULL REFERENCES studio.site_pages(id) ON DELETE CASCADE,
    section_type  TEXT NOT NULL,    -- HERO | SERVICES | ABOUT | CTA | GALLERY | CONTACT | CUSTOM
    content       JSONB NOT NULL DEFAULT '{}'::jsonb,
    order_index   INTEGER NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_site_pages_site ON studio.site_pages (site_id, order_index);
CREATE INDEX idx_site_sections_page ON studio.site_sections (page_id, order_index);
