-- =====================================================================
-- V74 — Payment Infrastructure (Phase 0).
--
-- Universal payment abstraction across three providers:
--   - Paystack     : tenant -> Conddo subscriptions + program enrolments
--   - Importapay   : customer -> tenant online collections (cart, links,
--                    invoices, booking deposits)
--   - Routepay     : customer -> tenant offline / POS collections
--
-- Design principles:
--
--  1) One universal {@code payment_intents} table with a {@code provider}
--     column. Feature code never touches provider SDKs directly — it
--     creates an intent, and the {@code PaymentProvider} adapter for
--     the chosen provider drives the checkout / verification.
--
--  2) Every webhook body is persisted verbatim to {@code payment_events}
--     BEFORE any business logic runs. That guarantees replay,
--     idempotency, and a defensible audit trail if a provider ever
--     disputes what we received.
--
--  3) Tenant bank + KYC lives in {@code tenant_payment_accounts}. KYC
--     approval is admin-driven (Importapay does not enforce merchant
--     KYC for us, but we do it for our own compliance posture). Only
--     tenants with {@code kyc_status = 'approved'} can accept live
--     customer payments.
--
--  4) Money is in kobo (BIGINT) everywhere. Consistent with V56, V73.
-- =====================================================================

-- ---- tenant_payment_accounts -----------------------------------------
-- One row per tenant. Holds bank settlement destination + KYC status
-- + provider-specific merchant identifiers (Importapay merchant id,
-- Routepay terminal id) once onboarded.
CREATE TABLE tenant_payment_accounts (
    tenant_id                  UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,

    -- Bank settlement destination. Name is what the provider's name-
    -- enquiry API returned + the tenant confirmed. Never blindly
    -- accept what the tenant typed.
    bank_code                  TEXT,
    bank_name                  TEXT,
    account_number             TEXT,
    account_name               TEXT,
    account_verified_at        TIMESTAMPTZ,

    -- KYC lifecycle. Owner uploads docs -> under_review -> admin
    -- approves or rejects. Rejected accounts can re-upload and go
    -- back to under_review.
    kyc_status                 TEXT NOT NULL DEFAULT 'pending'
        CHECK (kyc_status IN ('pending','under_review','approved','rejected')),
    kyc_submitted_at           TIMESTAMPTZ,
    kyc_reviewed_at            TIMESTAMPTZ,
    kyc_reviewed_by            UUID REFERENCES users(id),
    kyc_rejection_reason       TEXT,

    -- Uploaded document URLs (media assets). All optional at row-
    -- create time; required at submission time. Enforced in the
    -- service layer, not the schema, so drafts save without complaint.
    kyc_cac_document_url       TEXT,
    kyc_director_id_url        TEXT,
    kyc_utility_bill_url       TEXT,
    kyc_business_address       TEXT,

    -- Provider merchant identifiers. Populated after Importapay /
    -- Routepay onboarding hooks return successfully.
    importapay_merchant_id     TEXT,
    routepay_merchant_id       TEXT,
    paystack_subaccount_code   TEXT,

    -- Global on/off. Only true when kyc_status='approved' AND a
    -- verified bank account is on file.
    payments_enabled           BOOLEAN NOT NULL DEFAULT FALSE,

    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_accounts_kyc_status
    ON tenant_payment_accounts (kyc_status)
    WHERE kyc_status IN ('under_review','pending');

-- ---- payment_intents -------------------------------------------------
-- Universal intent object. Every attempt at moving money — subscription
-- charge, cart checkout, invoice pay-now, booking deposit, POS sale,
-- payment link click — creates exactly one intent row.
CREATE TABLE payment_intents (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenants(id),

    -- Routing.
    provider               TEXT NOT NULL
        CHECK (provider IN ('paystack','importapay','routepay')),

    -- What produced this intent. Feature code sets this so we can
    -- fan back out to the right entity on webhook receipt.
    origin                 TEXT NOT NULL
        CHECK (origin IN ('subscription','order','booking','invoice','pos','link','other')),

    -- Optional back-references. Exactly zero or one is set depending
    -- on {@code origin}. Kept as nullable UUIDs (not polymorphic FKs)
    -- because the target tables live in different bounded contexts.
    origin_order_id        UUID REFERENCES orders(id) ON DELETE SET NULL,
    origin_booking_id      UUID REFERENCES bookings(id) ON DELETE SET NULL,
    origin_invoice_id      UUID REFERENCES invoices(id) ON DELETE SET NULL,
    origin_link_id         UUID,  -- FK added when payment_links ships
    origin_reference       TEXT,  -- free-form tenant-supplied id (e.g. POS sale id)

    -- Money.
    currency               TEXT NOT NULL DEFAULT 'NGN',
    amount_kobo            BIGINT NOT NULL CHECK (amount_kobo > 0),
    fee_kobo               BIGINT NOT NULL DEFAULT 0,
    net_kobo               BIGINT NOT NULL DEFAULT 0,

    -- Payer snapshot. Denormalised — kept even if the customer record
    -- later disappears.
    customer_id            UUID REFERENCES customers(id) ON DELETE SET NULL,
    customer_name          TEXT,
    customer_email         TEXT,
    customer_phone         TEXT,

    -- Provider handles.
    provider_reference     TEXT UNIQUE,   -- their id (e.g. Paystack reference)
    checkout_url           TEXT,           -- where we sent the customer
    authorization_code     TEXT,           -- for future recurring charges

    -- Lifecycle. pending = created but not paid; succeeded = webhook
    -- confirmed money in; failed = provider said no; expired = we
    -- gave up on a stuck intent; refunded = money went back out.
    status                 TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','succeeded','failed','expired','refunded','partially_refunded')),
    failure_reason         TEXT,

    -- Timestamps at each lifecycle transition, so we can compute
    -- provider latency + build charts without joining events.
    initiated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at           TIMESTAMPTZ,
    last_verified_at       TIMESTAMPTZ,

    -- Idempotency key supplied by the caller (order id + checkout
    -- attempt count, etc). Enforces "one intent per business action"
    -- so a double-click on Pay Now doesn't spawn two charges.
    idempotency_key        TEXT,

    metadata               JSONB,  -- provider-specific extras

    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_payment_intents_tenant_status
    ON payment_intents (tenant_id, status);
CREATE INDEX idx_payment_intents_tenant_origin
    ON payment_intents (tenant_id, origin);
CREATE INDEX idx_payment_intents_pending_stuck
    ON payment_intents (initiated_at)
    WHERE status = 'pending';

-- ---- payment_events --------------------------------------------------
-- Every webhook body, verbatim. Deduped on (provider, event_id) so a
-- provider retry storm doesn't reprocess. The controller writes the
-- row first, then hands off to the service — a crash mid-processing
-- leaves the raw event on disk for replay.
CREATE TABLE payment_events (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    provider               TEXT NOT NULL
        CHECK (provider IN ('paystack','importapay','routepay')),

    -- Provider's own event id when they include one; else a hash of
    -- the raw body. Uniqueness is enforced per-provider so two
    -- providers can share an id without collision.
    provider_event_id      TEXT NOT NULL,
    event_type             TEXT NOT NULL,

    -- Which intent this event acts on. Optional because some events
    -- (payout notifications, KYC updates) aren't intent-scoped.
    payment_intent_id      UUID REFERENCES payment_intents(id) ON DELETE SET NULL,

    -- Verbatim payload. JSONB for query support; original headers on
    -- the side for signature debugging.
    raw_body               JSONB NOT NULL,
    raw_headers            JSONB,

    -- Processing state.
    processed              BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at           TIMESTAMPTZ,
    processing_error       TEXT,
    attempts               INTEGER NOT NULL DEFAULT 0,

    received_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_payment_events_unprocessed
    ON payment_events (received_at)
    WHERE processed = FALSE;

-- ---- payouts ---------------------------------------------------------
-- Settlement records. One row per provider payout to a tenant's bank
-- account. Populated by provider payout webhooks; balance display on
-- the tenant dashboard sums succeeded intents minus fees minus paid-
-- out payouts.
CREATE TABLE payouts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants(id),

    provider              TEXT NOT NULL
        CHECK (provider IN ('paystack','importapay','routepay')),
    provider_reference    TEXT NOT NULL,

    amount_kobo           BIGINT NOT NULL CHECK (amount_kobo > 0),
    currency              TEXT NOT NULL DEFAULT 'NGN',

    -- Destination bank at time of payout — denormalised from
    -- tenant_payment_accounts so a later bank change doesn't rewrite
    -- history.
    bank_name             TEXT,
    account_number_last4  TEXT,
    account_name          TEXT,

    status                TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','processing','succeeded','failed')),
    failure_reason        TEXT,

    initiated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMPTZ,

    UNIQUE (provider, provider_reference)
);

CREATE INDEX idx_payouts_tenant_status
    ON payouts (tenant_id, status);

-- ---- payment_links ---------------------------------------------------
-- Off-platform selling surface. Tenant creates a link, drops it into
-- WhatsApp / Instagram DM, customer taps -> Importapay checkout ->
-- succeeded intent. Reusable links spawn many intents; one-time links
-- flip status to 'used' after first successful payment.
CREATE TABLE payment_links (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenants(id),

    -- Unguessable public token. URL is /pay/{token}.
    public_token           TEXT NOT NULL UNIQUE,

    title                  TEXT NOT NULL,
    description            TEXT,

    -- NULL amount = customer enters their own. Common for donations,
    -- tips, "pay any amount".
    amount_kobo            BIGINT
        CHECK (amount_kobo IS NULL OR amount_kobo > 0),

    kind                   TEXT NOT NULL DEFAULT 'reusable'
        CHECK (kind IN ('one_time','reusable')),

    status                 TEXT NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','used','expired','archived')),

    expires_at             TIMESTAMPTZ,

    -- Aggregate counters kept fresh by the payment webhook handler
    -- for cheap list-view rendering.
    total_collected_kobo   BIGINT NOT NULL DEFAULT 0,
    payment_count          INTEGER NOT NULL DEFAULT 0,

    created_by             UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_links_tenant_status
    ON payment_links (tenant_id, status);

-- Now that payment_links exists, back-fill the FK on payment_intents.
ALTER TABLE payment_intents
    ADD CONSTRAINT payment_intents_origin_link_fk
    FOREIGN KEY (origin_link_id) REFERENCES payment_links(id) ON DELETE SET NULL;

-- ---- RLS -------------------------------------------------------------
-- Standard tenant-isolation, matching V56+. payment_events has no
-- tenant_id column so it uses cross-tenant (webhook handler runs
-- with app.cross_tenant='true'); every row still ends up linked to
-- an intent, which IS tenant-scoped.

ALTER TABLE tenant_payment_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_intents         ENABLE ROW LEVEL SECURITY;
ALTER TABLE payouts                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_links           ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_events          ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenant_payment_accounts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON payment_intents
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true'
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON payouts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY tenant_isolation ON payment_links
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true'
                OR current_setting('app.public_resolver', true) = 'true')
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                OR current_setting('app.cross_tenant', true) = 'true');

CREATE POLICY cross_tenant_only ON payment_events
    USING      (current_setting('app.cross_tenant', true) = 'true')
    WITH CHECK (current_setting('app.cross_tenant', true) = 'true');

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_payment_accounts TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_intents         TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_events          TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON payouts                 TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_links           TO ${app_role};

-- ---- plan_features: gate payment_links behind Growth+ ---------------
-- Same pattern as V73. Free / Starter tenants can accept payments on
-- orders / bookings / invoices, but payment-link creation is a
-- Growth+ feature (aligns with our "off-platform selling" positioning).
INSERT INTO plan_features (plan_id, feature_key, feature_value)
SELECT sp.id, 'payment_links',
       CASE sp.name
           WHEN 'growth' THEN 'true'
           WHEN 'pro'    THEN 'true'
           ELSE               'false'
       END
  FROM subscription_plans sp
 WHERE sp.name IN ('free','student','starter','growth','pro')
ON CONFLICT (plan_id, feature_key) DO NOTHING;
