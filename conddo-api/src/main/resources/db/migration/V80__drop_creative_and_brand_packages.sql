-- =====================================================================
-- V80 — remove the Conddo Studio creative-services + brand-package
-- surfaces.
--
-- The standalone Studio service has been removed from the platform.
-- These tables backed the creative-request marketplace and the
-- brand-package subscriptions, which existed only to commission Studio
-- jobs. Website generation is now handled in-process.
--
-- V29 (creative services) and V30 (brand packages) are kept in history
-- because Flyway checksums applied migrations; we drop their tables here
-- instead of deleting the files.
-- =====================================================================

DROP TABLE IF EXISTS creative_service_requests;
DROP TABLE IF EXISTS creative_service_offerings;

DROP TABLE IF EXISTS brand_package_usage;
DROP TABLE IF EXISTS brand_package_subscriptions;
DROP TABLE IF EXISTS brand_package_offerings;

-- The website change-request's soft link to a Studio job is no longer used.
ALTER TABLE website_change_requests DROP COLUMN IF EXISTS studio_job_id;
