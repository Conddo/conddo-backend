-- =====================================================================
-- Internal Operations Module — Financial metrics, accounting, and activities
-- Tracks Conddo's own startup operations, cash runway, and day-to-day progress
-- =====================================================================

-- ---- Financial Metrics Table -------------------------------------------------

CREATE TABLE studio.financial_metrics (
    id                      BIGSERIAL PRIMARY KEY,
    month                   DATE NOT NULL UNIQUE,              -- YearMonth stored as first day of month
    cash_balance            NUMERIC(19,2),
    gross_burn_rate         NUMERIC(19,2),
    net_burn_rate           NUMERIC(19,2),
    cash_runway_months      INTEGER,
    zero_cash_date          DATE,
    mrr                     NUMERIC(19,2),
    arr                     NUMERIC(19,2),
    new_mrr                 NUMERIC(19,2),
    churned_mrr             NUMERIC(19,2),
    expansion_mrr           NUMERIC(19,2),
    total_customers         INTEGER,
    new_customers           INTEGER,
    churned_customers       INTEGER,
    cac                     NUMERIC(19,2),
    ltv                     NUMERIC(19,2),
    ltv_to_cac_ratio        NUMERIC(10,2),
    cac_payback_months      INTEGER,
    net_revenue_retention   NUMERIC(5,2),
    gross_revenue_retention NUMERIC(5,2),
    created_at              DATE NOT NULL DEFAULT CURRENT_DATE,
    updated_at              DATE NOT NULL DEFAULT CURRENT_DATE
);

-- ---- Accounting Entries Table -----------------------------------------------

CREATE TABLE studio.accounting_entries (
    id              BIGSERIAL PRIMARY KEY,
    entry_number    TEXT UNIQUE,
    type            TEXT NOT NULL CHECK (type IN ('REVENUE','EXPENSE','CASH_IN','CASH_OUT','ACCRUAL','DEFERRAL')),
    category        TEXT NOT NULL CHECK (category IN (
        'SUBSCRIPTION','ONE_TIME','EXPANSION','SERVICE',
        'PAYROLL','INFRASTRUCTURE','MARKETING','SALES','LEGAL','OFFICE','SOFTWARE',
        'INVESTMENT','LOAN','GRANT','REFUND','PAYMENT'
    )),
    description     TEXT NOT NULL,
    amount          NUMERIC(19,2) NOT NULL,
    entry_date      DATE NOT NULL,
    recognized_date  DATE,
    related_entity  TEXT,
    reference       TEXT,
    status          TEXT NOT NULL,
    notes           TEXT,
    created_at      DATE NOT NULL DEFAULT CURRENT_DATE,
    updated_at      DATE NOT NULL DEFAULT CURRENT_DATE
);

-- ---- Operational Activities Table -------------------------------------------

CREATE TABLE studio.operational_activities (
    id              BIGSERIAL PRIMARY KEY,
    title           TEXT NOT NULL,
    description     TEXT NOT NULL,
    category        TEXT NOT NULL CHECK (category IN ('PRODUCT','ENGINEERING','SALES','MARKETING','OPERATIONS','FINANCE','HR','LEGAL')),
    status          TEXT NOT NULL CHECK (status IN ('PLANNED','IN_PROGRESS','BLOCKED','COMPLETED','CANCELLED')),
    start_date      DATE,
    target_date     DATE,
    completed_date  DATE,
    priority        INTEGER NOT NULL DEFAULT 3 CHECK (priority >= 1 AND priority <= 5),
    assigned_to     TEXT,
    tags            TEXT,
    progress_notes  TEXT,
    created_at      DATE NOT NULL DEFAULT CURRENT_DATE,
    updated_at      DATE NOT NULL DEFAULT CURRENT_DATE
);

-- ---- Indexes for Performance -------------------------------------------------

CREATE INDEX idx_financial_metrics_month ON studio.financial_metrics(month DESC);
CREATE INDEX idx_accounting_entries_date ON studio.accounting_entries(entry_date DESC);
CREATE INDEX idx_accounting_entries_type ON studio.accounting_entries(type);
CREATE INDEX idx_accounting_entries_category ON studio.accounting_entries(category);
CREATE INDEX idx_operational_activities_status ON studio.operational_activities(status);
CREATE INDEX idx_operational_activities_category ON studio.operational_activities(category);
CREATE INDEX idx_operational_activities_target_date ON studio.operational_activities(target_date);
CREATE INDEX idx_operational_activities_assigned_to ON studio.operational_activities(assigned_to);

-- ---- Comments for Documentation ----------------------------------------------

COMMENT ON TABLE studio.financial_metrics IS 'Monthly financial metrics for Conddo startup operations - cash runway, burn rate, ARR/MRR, unit economics';
COMMENT ON TABLE studio.accounting_entries IS 'Basic accounting entries for internal financial tracking with accrual accounting support';
COMMENT ON TABLE studio.operational_activities IS 'Day-to-day operational activities and progress tracking for Conddo team';

COMMENT ON COLUMN studio.financial_metrics.month IS 'YearMonth stored as first day of month (e.g., 2024-01-01 for January 2024)';
COMMENT ON COLUMN studio.financial_metrics.cash_runway_months IS 'Current cash balance divided by net burn rate';
COMMENT ON COLUMN studio.financial_metrics.zero_cash_date IS 'Projected date when cash balance reaches zero';
COMMENT ON COLUMN studio.financial_metrics.ltv_to_cac_ratio IS 'Customer lifetime value divided by customer acquisition cost';
COMMENT ON COLUMN studio.financial_metrics.net_revenue_retention IS 'Revenue retained from existing customers including expansion (NRR)';
COMMENT ON COLUMN studio.financial_metrics.gross_revenue_retention IS 'Revenue retained from existing customers excluding expansion (GRR)';

COMMENT ON COLUMN studio.accounting_entries.entry_number IS 'Auto-generated unique identifier (e.g., ACC-2024-1234)';
COMMENT ON COLUMN studio.accounting_entries.type IS 'Entry type: REVENUE, EXPENSE, CASH_IN, CASH_OUT, ACCRUAL, DEFERRAL';
COMMENT ON COLUMN studio.accounting_entries.category IS 'Category for reporting and analysis';
COMMENT ON COLUMN studio.accounting_entries.recognized_date IS 'For accrual accounting - when revenue/expense should be recognized';
COMMENT ON COLUMN studio.accounting_entries.related_entity IS 'Reference to related entity (customer, invoice, etc.)';

COMMENT ON COLUMN studio.operational_activities.priority IS 'Priority level: 1=highest, 5=lowest';
COMMENT ON COLUMN studio.operational_activities.assigned_to IS 'Staff member or team responsible';
COMMENT ON COLUMN studio.operational_activities.tags IS 'Comma-separated tags for filtering and organization';
