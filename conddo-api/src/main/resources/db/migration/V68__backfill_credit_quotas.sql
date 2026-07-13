-- =====================================================================
-- V68 — Backfill monthly_quota on existing tenant credit accounts to
-- match the tier's V67 budget.
--
-- V67 renamed launcher→starter and scaler→pro on `tenant_credit_accounts.tier`
-- but deliberately did NOT touch `monthly_quota` — mid-cycle windfalls felt
-- risky before we'd shipped the auto-downgrade path. Now that auto-downgrade
-- is in place (BillingService.downgradeToFree), it's safe to align the
-- quota with the tier so a legacy Starter gets its 500 credits/month and a
-- legacy Pro (formerly Scaler) gets its 10,000.
--
-- Idempotent — re-running is a no-op after the first apply.
-- =====================================================================

UPDATE tenant_credit_accounts SET monthly_quota =   100 WHERE tier = 'free'    AND monthly_quota <>   100;
UPDATE tenant_credit_accounts SET monthly_quota =   300 WHERE tier = 'student' AND monthly_quota <>   300;
UPDATE tenant_credit_accounts SET monthly_quota =   500 WHERE tier = 'starter' AND monthly_quota <>   500;
UPDATE tenant_credit_accounts SET monthly_quota =  3000 WHERE tier = 'growth'  AND monthly_quota <>  3000;
UPDATE tenant_credit_accounts SET monthly_quota = 10000 WHERE tier = 'pro'     AND monthly_quota <> 10000;
