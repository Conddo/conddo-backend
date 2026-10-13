-- =====================================================================
-- V3 — drop creative-service + brand-package routing from payments.
--
-- V2 added platform-account charge kinds (CREATIVE_SERVICE,
-- BRAND_PACKAGE) plus the target-id columns for the Studio creative
-- marketplace and brand-package subscriptions. Both have been removed
-- from the platform, so charges are once again only order / booking.
--
-- V2 is kept in history (Flyway checksum); we tighten here instead.
-- =====================================================================

ALTER TABLE payments.payments DROP CONSTRAINT IF EXISTS exactly_one_target;
ALTER TABLE payments.payments DROP CONSTRAINT IF EXISTS charge_kind_valid;

DROP INDEX IF EXISTS payments.idx_payments_creative_request;
DROP INDEX IF EXISTS payments.idx_payments_brand_subscription;

ALTER TABLE payments.payments
    DROP COLUMN IF EXISTS creative_request_id,
    DROP COLUMN IF EXISTS brand_package_subscription_id;

ALTER TABLE payments.payments
    ADD CONSTRAINT exactly_one_target CHECK (
        (CASE WHEN order_id   IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN booking_id IS NOT NULL THEN 1 ELSE 0 END) = 1
    );

ALTER TABLE payments.payments
    ADD CONSTRAINT charge_kind_valid CHECK (
        charge_kind IN ('BOOKING_DEPOSIT', 'ORDER')
    );
