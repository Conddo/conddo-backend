-- V61 — Persist the tenant's website vibe from onboarding step 5.
--
-- The FE captures a freeform vibe prompt ("warm and trustworthy",
-- "bold and colourful, green accents") but until now it stayed in the
-- FE store and never reached the BE. Storing on tenants (not
-- tenant_sites) because it's a business-level intent — surviving site
-- resets, informing future AI passes (marketing copy, product blurbs)
-- beyond the initial site generation.
--
-- Nullable — pre-V61 tenants have no vibe on file; the generator's
-- fallback prompt handles that (uses vertical + business name only).
ALTER TABLE tenants
    ADD COLUMN website_vibe TEXT;
