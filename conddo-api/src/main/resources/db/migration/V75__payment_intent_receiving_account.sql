-- =====================================================================
-- V75 — Add receiving-account fields to payment_intents.
--
-- Importapay is a bank-transfer PSP, not a card-checkout PSP. Instead
-- of a redirect to a hosted checkout page, they hand us a static
-- receiving-account (bank + account number + name) at intent creation
-- time. We show those details to the customer, the customer transfers,
-- and we confirm the transfer with sender-side info.
--
-- Storing the account on the intent means the customer-facing page
-- can render it deterministically on every visit without re-hitting
-- the provider.
-- =====================================================================

ALTER TABLE payment_intents
    ADD COLUMN receiving_bank_name        TEXT,
    ADD COLUMN receiving_account_number   TEXT,
    ADD COLUMN receiving_account_name     TEXT,
    -- Sender info supplied by the customer at confirm time. Persisting
    -- so we don't have to re-collect on retries, and so the provider's
    -- match-attempt trail lives with the intent for support.
    ADD COLUMN sender_bank_name           TEXT,
    ADD COLUMN sender_account_number      TEXT,
    -- Provider's matched inbound-transaction reference once resolved.
    ADD COLUMN matched_transaction_ref    TEXT;
