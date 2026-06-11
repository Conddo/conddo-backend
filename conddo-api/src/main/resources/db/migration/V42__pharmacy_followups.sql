-- =====================================================================
-- V42 — Pharmacy Roadmap Beta 2: Follow-up Workflow.
--
-- After dispensing medication, the pharmacist schedules a clinical
-- check ("how's the rash?", "did the antibiotic clear it?"). Conddo
-- reminds the pharmacist when the check_date arrives; they record the
-- outcome and the system optionally appends an immutable note onto
-- the patient's EMR (Beta 4).
--
-- Cross-tenant carve-out so the hourly missed-sweep cron can flip
-- PENDING → MISSED for rows whose due_date passed 48h+ ago without an
-- outcome.
-- =====================================================================

CREATE TABLE pharmacy_followups (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    -- No FK on customer/product/order — keep follow-up rows after any of
    -- those are deleted (clinical audit trail).
    customer_id     UUID         NOT NULL,
    order_id        UUID,
    product_id      UUID,
    due_date        TIMESTAMPTZ  NOT NULL,
    check_note      TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'COMPLETED', 'MISSED', 'CANCELLED')),
    outcome         TEXT,
    outcome_type    VARCHAR(30)
        CHECK (outcome_type IS NULL OR outcome_type IN (
            'RECOVERED', 'REFERRED', 'SIDE_EFFECT', 'NO_RESPONSE', 'OTHER')),
    completed_by    UUID,
    completed_at    TIMESTAMPTZ,
    created_by      UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_followups_tenant_status_due
    ON pharmacy_followups (tenant_id, status, due_date);
CREATE INDEX idx_followups_tenant_customer
    ON pharmacy_followups (tenant_id, customer_id, due_date DESC);
CREATE INDEX idx_followups_pending_due
    ON pharmacy_followups (status, due_date)
    WHERE status = 'PENDING';

GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_followups TO ${app_role};

ALTER TABLE pharmacy_followups ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pharmacy_followups
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
