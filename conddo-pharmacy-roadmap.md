# Conddo.io — Pharmacy Module Roadmap

**Document type:** Product Roadmap Spec  
**Audience:** Backend Team, Frontend Team, Product Lead  
**Last updated:** June 2026  
**Status:** Active

---

## Overview

This document covers everything beyond Phase 1 — the features currently in Beta or Coming Soon. It gives the full engineering team visibility into what they are building toward, so architecture decisions today do not block features tomorrow.

---

## How Features Are Labelled

| Label | Meaning |
|---|---|
| **Live** | Fully shipped — all tenants have access |
| **Beta** | Built and accessible to early-adopter tenants only — feedback being collected |
| **Coming Soon** | Visible in the dashboard as locked — tenants can click "Notify me when ready" |

---

## What Is Live at Launch

For reference — these are already specced in the API Spec v2 document.

- Inventory reconciliation (online + physical sync)
- Discount system with admin approval workflow
- Prescription and drug usage reminders (Brevo SMS)
- Refill offers
- AI product description from photo

---

## Beta Features

These are built alongside Phase 1 but released to a limited group of early-adopter pharmacy tenants. Access is manually granted by the Conddo team. The purpose is to collect real usage feedback before full release.

---

### Beta 1 — Cashback Loyalty System

**What it is:**
Customers earn a cashback percentage on every purchase. Cashback accumulates in a wallet and can be redeemed on a future order.

**Why it matters:**
Customer retention. A patient who knows they have ₦500 cashback waiting will come back to this pharmacy instead of switching.

---

**How it works:**

1. Tenant Admin sets a cashback rate (e.g. 2% on every order)
2. Customer places an order and pays
3. Conddo calculates cashback and credits the customer's wallet (after order is marked `DELIVERED` — not on placement)
4. Customer sees their wallet balance on the website and dashboard
5. At checkout, customer can apply cashback as a discount (partial or full)
6. Minimum redemption threshold configurable by tenant (e.g. minimum ₦500 before redemption)

---

**Database tables needed:**

```sql
CREATE TABLE pharmacy_loyalty_config (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL UNIQUE REFERENCES tenants(id),
    cashback_rate   DECIMAL(5,2) NOT NULL DEFAULT 2.00,  -- percentage
    min_redemption  DECIMAL(10,2) DEFAULT 500.00,
    is_active       BOOLEAN DEFAULT true,
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE pharmacy_customer_wallets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    customer_id     UUID NOT NULL,
    balance         DECIMAL(10,2) DEFAULT 0.00,
    total_earned    DECIMAL(10,2) DEFAULT 0.00,
    total_redeemed  DECIMAL(10,2) DEFAULT 0.00,
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(tenant_id, customer_id)
);

CREATE TABLE pharmacy_wallet_transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id       UUID NOT NULL REFERENCES pharmacy_customer_wallets(id),
    transaction_type VARCHAR(20) NOT NULL,
    -- Types: CASHBACK_EARNED, REDEMPTION, ADJUSTMENT, EXPIRY
    amount          DECIMAL(10,2) NOT NULL,
    reference_id    UUID,           -- order_id
    note            TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);
```

---

