-- =====================================================================
-- V73 — Invoices + invoice_lines + per-tenant sequential numbering.
--
-- One entity, two lifecycle states — an "invoice" is unpaid, a "receipt"
-- is the same row after status flips to 'paid'. Reuses one table so a
-- customer's paid document stays linked to the tenant's original bill.
--
-- Numbering: per-tenant sequential, year-scoped ({INV-2026-0001}).
-- Nigerian accounting practice resets the sequence yearly. Stored in a
-- dedicated {@code invoice_sequences} table so concurrent creates can't
-- hand the same number to two invoices.
--
-- Public token: unguessable string that gives a customer a share URL
-- ({@code app.getconddo.com/i/{token}}) without a login. Cannot be
-- reconstructed from the invoice id.
--
-- Amounts: all in kobo (integer), consistent with the rest of the
-- codebase. Tax rate lives per-line so different items on the same
-- invoice can have different VAT treatment.
-- =====================================================================

CREATE TABLE invoices (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenants(id),

    -- Per-tenant human-readable identifier — 'INV-2026-0001'. Filled by
    -- the service on insert; DB accepts NULL at first for the trigger
    -- pathway if we ever move to a DB-side sequence.
    invoice_number         TEXT NOT NULL,

    -- Optional linkage. An invoice can be created standalone (services
    -- businesses, retainers) or as one-click generation from an existing
    -- order / booking (pass 4).
    linked_order_id        UUID REFERENCES orders(id) ON DELETE SET NULL,
    linked_booking_id      UUID REFERENCES bookings(id) ON DELETE SET NULL,

    -- Customer snapshot. Denormalised on purpose — the customer's name
    -- and email at time of invoice must be preserved even if the CRM
    -- record is later updated. Deleting the customer must not orphan
    -- historical invoices.
    customer_id            UUID REFERENCES customers(id) ON DELETE SET NULL,
    customer_name          TEXT NOT NULL,
    customer_email         TEXT,
    customer_phone         TEXT,
    customer_address       TEXT,

    -- Money.
    currency               TEXT NOT NULL DEFAULT 'NGN',
    subtotal_kobo          BIGINT NOT NULL DEFAULT 0,
    tax_kobo               BIGINT NOT NULL DEFAULT 0,
    discount_kobo          BIGINT NOT NULL DEFAULT 0,
    total_kobo             BIGINT NOT NULL DEFAULT 0,

    -- Lifecycle. draft = tenant still editing; sent = link shared but
    -- not paid; paid = money in (auto or manual); overdue = past
    -- due_date, still unpaid (computed at read time OR by a nightly
    -- job); void = cancelled, kept for the audit trail.
    status                 TEXT NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft','sent','paid','overdue','void')),
    issue_date             DATE NOT NULL DEFAULT CURRENT_DATE,
    due_date               DATE,
    paid_at                TIMESTAMPTZ,
    paid_method            TEXT,      -- 'cash', 'transfer', 'routepay', 'importapay', 'other'
    payment_reference      TEXT,      -- external gateway ref when applicable

    -- Free-form notes shown to the customer + terms of trade.
    notes                  TEXT,
    terms                  TEXT,

    -- Share URL token — random, unique globally so lookups by token
    -- alone are safe (RLS is turned off for the public resolver via
    -- app.public_resolver, same shape as other public pages).
    public_token           TEXT NOT NULL UNIQUE,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, invoice_number)
);

CREATE INDEX idx_invoices_tenant_status
    ON invoices (tenant_id, status);
CREATE INDEX idx_invoices_tenant_customer
    ON invoices (tenant_id, customer_id);

CREATE TABLE invoice_lines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id        UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    tenant_id         UUID NOT NULL REFERENCES tenants(id),

    description       TEXT NOT NULL,
    quantity          NUMERIC(12, 3) NOT NULL DEFAULT 1
        CHECK (quantity > 0),
    unit_price_kobo   BIGINT NOT NULL DEFAULT 0
        CHECK (unit_price_kobo >= 0),
    -- Percent (e.g. 7.500 for Nigerian VAT). NULL = no tax on this line.
    tax_rate_percent  NUMERIC(6, 3),
    line_total_kobo   BIGINT NOT NULL DEFAULT 0,

    sort_order        INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_invoice_lines_invoice
    ON invoice_lines (invoice_id);

-- Per-tenant year-scoped counter. Rows land as the tenant issues their
-- first invoice for a given year; the service does an UPSERT with a
-- returning-clause to grab the next number atomically.
CREATE TABLE invoice_sequences (
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    year         INTEGER NOT NULL,
    last_number  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, year)
);

-- Standard tenant-isolation RLS policy, matching V56+ tables.
ALTER TABLE invoices          ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_lines     ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_sequences ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON invoices
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true'
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON invoice_lines
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true'
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON invoice_sequences
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

GRANT SELECT, INSERT, UPDATE, DELETE ON invoices          TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON invoice_lines     TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON invoice_sequences TO ${app_role};

-- ---- plan_features: gate invoicing behind Growth+ ---------------------
-- Same pattern as V67: seed one feature_key/value per plan. Growth + Pro
-- get the feature on; free / student / starter get it off. The
-- @RequiresFeature('invoicing') annotation on InvoiceController reads
-- these rows via BillingService.hasFeature.
INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT sp.id, 'invoicing',
       CASE sp.name
           WHEN 'growth' THEN 'true'
           WHEN 'pro'    THEN 'true'
           ELSE               'false'
       END
  FROM subscription_plans sp
 WHERE sp.name IN ('free','student','starter','growth','pro')
ON CONFLICT (plan_id, feature_key) DO NOTHING;
