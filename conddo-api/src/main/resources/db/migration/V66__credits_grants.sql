-- =====================================================================
-- V66 — Missing GRANTs for the credit-system tables (V56).
--
-- V56 created tenant_credit_accounts / credit_transactions / credit_topups
-- with RLS enabled but forgot to grant DML to ${app_role}. In production
-- this went unnoticed because the migration user IS the table owner and
-- owners bypass GRANT — but every CI run against a fresh Postgres uses a
-- non-owner role and dies with "permission denied for table
-- tenant_credit_accounts" on the first signup-side INSERT. This adds the
-- grants idempotently; safe on prod (already-effective grants are no-ops).
-- =====================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_credit_accounts TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON credit_transactions    TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON credit_topups          TO ${app_role};