**API Endpoints:**

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/loyalty/config` | Get cashback config |
| `PUT` | `/dashboard/{slug}/pharmacy/loyalty/config` | Update cashback rate and settings |
| `GET` | `/dashboard/{slug}/pharmacy/loyalty/wallets` | List all customer wallets + balances |
| `GET` | `/dashboard/{slug}/pharmacy/loyalty/wallets/{customerId}` | Get single customer wallet |
| `GET` | `/public/{slug}/pharmacy/loyalty/wallet` | Customer checks their own balance |
| `POST` | `/public/{slug}/pharmacy/orders` | Updated — accepts `cashbackRedemption` amount |

**Notes for backend:**
- Cashback is credited only when order status moves to `DELIVERED`
- Redemption reduces the order total at checkout — treat as a discount on the order
- Never allow redemption balance to go negative
- Log every wallet movement in `pharmacy_wallet_transactions`

**Notes for frontend:**
- Show wallet balance prominently in customer dashboard
- At checkout, show "You have ₦X cashback available" with an apply toggle
- In the tenant dashboard, show total cashback liability outstanding (useful for tenant's financial planning)

---

### Beta 2 — Follow-Up Workflow

**What it is:**
After dispensing medication, the pharmacist schedules a follow-up check for the patient. Conddo reminds the pharmacist to follow up, and the pharmacist records the outcome.

**Why it matters:**
This is what separates a pharmacy from a drug shop. Clinical follow-up is part of pharmaceutical care. It also deepens the patient relationship and creates a reason to call.

---

**How it works:**

1. Pharmacist dispenses medication
2. Pharmacist clicks "Schedule Follow-up" on the order
3. Sets follow-up date and what to check (e.g. "Check if infection cleared, ask about side effects")
4. On follow-up date, Conddo reminds the pharmacist via dashboard notification
5. Pharmacist calls or messages the patient
6. Pharmacist records outcome on the follow-up card — "Patient recovered well", "Referred to doctor", "Side effect reported — switched medication"
7. Outcome is stored in the patient's health profile

---

**Database tables needed:**

```sql
CREATE TABLE pharmacy_followups (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    customer_id     UUID NOT NULL,
    order_id        UUID,
    product_id      UUID,
    due_date        TIMESTAMP NOT NULL,
    check_note      TEXT NOT NULL,      -- what to check on follow-up
    status          VARCHAR(20) DEFAULT 'PENDING',
    -- Statuses: PENDING, COMPLETED, MISSED, CANCELLED
    outcome         TEXT,
    outcome_type    VARCHAR(30),
    -- Types: RECOVERED, REFERRED, SIDE_EFFECT, NO_RESPONSE, OTHER
    completed_by    UUID,
    completed_at    TIMESTAMP,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);
```

---

**API Endpoints:**

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/followups` | List all follow-ups (filter by status, date) |
| `POST` | `/dashboard/{slug}/pharmacy/followups` | Create a follow-up |
| `PATCH` | `/dashboard/{slug}/pharmacy/followups/{id}/complete` | Record follow-up outcome |
| `PATCH` | `/dashboard/{slug}/pharmacy/followups/{id}/cancel` | Cancel follow-up |
| `GET` | `/dashboard/{slug}/pharmacy/followups/due-today` | Follow-ups due today (for dashboard widget) |

**Notes for frontend:**
- Show a "Follow-ups Due Today" widget on the pharmacy dashboard home screen
- Each follow-up card shows: patient name, drug, what to check, and action buttons (Complete / Missed / Cancel)
- Completed follow-ups should auto-append to the patient's health profile

---

### Beta 3 — Drug Programs

**What it is:**
A pharmacist creates a structured care program for patients with chronic conditions. A program bundles products, reminders, follow-ups, and consultations into one package the patient can subscribe to.

**Example:** A "Diabetes Care Program" might include monthly Metformin supply, weekly blood sugar check reminders, and a monthly pharmacist consultation.

**Why it matters:**
Predictable recurring revenue for the pharmacy. Predictable care for the patient. This is the pharmacy equivalent of a subscription box.

---

**How it works:**

1. Pharmacist creates a program — name, description, duration, included products, reminder schedule, consultation schedule, monthly price
2. Program is published to the website
3. Customer enrolls from the website or the pharmacist enrolls them manually
4. Conddo charges the customer monthly via Routepay recurring payment
5. Each month, products are prepared for pickup or delivery, reminders fire, and consultations are scheduled automatically
6. Pharmacist sees all enrolled patients on a program dashboard

---

**Database tables needed:**

