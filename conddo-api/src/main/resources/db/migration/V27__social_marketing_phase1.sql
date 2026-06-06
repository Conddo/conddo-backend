-- =====================================================================
-- V27 — Social Marketing Phase 1 (SOCIAL_AND_CREATIVE_SERVICES_SPEC §1-3).
--
-- Tenant connects social channels via Ayrshare (unified API across
-- Facebook, Instagram, LinkedIn, X, TikTok, etc.). Phase 1 covers connect,
-- compose, schedule, and webhook reconcile — creative services and brand
-- packages land in subsequent phases.
--
-- One profile per tenant on the Ayrshare side; the profile aggregates ALL
-- connected providers. We never store per-platform tokens — Ayrshare's
-- hosted dialog handles every provider-specific OAuth dance.
--
-- The Ayrshare profile_key is encrypted at rest by SocialTokenCipher
-- (AES-GCM, 32-byte envelope key in CONDDO_SOCIAL_TOKEN_KEY, byte-prefixed
-- version for rotation).
-- =====================================================================

CREATE TABLE tenant_social_profile (
    tenant_id                UUID PRIMARY KEY REFERENCES tenants (id),
    ayrshare_profile_key     TEXT         NOT NULL,                      -- AES-GCM encrypted at rest
    ayrshare_profile_title   VARCHAR(160),                                -- mirrors tenant.name
    connected_platforms      JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- ["facebook","instagram",...]
    last_synced_at           TIMESTAMPTZ,                                  -- last /api/user refresh
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE social_posts (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID         NOT NULL REFERENCES tenants (id),
    author_user_id        UUID         NOT NULL REFERENCES users (id),
    caption               TEXT         NOT NULL,
    media                 JSONB,                                           -- [{url, type, width, height}, …]
    scheduled_at          TIMESTAMPTZ  NOT NULL,
    timezone              VARCHAR(64)  NOT NULL DEFAULT 'Africa/Lagos',
    status                VARCHAR(20)  NOT NULL,                           -- draft | scheduled | publishing | published | failed
    ayrshare_post_id      VARCHAR(255),                                    -- Ayrshare's scheduledPostId (Strategy A)
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_social_posts_tenant_sched ON social_posts (tenant_id, scheduled_at);
CREATE INDEX idx_social_posts_status       ON social_posts (status, scheduled_at);

-- One row per target channel — a post can be cross-posted. tenant_id is
-- denormalised onto the row so RLS scopes the table directly (the FK
-- alone would be a cross-table check that RLS can't enforce).
CREATE TABLE social_post_targets (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID         NOT NULL REFERENCES tenants (id),
    post_id               UUID         NOT NULL REFERENCES social_posts (id) ON DELETE CASCADE,
    provider              VARCHAR(40)  NOT NULL,                           -- facebook | instagram | linkedin | x | tiktok | ...
    external_post_id      VARCHAR(255),                                    -- after successful publish
    status                VARCHAR(20)  NOT NULL,                           -- pending | published | failed
    error_message         TEXT,
    published_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (post_id, provider)
);

CREATE INDEX idx_social_post_targets_tenant ON social_post_targets (tenant_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_social_profile TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON social_posts          TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON social_post_targets   TO ${app_role};

ALTER TABLE tenant_social_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE social_posts          ENABLE ROW LEVEL SECURITY;
ALTER TABLE social_post_targets   ENABLE ROW LEVEL SECURITY;

-- USING widens for the webhook reconcile path: the Ayrshare webhook
-- arrives without a tenant context, looks up the post by ayrshare_post_id,
-- and only then binds the tenant for subsequent writes. The carve-out is
-- the same app.public_resolver flag V25 / V26 use — SELECT only; WITH
-- CHECK stays strict so the webhook path can't INSERT/UPDATE without
-- binding a tenant first.

CREATE POLICY tenant_isolation ON tenant_social_profile
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON social_posts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON social_post_targets
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
