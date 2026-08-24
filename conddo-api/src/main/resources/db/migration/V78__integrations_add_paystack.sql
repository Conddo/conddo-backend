-- =====================================================================
-- V78 — Allow 'paystack' as a tenant integration provider.
--
-- V77's CHECK constraint locked provider to {moniepoint, opay}. The
-- mobile app also wants to store a tenant's own Paystack secret so
-- customer payments can route to the tenant's Paystack merchant
-- account (same model as Moniepoint / OPay).
--
-- Platform-billing Paystack (CONDDO_PAYSTACK_SECRET_KEY) is unrelated —
-- that keeps living in SSM.
-- =====================================================================

ALTER TABLE tenant_integrations
    DROP CONSTRAINT IF EXISTS tenant_integrations_provider_check;

ALTER TABLE tenant_integrations
    ADD CONSTRAINT tenant_integrations_provider_check
    CHECK (provider IN ('moniepoint','opay','paystack'));

ALTER TABLE incoming_transfers
    DROP CONSTRAINT IF EXISTS incoming_transfers_provider_check;

ALTER TABLE incoming_transfers
    ADD CONSTRAINT incoming_transfers_provider_check
    CHECK (provider IN ('moniepoint','opay','paystack'));