```sql
CREATE TABLE pharmacy_programs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    target_condition VARCHAR(100),   -- e.g. "Type 2 Diabetes"
    duration_months INTEGER,         -- null = ongoing
    monthly_price   DECIMAL(10,2) NOT NULL,
    is_active       BOOLEAN DEFAULT false,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE pharmacy_program_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id  UUID NOT NULL REFERENCES pharmacy_programs(id),
    product_id  UUID NOT NULL,
    quantity    INTEGER NOT NULL DEFAULT 1,
    frequency   VARCHAR(20) DEFAULT 'MONTHLY'
);

CREATE TABLE pharmacy_program_enrollments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id      UUID NOT NULL REFERENCES pharmacy_programs(id),
    customer_id     UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    -- Statuses: ACTIVE, PAUSED, COMPLETED, CANCELLED
    enrolled_at     TIMESTAMP DEFAULT NOW(),
    next_billing_at TIMESTAMP,
    ends_at         TIMESTAMP
);
```

---

**API Endpoints:**

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/programs` | List all programs |
| `POST` | `/dashboard/{slug}/pharmacy/programs` | Create a program |
| `PUT` | `/dashboard/{slug}/pharmacy/programs/{id}` | Update program |
| `PATCH` | `/dashboard/{slug}/pharmacy/programs/{id}/publish` | Publish or unpublish |
| `GET` | `/dashboard/{slug}/pharmacy/programs/{id}/enrollments` | List enrolled patients |
| `POST` | `/dashboard/{slug}/pharmacy/programs/{id}/enroll` | Manually enroll a patient |
| `GET` | `/public/{slug}/pharmacy/programs` | List active programs (website) |
| `POST` | `/public/{slug}/pharmacy/programs/{id}/enroll` | Customer self-enrolls |

---

### Beta 4 — Electronic Medical Records (Basic)

**What it is:**
A structured digital health record per patient, created and maintained by the pharmacist. This goes deeper than the health profile already in the spec.

**Why it matters:**
Pharmacists are healthcare professionals. EMR is a core tool. It also creates deep lock-in — a pharmacy's patient records are not easily moved to another platform.

---

**What it includes:**

- Patient demographics (already in customer profile)
- Diagnoses and medical history
- Full medication history (every drug ever dispensed, with dates and dosage)
- Allergy records with severity
- Vaccination records
- Lab result uploads (PDF or image)
- Pharmacist clinical notes (timestamped, immutable)
- Prescriptions received and dispensed

---

**Database tables needed:**

```sql
CREATE TABLE pharmacy_emr (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    customer_id     UUID NOT NULL UNIQUE,
    blood_group     VARCHAR(5),
    genotype        VARCHAR(5),
    height_cm       DECIMAL(5,1),
    weight_kg       DECIMAL(5,1),
    allergies       JSONB DEFAULT '[]',
    chronic_conditions JSONB DEFAULT '[]',
    immunizations   JSONB DEFAULT '[]',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE pharmacy_emr_notes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    emr_id          UUID NOT NULL REFERENCES pharmacy_emr(id),
    note            TEXT NOT NULL,
    note_type       VARCHAR(30) DEFAULT 'CLINICAL',
    -- Types: CLINICAL, ALLERGY, COUNSELLING, REFERRAL
    created_by      UUID NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
    -- Notes are immutable — no update or delete
);

CREATE TABLE pharmacy_emr_documents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    emr_id      UUID NOT NULL REFERENCES pharmacy_emr(id),
    label       VARCHAR(150),
    file_url    TEXT NOT NULL,
    doc_type    VARCHAR(30),
    -- Types: LAB_RESULT, PRESCRIPTION, REFERRAL, IMAGING, OTHER
    uploaded_by UUID NOT NULL,
    uploaded_at TIMESTAMP DEFAULT NOW()
);
```

---

**API Endpoints:**

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/emr/{customerId}` | Get patient EMR |
| `POST` | `/dashboard/{slug}/pharmacy/emr/{customerId}` | Create EMR for patient |
| `PUT` | `/dashboard/{slug}/pharmacy/emr/{customerId}` | Update demographics |
| `POST` | `/dashboard/{slug}/pharmacy/emr/{customerId}/notes` | Add clinical note |
| `POST` | `/dashboard/{slug}/pharmacy/emr/{customerId}/documents` | Upload document |
| `GET` | `/dashboard/{slug}/pharmacy/emr/{customerId}/documents` | List documents |

