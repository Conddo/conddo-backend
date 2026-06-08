-- =====================================================================
-- V33 — Pharmacy public order loop (PHARMACY_PUBLIC_API_SPEC §4-7).
--
-- Three new tables, all RLS-scoped (spec §2 — customer accounts are
-- tenant-scoped; carts and addresses follow the same isolation):
--
-- 1. customer_addresses — saved delivery addresses per customer. One
--    customer can have many; one is_default for the checkout pre-select.
--
-- 2. customer_carts — server-side cart, one per customer. Quantities
--    are recomputed from cart_items on read; this row only exists so we
--    have something to attach items to (and to clear in one shot on
--    successful checkout).
--
-- 3. cart_items — one row per (cart, product). The POST /cart endpoint
--    upserts; the spec says quantity is REPLACED (not summed) on
--    duplicate adds. Unique constraint on (cart_id, product_id) so the
--    upsert path is deterministic.
--
-- Order intake already exists (V10 + V25 stock-race protection). This
-- migration only adds the bits the customer-side checkout needs on top:
-- order_addresses snapshot column on orders so the delivery shipping
-- info is preserved even if the customer later edits/deletes the
-- address row, plus a few fields the spec response carries
-- (delivery_fee_kobo, payment_status, prescription_id linkage).
-- =====================================================================

CREATE TABLE customer_addresses (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    customer_id     UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    label           VARCHAR(80),                              -- "Home", "Office"
    street          TEXT         NOT NULL,
    city            TEXT,
    state           TEXT         NOT NULL,
    landmark        TEXT,
    is_default      BOOLEAN      NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_addresses_customer
    ON customer_addresses (tenant_id, customer_id, is_default DESC);

-- One cart per customer per tenant; recreate on demand if missing.
CREATE TABLE customer_carts (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    customer_id     UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, customer_id)
);

CREATE TABLE cart_items (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    cart_id         UUID         NOT NULL REFERENCES customer_carts (id) ON DELETE CASCADE,
    product_id      UUID         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    quantity        INTEGER      NOT NULL CHECK (quantity > 0),
    added_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (cart_id, product_id)
);

CREATE INDEX idx_cart_items_cart ON cart_items (cart_id);

-- Order shipping snapshot + customer-side fields. `customer_id` already
-- exists from V10 (it shipped as part of the original orders schema);
-- we only add the V33-new columns here.
ALTER TABLE orders
    ADD COLUMN address_id          UUID    REFERENCES customer_addresses (id),
    ADD COLUMN address_snapshot    JSONB,                   -- {street, city, state, landmark} captured at order time
    ADD COLUMN delivery_fee_kobo   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN payment_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                                            -- PENDING | PAID | FAILED | REFUNDED
    ADD COLUMN payment_link        TEXT,                    -- RoutePay hosted URL when applicable
    ADD COLUMN prescription_id     UUID    REFERENCES customer_prescriptions (id);

CREATE INDEX idx_orders_customer_created  ON orders (tenant_id, customer_id, created_at DESC);
CREATE INDEX idx_orders_payment_status    ON orders (tenant_id, payment_status);

GRANT SELECT, INSERT, UPDATE, DELETE ON customer_addresses TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON customer_carts     TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON cart_items         TO ${app_role};

ALTER TABLE customer_addresses ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_carts     ENABLE ROW LEVEL SECURITY;
ALTER TABLE cart_items         ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON customer_addresses
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON customer_carts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON cart_items
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
