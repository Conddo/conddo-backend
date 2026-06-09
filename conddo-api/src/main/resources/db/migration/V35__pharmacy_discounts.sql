-- =====================================================================
-- V35 — Pharmacy Module Spec v2 §12B: Discount System.
--
-- Discounts are product-scoped and admin-approved. Lifecycle:
--   PENDING_APPROVAL → APPROVED → (auto) EXPIRED at ends_at
--                  \-> REJECTED (terminal)
--
-- Pricing is computed at read time: `discountedPrice` is rendered on
-- product GETs while status='APPROVED' and now() is within
-- [starts_at, ends_at]. Snapshot copies are written into
-- OrderItem.snapshot at checkout so the price the customer paid is
-- preserved after the discount expires.
-- =====================================================================

CREATE TABLE pharmacy_discounts (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    -- No FK on product_id: a discount row stays as audit even if the
    -- product is later removed. Lookups still go through an index.
    product_id      UUID         NOT NULL,
    discount_type   VARCHAR(20)  NOT NULL
        CHECK (discount_type IN ('PERCENTAGE', 'FIXED')),
    discount_value  NUMERIC(12, 2) NOT NULL CHECK (discount_value > 0),
    label           VARCHAR(120),
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING_APPROVAL'
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXPIRED')),
    created_by      UUID         NOT NULL,
    approved_by     UUID,
    approved_at     TIMESTAMPTZ,
    rejection_note  TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_discounts_tenant_status_created
    ON pharmacy_discounts (tenant_id, status, created_at DESC);
CREATE INDEX idx_discounts_tenant_product_active
    ON pharmacy_discounts (tenant_id, product_id, status, starts_at, ends_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_discounts TO ${app_role};

ALTER TABLE pharmacy_discounts ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pharmacy_discounts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