**Security notes for backend:**
- EMR endpoints require `PHARMACIST` or `ADMIN` role minimum
- Clinical notes are immutable — no `PUT` or `DELETE` on notes
- Document uploads go to a private MinIO bucket — pre-signed URLs only, never public
- All EMR access is logged for audit purposes

---

## Coming Soon Features

These appear in the dashboard as locked cards with a "Notify me when ready" button. The button click is stored — the team uses this data to prioritise which feature to build next.

---

### Coming Soon 1 — Offline Mobile Inventory App

**What it is:**
A mobile app (iOS and Android) that works completely offline. Pharmacists can count stock, record deliveries, do reconciliations, and manage products without internet. Data syncs to Conddo when connection is restored.

**Why it's Coming Soon:**
Building offline-first requires a local SQLite database on the device, a sync engine, and conflict resolution logic. This is significant engineering effort and needs its own sprint.

**Key capabilities when built:**
- Barcode scanner using device camera
- Offline stock count and reconciliation
- Receive delivery (scan items, update stock)
- Low stock alerts even offline
- Auto-sync when internet returns with conflict detection

**Tech approach when ready:**
- Expo React Native (already used for Radar)
- SQLite on device via `expo-sqlite`
- Background sync via `expo-background-fetch`
- Conflict resolution: last-write-wins with manual override for large variances

---

### Coming Soon 2 — Multi-Store Management

**What it is:**
A single Conddo account that manages multiple pharmacy branches. Each branch has its own stock, staff, and POS. The owner sees everything consolidated.

**Why it's Coming Soon:**
Multi-store affects almost every other system — inventory, orders, staff accounts, analytics, reporting. It needs to be designed holistically and is a significant scope expansion.

**Key capabilities when built:**
- Create and manage multiple branch profiles
- Each branch has its own stock levels
- Stock transfer between branches
- Per-branch staff accounts and roles
- Per-branch POS
- Consolidated revenue and inventory dashboard
- Branch-level analytics

**Architecture note for backend:**
The `branch_id` field needs to be threaded through most pharmacy module tables when this is built. Design the schema now with nullable `branch_id` so the migration is clean later.

---

### Coming Soon 3 — Customer Retainer (Recurring Orders)

**What it is:**
Chronic patients subscribe to receive their medications automatically every month. Conddo charges them via Routepay recurring billing and notifies the pharmacy to prepare and dispatch.

**Why it's Coming Soon:**
Requires Routepay recurring payment integration and a subscription billing engine — different from one-time payments.

**Key capabilities when built:**
- Customer subscribes to a drug or a bundle
- Monthly automatic billing via Routepay
- Pharmacy gets a dispatch notification each cycle
- Customer can pause, modify, or cancel anytime
- Missed payment handling and retry logic

---

### Coming Soon 4 — Barcode Scanning (Web)

**What it is:**
Pharmacist opens the product creation form on desktop or tablet, clicks "Scan barcode", points the device camera at the drug packaging, and Conddo auto-fills the product details by looking up the barcode in a pharmaceutical database.

**Why it's Coming Soon:**
Requires integration with a drug barcode database (NAFDAC registry or international drug databases). The AI product assistant (already live) covers the immediate need.

---

### Coming Soon 5 — Full EMR with Regulatory Compliance

**What it is:**
An upgrade to the Basic EMR (Beta 4) that meets Nigerian healthcare data regulations, supports inter-pharmacy record sharing with patient consent, and integrates with NHIA (National Health Insurance Authority) where applicable.

