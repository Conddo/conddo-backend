-- =====================================================================
-- V32 — Pharmacy public catalog + customer auth foundation
-- (PHARMACY_PUBLIC_API_SPEC §§2-3 / STUDIO_DEV_ONBOARDING_SEB_BAYOR §1).
--
-- Three changes:
--
-- 1. customers gains password_hash so customers can self-register on the
--    merchant's public website. Nullable for back-compat with the
--    merchant-side customer rows (created by walk-in entry / order
--    intake, no password set).
--
-- 2. product_categories gains slug + icon so the public catalog endpoint
--    can render category chips with a stable url-safe key and an icon
--    name the FE maps to its own iconset.
--
-- 3. products gains the spec-mandated pharmacy fields. The existing
--    name + sku stay (merchant-side legacy callers); name_generic +
--    name_brand are the public-facing labels; description / indications /
--    dosage_guidance / warnings / storage carry the body content; slug
--    is the url segment; requires_prescription gates checkout; the rest
--    is metadata the customer sees.
-- =====================================================================

ALTER TABLE customers
    ADD COLUMN password_hash TEXT;

ALTER TABLE product_categories
    ADD COLUMN slug VARCHAR(120),
    ADD COLUMN icon VARCHAR(60);

CREATE UNIQUE INDEX idx_product_categories_tenant_slug
    ON product_categories (tenant_id, slug)
    WHERE slug IS NOT NULL;

-- Backfill slug from name for existing rows so the unique index doesn't
-- complain and the public endpoint can resolve every category.
UPDATE product_categories
SET slug = lower(regexp_replace(regexp_replace(name, '[^a-zA-Z0-9]+', '-', 'g'), '-+$', ''))
WHERE slug IS NULL;

ALTER TABLE products
    ADD COLUMN name_generic           TEXT,
    ADD COLUMN name_brand             TEXT,
    ADD COLUMN slug                   VARCHAR(160),
    ADD COLUMN description            TEXT,
    ADD COLUMN indications            TEXT,
    ADD COLUMN dosage_guidance        TEXT,
    ADD COLUMN warnings               TEXT,
    ADD COLUMN storage                TEXT,
    ADD COLUMN requires_prescription  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN nafdac_number          VARCHAR(40),
    ADD COLUMN brand                  TEXT,
    ADD COLUMN images                 JSONB;

CREATE UNIQUE INDEX idx_products_tenant_slug
    ON products (tenant_id, slug)
    WHERE slug IS NOT NULL;

CREATE INDEX idx_products_requires_prescription
    ON products (tenant_id, requires_prescription, active)
    WHERE active = true;

-- Backfill product slug from name so existing rows are addressable by
-- /pharmacy/products/{productSlug}.
UPDATE products
SET slug = lower(regexp_replace(regexp_replace(name, '[^a-zA-Z0-9]+', '-', 'g'), '-+$', ''))
WHERE slug IS NULL;

-- name_generic defaults to name when not set on insert; merchant-side
-- update flow can override.
UPDATE products
SET name_generic = name
WHERE name_generic IS NULL;
