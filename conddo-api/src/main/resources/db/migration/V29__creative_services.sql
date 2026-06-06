-- =====================================================================
-- V29 — Creative Services Phase 2b (SOCIAL_AND_CREATIVE_SERVICES_SPEC §5).
--
-- A tenant requests a paid creative service (graphic design / video edit
-- / ad creative) attached to a post or standalone. The request enters
-- pending_payment, the tenant pays via RoutePay (through conddo-payments),
-- a Studio job is created, and on delivery the final media flows back
-- to the request — and to the originating social post if linked.
--
-- creative_service_offerings is the global catalog (NOT tenant-scoped;
-- Studio admin manages it). creative_service_requests is tenant-scoped
-- via RLS — each tenant only sees its own requests.
-- =====================================================================

CREATE TABLE creative_service_offerings (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code              VARCHAR(80)  NOT NULL UNIQUE,
    name              VARCHAR(160) NOT NULL,
    description       TEXT,
    price_kobo        INTEGER      NOT NULL,
    turnaround_hours  INTEGER      NOT NULL,
    job_type          VARCHAR(40)  NOT NULL,                  -- CREATIVE_DESIGN | CREATIVE_VIDEO | CREATIVE_AD
    active            BOOLEAN      NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_creative_offerings_active ON creative_service_offerings (active, code);

CREATE TABLE creative_service_requests (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants (id),
    user_id           UUID         NOT NULL REFERENCES users (id),
    offering_id       UUID         NOT NULL REFERENCES creative_service_offerings (id),
    social_post_id    UUID         REFERENCES social_posts (id),

    brief             TEXT         NOT NULL,
    attached_media    JSONB,                                   -- [media_asset_id, …]
    price_kobo        INTEGER      NOT NULL,                   -- frozen at request time

    status            VARCHAR(20)  NOT NULL,                   -- pending_payment | queued | in_progress | delivered | cancelled
    payment_reference VARCHAR(160),                            -- RoutePay reference (via conddo-payments)
    studio_job_id     UUID,                                    -- Studio's job UUID once created
    studio_job_number VARCHAR(80),                             -- human-readable (e.g. CD-1042) for UI display

    delivery_media    JSONB,                                   -- [{url, width, height}, …] returned by Studio
    delivered_at      TIMESTAMPTZ,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_creative_requests_tenant_status ON creative_service_requests (tenant_id, status, created_at DESC);
CREATE INDEX idx_creative_requests_post         ON creative_service_requests (social_post_id);

GRANT SELECT                            ON creative_service_offerings TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE    ON creative_service_requests  TO ${app_role};

-- Offerings are world-readable to authenticated tenants; no RLS needed.
ALTER TABLE creative_service_requests ENABLE ROW LEVEL SECURITY;

-- The Studio-side delivered webhook arrives without a tenant context —
-- look up the row by id under the public_resolver carve-out, then bind
-- the tenant for the write.
CREATE POLICY tenant_isolation ON creative_service_requests
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---- Catalog seed ----------------------------------------------------
-- Prices are placeholders product/finance will tune. job_type maps to
-- Studio's job-type registry (already extended in conddo-studio for
-- CREATIVE_DESIGN / CREATIVE_VIDEO / CREATIVE_AD; see §8).

INSERT INTO creative_service_offerings (code, name, description, price_kobo, turnaround_hours, job_type) VALUES
    ('design_static',      'Static Design',
     'A single static graphic for one platform (1080×1080 IG, 1200×630 FB, etc.)',
     500000,   24, 'CREATIVE_DESIGN'),
    ('design_reels',       'Reels / Vertical Video Edit',
     'A 15-60s vertical-format video edit from your raw footage, captions included',
     1500000,  48, 'CREATIVE_VIDEO'),
    ('ad_creative_static', 'Ad Creative (Static)',
     'A static ad creative tuned for paid Meta / Google placements, multiple aspect ratios',
     800000,   36, 'CREATIVE_AD'),
    ('ad_creative_video',  'Ad Creative (Video)',
     'A short video ad creative for paid Meta / TikTok placements',
     2000000,  60, 'CREATIVE_AD'),
    ('brand_kit_starter',  'Brand Starter Kit',
     'Logo refresh + colour palette + 5 templated post designs',
     5000000,  72, 'CREATIVE_DESIGN')
ON CONFLICT (code) DO NOTHING;
