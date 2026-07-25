-- =====================================================================
-- V76 — Drop kyc_reviewed_by FK on tenant_payment_accounts.
--
-- V74 defined:
--     kyc_reviewed_by UUID REFERENCES users(id)
--
-- but SUPER_ADMIN reviewers live in the {@code staff_users} table, not
-- {@code users}. Every approve/reject was failing with a FK violation.
--
-- The field is a plain audit-trail pointer — we don't need referential
-- integrity here (a staff account might be deactivated later and we
-- still want to remember who approved the KYC historically). Drop the
-- FK, keep the column.
-- =====================================================================

ALTER TABLE tenant_payment_accounts
    DROP CONSTRAINT IF EXISTS tenant_payment_accounts_kyc_reviewed_by_fkey;
