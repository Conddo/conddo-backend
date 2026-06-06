-- =====================================================================
-- V28 — Media Library Phase 2a (SOCIAL_AND_CREATIVE_SERVICES_SPEC §4).
--
-- Existing media_assets (V19) only tracked storage_key + size + kind. The
-- social composer needs dimensions for layout previews and to enforce
-- platform-specific aspect ratios; creative services (Phase 2b) need to
-- know who in the org uploaded the source media. Plan-tier storage caps
-- (500MB / 5GB / unlimited) need a feature row to read from.
-- =====================================================================

ALTER TABLE media_assets
    ADD COLUMN width       INTEGER,
    ADD COLUMN height      INTEGER,
    ADD COLUMN uploaded_by UUID REFERENCES users (id);

CREATE INDEX idx_media_assets_uploaded_by ON media_assets (uploaded_by);

-- ---- Plan-feature seed: media_storage_mb -------------------------------
-- Insert is idempotent (ON CONFLICT) so a hot-reload of V24 doesn't
-- collide with a Phase-2 redeploy.

INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT id, 'media_storage_mb', '500'
FROM subscription_plans WHERE name = 'launcher'
ON CONFLICT (plan_id, feature_key) DO NOTHING;

INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT id, 'media_storage_mb', '5120'
FROM subscription_plans WHERE name = 'growth'
ON CONFLICT (plan_id, feature_key) DO NOTHING;

INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT id, 'media_storage_mb', 'unlimited'
FROM subscription_plans WHERE name = 'scaler'
ON CONFLICT (plan_id, feature_key) DO NOTHING;
