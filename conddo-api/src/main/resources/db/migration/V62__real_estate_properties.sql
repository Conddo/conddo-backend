-- =====================================================================
-- V62 — Real estate vertical: `properties` table.
--
-- A property is a real-world piece of real estate the tenant is trying
-- to sell, rent, or manage — not a SKU. Fields chosen from Nigerian
-- real estate practice: C of O / Deed / Governor's Consent surface as
-- separate document status fields; type covers the local vocabulary
-- (self-con through commercial); location tracks state + LGA + estate.
--
-- Tenant-scoped via RLS. Rich media via JSONB image array.
-- =====================================================================

CREATE TABLE properties (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,

    -- Identifying
    title                   TEXT NOT NULL,
    slug                    TEXT,
    reference_code          TEXT,  -- internal ID like "IKJ-2BR-042"

    -- Type + intent
    property_type           TEXT NOT NULL,  -- house, duplex, bungalow, apartment, self-con, land, commercial, office
    listing_type            TEXT NOT NULL,  -- sale | rent | short-let
    status                  TEXT NOT NULL DEFAULT 'draft',
                            -- draft | available | reserved | under_offer | sold | rented | archived

    -- Financial
    price                   NUMERIC(14, 2) NOT NULL,
    currency                TEXT NOT NULL DEFAULT 'NGN',
    price_negotiable        BOOLEAN NOT NULL DEFAULT TRUE,
    rent_period             TEXT,           -- annual | monthly | nightly (for short-let); NULL for sale

    -- Location (Nigerian addressing)
    address_line            TEXT,
    estate_name             TEXT,           -- e.g. "Lekki Phase 1", "Magodo GRA"
    lga                     TEXT,           -- Local Government Area
    state                   TEXT,           -- Lagos, Abuja, Rivers, etc.
    country                 TEXT NOT NULL DEFAULT 'Nigeria',
    landmark                TEXT,           -- "off Admiralty Way"
    latitude                NUMERIC(9, 6),
    longitude               NUMERIC(9, 6),

    -- Property spec
    bedrooms                INTEGER,
    bathrooms               INTEGER,
    toilets                 INTEGER,
    size_sqm                NUMERIC(10, 2),
    plot_size_sqm           NUMERIC(10, 2),
    year_built              INTEGER,
    parking_spaces          INTEGER,

    -- Features — JSONB array of strings for filter chips.
    -- Standard NG examples: "borehole", "24hr power", "gated", "swimming pool",
    -- "cctv", "gen house", "swimming pool", "gym", "furnished", "serviced".
    features                JSONB DEFAULT '[]'::jsonb,

    -- Media — array of Cloudinary URLs. First = primary hero image.
    images                  JSONB DEFAULT '[]'::jsonb,
    floor_plan_url          TEXT,
    virtual_tour_url        TEXT,           -- Matterport / walkthrough embed URL

    -- Documents (Nigerian real estate — buyer wants to see these upfront)
    has_c_of_o              BOOLEAN NOT NULL DEFAULT FALSE,  -- Certificate of Occupancy
    has_deed_of_assignment  BOOLEAN NOT NULL DEFAULT FALSE,
    has_survey_plan         BOOLEAN NOT NULL DEFAULT FALSE,
    has_governor_consent    BOOLEAN NOT NULL DEFAULT FALSE,
    has_gazette             BOOLEAN NOT NULL DEFAULT FALSE,
    document_notes          TEXT,           -- "Excision in progress", "Awaiting consent"

    -- Ownership / assignment
    listed_by_user_id       UUID,           -- staff/agent who added the listing
    owner_id                UUID,           -- FK to `owners` when the rentals module runs it

    -- Public visibility
    public                  BOOLEAN NOT NULL DEFAULT TRUE,   -- appears on the tenant's website
    featured                BOOLEAN NOT NULL DEFAULT FALSE,  -- pinned on the homepage

    -- Rich free text
    description             TEXT,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT property_type_valid CHECK (property_type IN (
        'house', 'duplex', 'bungalow', 'apartment', 'self-con',
        'land', 'commercial', 'office', 'shop', 'warehouse', 'mixed-use'
    )),
    CONSTRAINT listing_type_valid CHECK (listing_type IN ('sale', 'rent', 'short-let')),
    CONSTRAINT status_valid CHECK (status IN (
        'draft', 'available', 'reserved', 'under_offer', 'sold', 'rented', 'archived'
    )),
    CONSTRAINT price_non_negative CHECK (price >= 0)
);

-- Uniqueness of slug within a tenant so the public URL is stable.
CREATE UNIQUE INDEX properties_tenant_slug_idx ON properties (tenant_id, slug)
    WHERE slug IS NOT NULL;

-- Public catalog listing — index the hot filter (status + type + listing_type).
CREATE INDEX properties_public_available_idx
    ON properties (tenant_id, status, listing_type, property_type)
    WHERE public = TRUE AND status = 'available';

-- Recent-first dashboard read.
CREATE INDEX properties_tenant_created_idx
    ON properties (tenant_id, created_at DESC);

ALTER TABLE properties ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON properties
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true'
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
