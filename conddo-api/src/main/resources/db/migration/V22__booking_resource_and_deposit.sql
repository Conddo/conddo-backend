-- =====================================================================
-- Music Studio MS-2 — per-resource bookings + deposit tracking.
--
-- A booking now optionally references:
--   * a resource (a studio room / booth / lesson slot — soft FK to the
--     tenant's inventory_products row, so the rate-per-hour lives in
--     one place and the dashboard's resource view doesn't need a new
--     model);
--   * a session type (RECORDING / MIXING / MASTERING / PODCAST /
--     REHEARSAL / LESSON / OTHER) — drives the Kanban grouping and
--     the AI prompt that picks copy patterns.
--
-- Deposit tracking is a side-channel on Booking so the same row can
-- carry "you owe ₦25,000; ₦10,000 in deposit has been paid" without
-- a separate join. The actual payment row lives in conddo-payments;
-- the deposit_status flips when payments' webhook callback fires.
-- =====================================================================

ALTER TABLE bookings
    ADD COLUMN resource_id          UUID,
    ADD COLUMN session_type         TEXT,
    ADD COLUMN deposit_amount_kobo  BIGINT,
    ADD COLUMN deposit_status       TEXT NOT NULL DEFAULT 'NONE'
        CHECK (deposit_status IN ('NONE','PENDING_DEPOSIT','DEPOSIT_PAID','REFUNDED'));

-- Resource-overlap lookups: "are there any bookings on Studio A
-- between 14:00 and 18:00 next Saturday that aren't cancelled?"
-- Single composite index — the service-side check uses status +
-- resource_id + the time window.
CREATE INDEX idx_bookings_resource_window
    ON bookings (tenant_id, resource_id, starts_at, ends_at)
    WHERE resource_id IS NOT NULL AND status NOT IN ('cancelled');
