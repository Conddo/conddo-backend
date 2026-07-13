-- =====================================================================
-- V67 — Pricing v2 (5 tiers, 3 cycles, new price points).
--
-- What changes on the wire:
--   - New tiers `free` + `student` sit BELOW the paid ladder.
--   - Old `launcher` → `starter` and `scaler` → `pro`.
--   - `growth` keeps its name (positioned differently on the ladder).
--   - Prices drop across the board (₦20k/45k/120k → ₦5k/15k/30k monthly).
--   - Adds a `yearly_price` column + row values (30% discount vs 12×monthly).
--   - `plan_features.credits_month` becomes the source of truth for how
--     many credits a new tenant provisions with — TenantActivationListener
--     reads from here instead of the hardcoded 100.
--
-- Existing tenants are RENAMED, not migrated to a new plan:
--   `tenants.plan_id`               — launcher → starter, scaler → pro
--   `tenant_credit_accounts.tier`   — same renames + widened CHECK
--
-- The quota on already-provisioned credit accounts is NOT touched here.
-- We don't want to grant a windfall (or worse, take credits away mid-cycle);
-- the operator can top-up via /admin if a tenant complains.
-- =====================================================================

-- ---- 1. Add yearly_price column to the catalog ------------------------
ALTER TABLE subscription_plans
    ADD COLUMN IF NOT EXISTS yearly_price INTEGER;   -- Kobo; NULL until seeded

-- ---- 2. Rename existing plan rows -------------------------------------
-- Rename in-place so all foreign keys (plan_features.plan_id, tenants.plan_id
-- FKs if any, subscription rows) stay intact. Idempotent — if the plan is
-- already at the new name the UPDATE simply matches zero rows.
UPDATE subscription_plans SET name = 'starter', display_name = 'Starter'
 WHERE name = 'launcher';
UPDATE subscription_plans SET name = 'pro',     display_name = 'Pro'
 WHERE name = 'scaler';

-- ---- 3. Reprice existing tiers (kobo) ---------------------------------
UPDATE subscription_plans
   SET monthly_price =    500000,
       quarterly_price = 1275000,
       yearly_price =    4200000,
       is_custom = false
 WHERE name = 'starter';   -- ₦5,000 / ₦12,750 / ₦42,000

UPDATE subscription_plans
   SET monthly_price =   1500000,
       quarterly_price = 3825000,
       yearly_price =   12600000,
       is_custom = false
 WHERE name = 'growth';    -- ₦15,000 / ₦38,250 / ₦126,000

UPDATE subscription_plans
   SET monthly_price =   3000000,
       quarterly_price = 7650000,
       yearly_price =   25200000,
       is_custom = false
 WHERE name = 'pro';       -- ₦30,000 / ₦76,500 / ₦252,000

-- ---- 4. Seed the two new tiers ----------------------------------------
INSERT INTO subscription_plans (name, display_name, monthly_price, quarterly_price, yearly_price, is_custom)
VALUES
    ('free',    'Free',          0,        0,        0,        false),
    ('student', 'Student',   300000,   765000,  2520000,        false)  -- ₦3,000 / ₦7,650 / ₦25,200
ON CONFLICT (name) DO NOTHING;

-- ---- 5. Rename plan_id on tenants + credit accounts -------------------
UPDATE tenants                  SET plan_id = 'starter' WHERE plan_id = 'launcher';
UPDATE tenants                  SET plan_id = 'pro'     WHERE plan_id = 'scaler';
UPDATE tenant_credit_accounts   SET tier    = 'starter' WHERE tier    = 'launcher';
UPDATE tenant_credit_accounts   SET tier    = 'pro'     WHERE tier    = 'scaler';

-- ---- 6. Widen the tier CHECK constraint --------------------------------
-- V56 allowed only ('free','starter','growth') — a defensible tight set
-- when there were only three product names. Now widened for the two new
-- product tiers.
ALTER TABLE tenant_credit_accounts DROP CONSTRAINT IF EXISTS tier_valid;
ALTER TABLE tenant_credit_accounts
    ADD CONSTRAINT tier_valid
    CHECK (tier IN ('free','student','starter','growth','pro'));

-- ---- 7. Seed features for the two new tiers ---------------------------
-- Student mirrors Starter's feature set (a discount-tier, not a functional
-- tier). Free gets a deliberately narrow slice — website + view analytics —
-- so it's a real onramp, not a "why upgrade" freebie.
WITH plan_ids AS (
    SELECT name, id FROM subscription_plans
     WHERE name IN ('free','student','starter','growth','pro')
),
seed (plan_name, feature_key, feature_value) AS (
    VALUES
        -- Free — website + read-only analytics + tiny credit budget
        ('free', 'website',              'true'),
        ('free', 'custom_domain',        'false'),
        ('free', 'business_email',       'false'),
        ('free', 'order_management',     'false'),
        ('free', 'bookings',             'false'),
        ('free', 'email_campaigns',      'false'),
        ('free', 'sms_campaigns',        'false'),
        ('free', 'social_scheduler',     'false'),
        ('free', 'ad_management',        'false'),
        ('free', 'multi_location',       'false'),
        ('free', 'api_access',           'false'),
        ('free', 'advanced_analytics',   'false'),
        ('free', 'staff_accounts',       '1'),

        -- Student — same as Starter functionally (see below); duplicated
        -- rather than JOIN-copied so a future Student-only feature change
        -- is a single UPDATE on this tier's rows.
        ('student', 'website',           'true'),
        ('student', 'custom_domain',     'false'),
        ('student', 'business_email',    'false'),
        ('student', 'order_management',  'true'),
        ('student', 'bookings',          'true'),
        ('student', 'email_campaigns',   'false'),
        ('student', 'sms_campaigns',     'false'),
        ('student', 'social_scheduler',  'false'),
        ('student', 'ad_management',     'false'),
        ('student', 'multi_location',    'false'),
        ('student', 'api_access',        'false'),
        ('student', 'advanced_analytics','false'),
        ('student', 'staff_accounts',    '2')
)
INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT plan_ids.id, seed.feature_key, seed.feature_value
FROM seed JOIN plan_ids ON plan_ids.name = seed.plan_name
ON CONFLICT (plan_id, feature_key) DO NOTHING;

-- ---- 8. credits_month per plan (source of truth) ----------------------
-- Provisioning reads this via PlanFeatureRepository. Free 100 · Student 300 ·
-- Starter 500 · Growth 3000 · Pro 10000. Kept as strings to match the
-- feature_value TEXT contract.
WITH plan_ids AS (
    SELECT name, id FROM subscription_plans
     WHERE name IN ('free','student','starter','growth','pro')
),
quotas (plan_name, credits_month) AS (
    VALUES ('free', '100'), ('student', '300'), ('starter', '500'),
           ('growth', '3000'), ('pro', '10000')
)
INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT plan_ids.id, 'credits_month', quotas.credits_month
FROM quotas JOIN plan_ids ON plan_ids.name = quotas.plan_name
ON CONFLICT (plan_id, feature_key) DO UPDATE SET feature_value = EXCLUDED.feature_value;
