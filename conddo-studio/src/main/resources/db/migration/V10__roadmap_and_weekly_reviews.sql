-- =====================================================================
-- Product Roadmap Planner & Weekly Metric Reviews
-- Strategic planning and weekly performance tracking for Conddo startup
-- =====================================================================

-- ---- Roadmap Items Table --------------------------------------------------

CREATE TABLE studio.roadmap_items (
    id              BIGSERIAL PRIMARY KEY,
    title           TEXT NOT NULL,
    description     TEXT NOT NULL,
    category        TEXT NOT NULL CHECK (category IN ('PRODUCT','ENGINEERING','DESIGN','MARKETING','SALES','OPERATIONS','INFRASTRUCTURE','SECURITY')),
    priority        TEXT NOT NULL CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    status          TEXT NOT NULL CHECK (status IN ('PLANNED','IN_PROGRESS','BLOCKED','COMPLETED','CANCELLED','DEFERRED')),
    target_date     DATE NOT NULL,
    start_date      DATE,
    completed_date  DATE,
    assigned_to     TEXT,
    quarter         TEXT,
    estimated_hours INTEGER,
    actual_hours    INTEGER,
    dependencies    TEXT,
    success_criteria TEXT,
    notes           TEXT,
    created_at      DATE NOT NULL DEFAULT CURRENT_DATE,
    updated_at      DATE NOT NULL DEFAULT CURRENT_DATE
);

-- ---- Weekly Metric Reviews Table ---------------------------------------------

CREATE TABLE studio.weekly_metric_reviews (
    id                      BIGSERIAL PRIMARY KEY,
    week_start_date         DATE NOT NULL UNIQUE,
    week_end_date           DATE NOT NULL,
    cash_balance            NUMERIC(19,2),
    net_burn_rate           NUMERIC(19,2),
    cash_runway_months      INTEGER,
    mrr                     NUMERIC(19,2),
    arr                     NUMERIC(19,2),
    total_customers         INTEGER,
    new_customers_this_week INTEGER,
    churned_customers_this_week INTEGER,
    cac                     NUMERIC(19,2),
    ltv                     NUMERIC(19,2),
    ltv_to_cac_ratio        NUMERIC(10,2),
    net_revenue_retention   NUMERIC(5,2),
    active_users            INTEGER,
    daily_active_users      INTEGER,
    new_signups             INTEGER,
    support_tickets         INTEGER,
    churn_rate              NUMERIC(5,2),
    highlights              TEXT,
    concerns                TEXT,
    key_learnings           TEXT,
    action_items            TEXT,
    blockers                TEXT,
    reviewed_by             TEXT,
    created_at              DATE NOT NULL DEFAULT CURRENT_DATE,
    updated_at              DATE NOT NULL DEFAULT CURRENT_DATE
);

-- ---- Indexes for Performance -------------------------------------------------

CREATE INDEX idx_roadmap_items_status ON studio.roadmap_items(status);
CREATE INDEX idx_roadmap_items_category ON studio.roadmap_items(category);
CREATE INDEX idx_roadmap_items_priority ON studio.roadmap_items(priority);
CREATE INDEX idx_roadmap_items_quarter ON studio.roadmap_items(quarter);
CREATE INDEX idx_roadmap_items_target_date ON studio.roadmap_items(target_date);
CREATE INDEX idx_roadmap_items_assigned_to ON studio.roadmap_items(assigned_to);

CREATE INDEX idx_weekly_reviews_week_start ON studio.weekly_metric_reviews(week_start_date DESC);
CREATE INDEX idx_weekly_reviews_week_end ON studio.weekly_metric_reviews(week_end_date);

-- ---- Comments for Documentation ----------------------------------------------

COMMENT ON TABLE studio.roadmap_items IS 'Product roadmap items for strategic planning and milestone tracking';
COMMENT ON TABLE studio.weekly_metric_reviews IS 'Weekly metric reviews for tracking startup performance over time with analysis';

COMMENT ON COLUMN studio.roadmap_items.quarter IS 'Quarter assignment for roadmap planning (e.g., Q1 2024, Q2 2024)';
COMMENT ON COLUMN studio.roadmap_items.estimated_hours IS 'Estimated effort in hours for planning purposes';
COMMENT ON COLUMN studio.roadmap_items.actual_hours IS 'Actual hours spent for tracking accuracy';
COMMENT ON COLUMN studio.roadmap_items.dependencies IS 'Comma-separated list of dependent roadmap items or external factors';
COMMENT ON COLUMN studio.roadmap_items.success_criteria IS 'Clear definition of what completion looks like';

COMMENT ON COLUMN studio.weekly_metric_reviews.week_start_date IS 'Start date of the week being reviewed (typically Monday)';
COMMENT ON COLUMN studio.weekly_metric_reviews.week_end_date IS 'End date of the week being reviewed (typically Sunday)';
COMMENT ON COLUMN studio.weekly_metric_reviews.highlights IS 'Key wins and positive developments from the week';
COMMENT ON COLUMN studio.weekly_metric_reviews.concerns IS 'Areas needing attention or showing negative trends';
COMMENT ON COLUMN studio.weekly_metric_reviews.key_learnings IS 'Important insights discovered during the week';
COMMENT ON COLUMN studio.weekly_metric_reviews.action_items IS 'Specific actions to take based on the review';
COMMENT ON COLUMN studio.weekly_metric_reviews.blockers IS 'Obstacles preventing progress that need resolution';
