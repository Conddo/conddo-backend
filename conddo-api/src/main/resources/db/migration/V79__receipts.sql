-- =====================================================================
-- V79 — Receipts. A standalone entity generated from a paid invoice
-- ("Generate receipt for this paid invoice"), not a projection over
-- invoices themselves.
--
-- Design decisions:
--   * FK to invoices — the source of truth for what was charged.
--     Every receipt has exactly one invoice.
--   * Receipt numbering is per-tenant sequential, year-scoped, distinct
--     from invoice numbering (Nigerian retail convention: separate
--     books for receipts vs. invoices).
--   * Customer + amount + payment-method are snapshotted at generation
--     time so a later invoice edit doesn't rewrite receipt history.
--   * Refund is a status flip + amount, not a separate entity. Simpler
--     for the mobile app to render, one row per receipt on the ledger.
--   * Mobile app generates the UUID offline and replays POST /receipts
--     when back online — the primary key accepts a client-supplied id.
--     Enforced idempotent via UNIQUE (tenant_id, id).
-- =====================================================================

CREATE TABLE receipts (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    -- Every receipt comes from exactly one paid invoice. ON DELETE
    -- RESTRICT because you should never delete an invoice that has a
    -- receipt attached — void the invoice through the proper flow.
    invoice_id             UUID NOT NULL REFERENCES invoices(id) ON DELETE RESTRICT,

    -- Optional soft link to the order (some invoices originate from an
    -- order, others don't — services businesses issue standalone
    -- invoices without an order object).
    order_id               UUID REFERENCES orders(id) ON DELETE SET NULL,

    -- Human-facing identifier: 'RCP-2026-0001'. Per-tenant sequential
    -- + year-scoped, matches the pattern established for invoices.
    receipt_number         TEXT NOT NULL,

    -- Customer snapshot — denormalised from the invoice at generation
    -- time. Preserves audit trail even if the CRM record changes.
    customer_name          TEXT NOT NULL,
    customer_email         TEXT,
    customer_phone         TEXT,

    -- Money.
    currency               TEXT NOT NULL DEFAULT 'NGN',
    amount_kobo            BIGINT NOT NULL CHECK (amount_kobo > 0),

    -- How the customer paid — free text so new methods
    -- (transfer / cash / pos / moniepoint / opay / paystack / etc.)
    -- don't need a migration.
    payment_method         TEXT NOT NULL,

    -- External payment identifier when applicable — e.g. an
    -- incoming_transfer.provider_reference or a POS terminal id.
    payment_reference      TEXT,

    paid_at                TIMESTAMPTZ NOT NULL,
    issued_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    notes                  TEXT,

    -- Lifecycle: issued (default) / refunded (partial or full).
    -- refund_amount_kobo tracks the refunded portion; a full refund
    -- has refund_amount_kobo = amount_kobo.
    status                 TEXT NOT NULL DEFAULT 'issued'
        CHECK (status IN ('issued','refunded')),
    refund_amount_kobo     BIGINT DEFAULT 0
        CHECK (refund_amount_kobo >= 0),
    refunded_at            TIMESTAMPTZ,
    refund_reason          TEXT,

    -- Delivery tracking — one row per receipt regardless of how many
    -- times you resend it. Last sent/channel wins.
    last_sent_at           TIMESTAMPTZ,
    last_sent_channel      TEXT,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, receipt_number)
);

CREATE INDEX idx_receipts_tenant_paid
    ON receipts (tenant_id, paid_at DESC);
CREATE INDEX idx_receipts_tenant_invoice
    ON receipts (tenant_id, invoice_id);
CREATE INDEX idx_receipts_tenant_status
    ON receipts (tenant_id, status);

-- Per-tenant year-scoped counter (same pattern as invoice_sequences).
CREATE TABLE receipt_sequences (
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    year         INTEGER NOT NULL,
    last_number  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, year)
);

-- Standard tenant-isolation RLS (matches V73/V74).
ALTER TABLE receipts           ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt_sequences  ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON receipts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true'
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON receipt_sequences
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

GRANT SELECT, INSERT, UPDATE, DELETE ON receipts          TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON receipt_sequences TO ${app_role};
