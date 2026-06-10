-- =====================================================================
-- V40 — Drop the FK on media_assets.uploaded_by so customers can be
-- recorded as uploaders too (PHARMACY_PUBLIC_API_SPEC §10 public
-- upload endpoint, shipped Slice 7).
--
-- The column stays — it's still useful audit metadata. We just stop
-- pinning it to staff users so customer ids (from the customer-JWT
-- public flow) don't collide with the constraint.
-- =====================================================================

ALTER TABLE media_assets DROP CONSTRAINT media_assets_uploaded_by_fkey;
