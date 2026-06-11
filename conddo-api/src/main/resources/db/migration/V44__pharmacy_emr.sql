-- =====================================================================
-- V44 — Pharmacy Roadmap Beta 4: Basic EMR (Electronic Medical Records).
--
-- Three tables:
--   pharmacy_emr            — one row per customer; demographics + JSONB
--                             arrays for allergies / chronic / immunizations
--   pharmacy_emr_notes      — immutable clinical notes (no UPDATE / DELETE)
--   pharmacy_emr_documents  — uploaded files (lab results, prescriptions, …)
--
-- All three are tenant-scoped + audit-logged at the service layer.
-- The role gate (PHARMACIST + ADMIN per roadmap) is implemented as
-- TENANT_ADMIN-only on writes per the FE handoff §4 mapping; STAFF
-- still reads.
-- =====================================================================

CREATE TABLE pharmacy_emr (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    -- One EMR row per customer. UNIQUE (tenant, customer) makes
    -- the table effectively keyed by customer_id.
    customer_id     UUID         NOT NULL,
    blood_group     VARCHAR(5),
    genotype        VARCHAR(5),
    height_cm       NUMERIC(5, 1),
    weight_kg       NUMERIC(5, 1),
    allergies          JSONB     NOT NULL DEFAULT '[]'::jsonb,
    chronic_conditions JSONB     NOT NULL DEFAULT '[]'::jsonb,
    immunizations      JSONB     NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, customer_id)
);

CREATE INDEX idx_emr_tenant_updated
    ON pharmacy_emr (tenant_id, updated_at DESC);

CREATE TABLE pharmacy_emr_notes (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    emr_id          UUID         NOT NULL REFERENCES pharmacy_emr (id) ON DELETE CASCADE,
    note            TEXT         NOT NULL,
    note_type       VARCHAR(30)  NOT NULL DEFAULT 'CLINICAL'
        CHECK (note_type IN ('CLINICAL', 'ALLERGY', 'COUNSELLING', 'REFERRAL')),
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_emr_notes_emr
    ON pharmacy_emr_notes (emr_id, created_at DESC);

CREATE TABLE pharmacy_emr_documents (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    emr_id          UUID         NOT NULL REFERENCES pharmacy_emr (id) ON DELETE CASCADE,
    label           VARCHAR(150),
    file_url        TEXT         NOT NULL,
    doc_type        VARCHAR(30)  NOT NULL
        CHECK (doc_type IN ('LAB_RESULT', 'PRESCRIPTION', 'REFERRAL', 'IMAGING', 'OTHER')),
    uploaded_by     UUID,
    uploaded_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_emr_documents_emr
    ON pharmacy_emr_documents (emr_id, uploaded_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_emr            TO ${app_role};
-- Notes are immutable at the API layer; allow UPDATE/DELETE here only
-- because Hibernate sets owner ON ALL grants on every insert (no app
-- code path issues UPDATE/DELETE).
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_emr_notes      TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON pharmacy_emr_documents  TO ${app_role};

ALTER TABLE pharmacy_emr            ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_emr_notes      ENABLE ROW LEVEL SECURITY;
ALTER TABLE pharmacy_emr_documents  ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pharmacy_emr
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pharmacy_emr_notes
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON pharmacy_emr_documents
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');