**Why it's Coming Soon:**
Regulatory compliance in healthcare data is complex. This needs legal review alongside engineering work.

---

## Frontend — Coming Soon UI Spec

For every Coming Soon feature, the frontend must render a locked card in the relevant dashboard section.

**Locked card structure:**
```
[Feature Icon]
[Feature Name]
Coming Soon

[One-line description of what it does]

[Notify me when it's ready →]
```

**On "Notify me" click:**
- Call `POST /dashboard/{slug}/feature-interest` with `{ "featureKey": "multi_store" }`
- Show confirmation: "You're on the list. We'll notify you when this is ready."
- Button changes to "You're on the list ✓" — disabled, no second click

**Beta feature locked card:**
Same structure but label says **Beta** instead of Coming Soon, with:
```
[Request Beta Access →]
```

On click: `POST /dashboard/{slug}/beta-access-request` with `{ "featureKey": "cashback_loyalty" }`

---

## Feature Flag System (Backend)

Every Beta and Coming Soon feature is controlled by a feature flag per tenant.

```sql
CREATE TABLE tenant_feature_flags (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    feature_key     VARCHAR(100) NOT NULL,
    status          VARCHAR(20) DEFAULT 'coming_soon',
    -- Statuses: live, beta, coming_soon
    enabled         BOOLEAN DEFAULT false,
    granted_at      TIMESTAMP,
    granted_by      UUID,
    UNIQUE(tenant_id, feature_key)
);
```

The backend checks this table before serving any Beta endpoint. If `enabled = false`, return:

```json
{
  "error": "FEATURE_NOT_ENABLED",
  "message": "This feature is not yet available on your account.",
  "featureKey": "cashback_loyalty",
  "status": "beta"
}
```

**Feature keys:**

| Feature | Key |
|---|---|
| Cashback Loyalty | `cashback_loyalty` |
| Follow-up Workflow | `followup_workflow` |
| Drug Programs | `drug_programs` |
| Basic EMR | `emr_basic` |
| Offline Mobile App | `offline_mobile` |
| Multi-Store | `multi_store` |
| Customer Retainer | `customer_retainer` |
| Barcode Scanning | `barcode_scan` |
| Full EMR | `emr_full` |

---

## Interest Tracking Endpoints

These endpoints capture demand signals from the "Notify me" and "Request Beta Access" buttons.

### `POST /dashboard/{slug}/feature-interest`

**Auth:** Tenant JWT  
**Request body:** `{ "featureKey": "multi_store" }`  
**Response:** `{ "success": true, "message": "You're on the list." }`

---

### `POST /dashboard/{slug}/beta-access-request`

**Auth:** Tenant JWT  
**Request body:** `{ "featureKey": "cashback_loyalty" }`  
**Response:** `{ "success": true, "message": "Request received. We'll review and grant access shortly." }`

Conddo team reviews requests and manually sets `enabled = true` in `tenant_feature_flags` for approved tenants.

---

## Build Priority Order

Based on tenant feedback and Seb&Bayor's immediate needs:

| Priority | Feature | Phase |
|---|---|---|
| 1 | Inventory reconciliation | Live |
| 2 | Discount system | Live |
| 3 | AI product assistant | Live |
| 4 | Prescription reminders | Live |
| 5 | Refill offers | Live |
| 6 | Follow-up workflow | Beta |
| 7 | Cashback loyalty | Beta |
| 8 | Basic EMR | Beta |
| 9 | Drug programs | Beta |
| 10 | Multi-store | Coming Soon |
| 11 | Offline mobile app | Coming Soon |
| 12 | Customer retainer | Coming Soon |
| 13 | Barcode scanning | Coming Soon |
| 14 | Full EMR | Coming Soon |

---

*Questions? Raise on the internal board or flag to the product lead.*
