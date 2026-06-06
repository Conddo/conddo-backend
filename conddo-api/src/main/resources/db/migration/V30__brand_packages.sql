-- =====================================================================
-- V30 — Brand Packages Phase 3 (SOCIAL_AND_CREATIVE_SERVICES_SPEC §6).
--
-- A tenant subscribes to a recurring creative bundle that auto-includes
-- N designs / videos / ad-creatives per month. Active subscribers can
-- create creative_service_requests WITHOUT paying per-job as long as
-- quota's left — request.price_kobo = 0, payment_reference stays null,
-- and the usage row's counts JSON is incremented. Quota exhausted →
-- 409 QUOTA_EXHAUSTED; the tenant either waits for next period or
-- pays per-job.
--
-- Renewal: BrandPackageRenewalScheduler walks past-due subscriptions
-- daily, charges via conddo-payments (kind=BRAND_PACKAGE → platform
-- account, V2). Failure → past_due, notification fires.
-- =====================================================================

CREATE TABLE brand_package_offerings (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(80)  NOT NULL UNIQUE,
    name                VARCHAR(160) NOT NULL,
    description         TEXT,
    monthly_price_kobo  INTEGER      NOT NULL,
    includes            JSONB        NOT NULL,
    -- {design_static: 8, design_reels: 2, ad_creative_static: 4}
    --   Keys match creative_service_offerings.code (V29).
    active              BOOLEAN      NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_brand_offerings_active ON brand_package_offerings (active, code);

CREATE TABLE brand_package_subscriptions (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID         NOT NULL REFERENCES tenants (id),
    offering_id           UUID         NOT NULL REFERENCES brand_package_offerings (id),
    status                VARCHAR(20)  NOT NULL,
                                       -- pending_payment | active | past_due | cancelled
    current_period_start  TIMESTAMPTZ  NOT NULL,
    current_period_end    TIMESTAMPTZ  NOT NULL,
    payment_reference     VARCHAR(160),                     -- last RoutePay reference (initial or renewal)
    cancelled_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_brand_subs_tenant         ON brand_package_subscriptions (tenant_id, status);
CREATE INDEX idx_brand_subs_period_end     ON brand_package_subscriptions (current_period_end, status);

-- One usage row per (subscription_id, period_start). Increment in-place
-- as creative requests consume the quota; renewal rolls to a fresh row.
CREATE TABLE brand_package_usage (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    subscription_id UUID         NOT NULL REFERENCES brand_package_subscriptions (id) ON DELETE CASCADE,
    period_start    TIMESTAMPTZ  NOT NULL,
    period_end      TIMESTAMPTZ  NOT NULL,
    counts          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- {design_static: 3, ...} — matches the includes keys in the offering.
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (subscription_id, period_start)
);

CREATE INDEX idx_brand_usage_tenant ON brand_package_usage (tenant_id);

GRANT SELECT                            ON brand_package_offerings      TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE    ON brand_package_subscriptions  TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE    ON brand_package_usage          TO ${app_role};

-- Offerings: global catalog, no RLS.
-- Subscriptions + usage: tenant-scoped with the V25 app.public_resolver
-- carve-out so the renewal scheduler and webhook can look up cross-tenant
-- before binding context for writes.
ALTER TABLE brand_package_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE brand_package_usage         ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON brand_package_subscriptions
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON brand_package_usage
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---- Brand-package offerings seed --------------------------------------
-- Prices/counts are placeholders product/finance will tune. Codes inside
-- the `includes` JSON must match creative_service_offerings.code (V29).

INSERT INTO brand_package_offerings (code, name, description, monthly_price_kobo, includes) VALUES
    ('starter_brand',
     'Starter Brand Package',
     '4 static designs + 1 vertical video per month — perfect for solo merchants getting their feed off the ground.',
     2500000,
     '{"design_static": 4, "design_reels": 1}'::jsonb),
    ('growth_brand',
     'Growth Brand Package',
     '8 static designs + 2 reels + 2 ad creatives per month — for merchants running consistent paid + organic.',
     4500000,
     '{"design_static": 8, "design_reels": 2, "ad_creative_static": 2}'::jsonb),
    ('pro_brand',
     'Pro Brand Package',
     '16 static designs + 4 reels + 4 ad creatives (mix static + video) per month — for high-velocity merchants.',
     8500000,
     '{"design_static": 16, "design_reels": 4, "ad_creative_static": 2, "ad_creative_video": 2}'::jsonb)
ON CONFLICT (code) DO NOTHING;

-- ---- Plan-feature seed: brand_package_subscription ---------------------
-- Per spec §9: Launcher='—', Growth+Scaler='true'.

INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT id, 'brand_package_subscription', 'false'
FROM subscription_plans WHERE name = 'launcher'
ON CONFLICT (plan_id, feature_key) DO NOTHING;

INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT id, 'brand_package_subscription', 'true'
FROM subscription_plans WHERE name = 'growth'
ON CONFLICT (plan_id, feature_key) DO NOTHING;

INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT id, 'brand_package_subscription', 'true'
FROM subscription_plans WHERE name = 'scaler'
ON CONFLICT (plan_id, feature_key) DO NOTHING;
