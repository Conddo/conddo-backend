-- =====================================================================
-- V34 — Pharmacy Module Spec v2 §12A: Inventory Reconciliation.
--
-- One stock count per product. Every change — online order, manual
-- adjustment, restock, reconciliation variance — runs through the
-- movement log so the audit trail is complete and the FE can subscribe
-- to live updates (Redis pub/sub on the service side).
--
-- All three tables are tenant-scoped with the standard RLS isolation
-- policy + `${app_role}` DML grants.
-- =====================================================================

-- Append-only movement log: one row per stock-changing event.
-- `quantity_change` is signed (negative on deductions, positive on
-- restocks / positive variance fixes). `quantity_before` /
-- `quantity_after` are the snapshot bounds so a reader can reconstruct
-- the stock timeline without joining back to products.
CREATE TABLE pharmacy_stock_movements (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    -- No FK on product_id: movements are an immutable audit log and
    -- must outlive product deletes. The id is still indexed for fast
    -- per-product lookups (idx_stock_movements_tenant_product_created).
    product_id      UUID         NOT NULL,
    movement_type   VARCHAR(40)  NOT NULL,
    -- Allowed: SALE_ONLINE | SALE_POS | RESTOCK | ADJUSTMENT |
    --          RECONCILIATION | RETURN | EXPIRY_REMOVAL |
    --          TRANSFER_OUT | TRANSFER_IN
    quantity_change INTEGER      NOT NULL,
    quantity_before INTEGER      NOT NULL,
    quantity_after  INTEGER      NOT NULL,
    -- Free-form pointer back to the originating row (order_id,
    -- reconciliation_id, restock_id, etc.). Nullable on direct
    -- adjustments that have no FK to point at.
    reference_id    UUID,
    reference_kind  VARCHAR(40),  -- 'ORDER' | 'RECONCILIATION' | 'RESTOCK' | 'ADJUSTMENT' | …
    note            TEXT,
    -- Staff/system actor. Null for SALE_ONLINE (the customer isn't a staff user).
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_movements_tenant_product_created
    ON pharmacy_stock_movements (tenant_id, product_id, created_at DESC);
CREATE INDEX idx_stock_movements_tenant_type_created
    ON pharmacy_stock_movements (tenant_id, movement_type, created_at DESC);

-- Reconciliation session: an open count over (some or all) products.
-- Status flow: IN_PROGRESS → COMPLETED (variances applied as movements)
--                       \-> CANCELLED (no movements; session is closed).
CREATE TABLE pharmacy_reconciliations (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    status          VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    started_by      UUID         NOT NULL,
    completed_by    UUID,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    notes           TEXT
);

CREATE INDEX idx_reconciliations_tenant_status
    ON pharmacy_reconciliations (tenant_id, status, started_at DESC);

-- Per-product reconciliation row. system_qty is the snapshot taken at
-- session start; counted_qty is what the pharmacist physically saw;
-- variance is (counted - system). Becomes the ADJUSTMENT input on
-- session completion.
CREATE TABLE pharmacy_reconciliation_items (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants (id),
    reconciliation_id UUID        NOT NULL REFERENCES pharmacy_reconciliations (id) ON DELETE CASCADE,
    -- No FK on product_id for the same reason as pharmacy_stock_movements:
    -- a reconciliation row stays intact even if the product is later removed.
    product_id        UUID        NOT NULL,
    system_qty        INTEGER     NOT NULL,
    counted_qty       INTEGER,
    variance          INTEGER,
    resolved          BOOLEAN     NOT NULL DEFAULT false,
    UNIQUE (reconciliation_id, product_id)
);

CREATE INDEX idx_reconciliation_items_session
    ON pharmacy_reconciliation_items (reconciliation_id);

-- Grants for the application role (RLS-scoped non-owner).
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_stock_movements        TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_reconciliations        TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_reconciliation_items   TO ${app_role};

-- Row-level security mirrors every other tenant-scoped table in the
-- module: NULLIF-hardened so a missing GUC fails closed (V5).
ALTER TABLE pharmacy_stock_movements      ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_reconciliations      ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_reconciliation_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pharmacy_stock_movements
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON pharmacy_reconciliations
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON pharmacy_reconciliation_items
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
