# Conddo.io — Pharmacy Module API Spec v2

**Document type:** Backend & Frontend Engineering Spec  
**Audience:** Backend Team, Frontend Team  
**Version:** 2.0 — includes Phase 1 feature additions  
**Last updated:** June 2026  
**Status:** Active — implement before Seb&Bayor integration

---

## What Changed from v1

This version adds five new capability areas on top of the original spec:

1. **Inventory Reconciliation** — real-time sync between physical POS and online store
2. **Discount System** — product and cart-level discounts with admin approval workflow
3. **AI Product Assistant** — generate product descriptions and details from a photo
4. **Prescription & Usage Reminders** — automated Brevo SMS reminders for refills and drug schedules
5. **Refill Offers** — time-bound discounted pricing to bring customers back

All original endpoints from v1 remain unchanged. New endpoints are added below in their own sections.

---

## Base URLs

```
Public API:    https://api.conddo.io/api/v1/public/{slug}
Dashboard API: https://api.conddo.io/api/v1/dashboard/{slug}
```

---

## Authentication

| Context | Method |
|---|---|
| Website (public) | `X-Conddo-Site-Key` header |
| Customer (website user) | `Authorization: Bearer [customer-jwt]` |
| Pharmacist / Staff | `Authorization: Bearer [tenant-jwt]` |

---

## Section 1 — Store Info
*(Unchanged from v1)*

### `GET /public/{slug}/store-info`
Returns the pharmacy's public profile including name, logo, hours, contact, and active modules.

---

## Section 2 — Customer Auth
*(Unchanged from v1)*

- `POST /public/{slug}/auth/register`
- `POST /public/{slug}/auth/login`
- `GET  /public/{slug}/auth/me`
- `POST /public/{slug}/auth/forgot-password`
- `POST /public/{slug}/auth/reset-password`

---

## Section 3 — Products (Public)
*(Updated — now includes discount fields)*

### `GET /public/{slug}/pharmacy/products`

**New fields in response:**
```json
{
  "products": [
    {
      "id": "uuid",
      "nameGeneric": "Amoxicillin",
      "nameBrand": "Amoxil",
      "slug": "amoxicillin-500mg",
      "price": 1500.00,
      "discountedPrice": 1200.00,
      "discountPercent": 20,
      "discountLabel": "20% OFF",
      "discountEndsAt": "2026-06-30T23:59:00Z",
      "requiresPrescription": true,
      "stockQty": 48,
      "nafdacNumber": "A4-1234",
      "images": ["https://cdn.conddo.io/..."],
      "category": { "name": "Prescription Drugs", "slug": "prescription" },
      "isActive": true
    }
  ]
}
```

**Notes:**
- `discountedPrice` is `null` if no active discount
- Frontend should show strikethrough on `price` and highlight `discountedPrice` when discount is active
- `discountEndsAt` should be shown as a countdown timer on the website

---

### `GET /public/{slug}/pharmacy/products/{productSlug}`
*(Same update — includes discount fields)*

### `GET /public/{slug}/pharmacy/categories`
*(Unchanged)*

---

## Section 4 — Cart
*(Unchanged from v1)*

---

## Section 5 — Orders (Public)
*(Updated — applies discounts at order time)*

### `POST /public/{slug}/pharmacy/orders`

**Notes for backend:**
- Apply active discounts at order submission — use `discountedPrice` as `unitPrice` if a valid discount exists
- Snapshot the discount details into `OrderItem.snapshot` so the price is preserved even after discount expires
- All other logic unchanged from v1

---

## Section 6 — Prescriptions (Public)
*(Unchanged from v1)*

---

## Section 7 — Addresses (Public)
*(Unchanged from v1)*

---

## Section 8 — Consultations (Public)
*(Unchanged from v1)*

---

## Section 9 — Articles / Blog (Public)
*(Unchanged from v1)*

---

## Section 10 — File Upload
*(Unchanged from v1)*

---

## Section 11 — Delivery Fees (Public)
*(Unchanged from v1)*

---

## Section 12 — Dashboard API
*(Updated — new endpoints added for inventory, discounts, reminders, refill offers)*

### Products (Dashboard)
*(All v1 endpoints unchanged — new endpoints added)*

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/products` | List all products |
| `POST` | `/dashboard/{slug}/pharmacy/products` | Create product |
| `GET` | `/dashboard/{slug}/pharmacy/products/{id}` | Get product |
| `PUT` | `/dashboard/{slug}/pharmacy/products/{id}` | Update product |
| `PATCH` | `/dashboard/{slug}/pharmacy/products/{id}/toggle` | Toggle active/inactive |
| `DELETE` | `/dashboard/{slug}/pharmacy/products/{id}` | Delete product |
| `GET` | `/dashboard/{slug}/pharmacy/products/low-stock` | Low stock alerts |
| `GET` | `/dashboard/{slug}/pharmacy/products/expiring` | Expiry alerts |

**Updated POST/PUT request body — new fields:**
```json
{
  "nameGeneric": "Amoxicillin",
  "nameBrand": "Amoxil",
  "slug": "amoxicillin-500mg",
  "description": "...",
  "price": 1500.00,
  "categoryId": "uuid",
  "requiresPrescription": true,
  "stockQty": 100,
  "nafdacNumber": "A4-1234",
  "brand": "GSK",
  "images": ["https://cdn.conddo.io/..."],
  "isActive": true,
  "expiryDate": "2027-12-31",
  "reorderLevel": 10,
  "barcode": "6001234567890",
  "costPrice": 900.00
}
```

---

## Section 12A — NEW: Inventory Reconciliation

This is the system that keeps physical POS stock and online store stock in sync. There is one stock count per product. Every sale — whether walk-in POS or online order — deducts from the same number.

### Database additions needed

```sql
-- Stock movement log — every change to stock is recorded
CREATE TABLE pharmacy_stock_movements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    product_id      UUID NOT NULL,
    movement_type   VARCHAR(50) NOT NULL,
    -- Types: SALE_ONLINE, SALE_POS, RESTOCK, ADJUSTMENT,
    --        RETURN, EXPIRY_REMOVAL, TRANSFER_OUT, TRANSFER_IN
    quantity_change INTEGER NOT NULL,  -- negative for deductions
    quantity_before INTEGER NOT NULL,
    quantity_after  INTEGER NOT NULL,
    reference_id    UUID,              -- order_id, restock_id, etc.
    note            TEXT,
    created_by      UUID,              -- staff member who made the change
    created_at      TIMESTAMP DEFAULT NOW()
);

-- Reconciliation sessions — scheduled or manual stock counts
CREATE TABLE pharmacy_reconciliations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    status          VARCHAR(20) DEFAULT 'IN_PROGRESS',
    -- Statuses: IN_PROGRESS, COMPLETED, CANCELLED
    started_by      UUID NOT NULL,
    completed_by    UUID,
    started_at      TIMESTAMP DEFAULT NOW(),
    completed_at    TIMESTAMP,
    notes           TEXT
);

CREATE TABLE pharmacy_reconciliation_items (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_id   UUID NOT NULL REFERENCES pharmacy_reconciliations(id),
    product_id          UUID NOT NULL,
    system_qty          INTEGER NOT NULL,   -- what Conddo thinks is in stock
    counted_qty         INTEGER,            -- what staff physically counted
    variance            INTEGER,            -- counted - system
    resolved            BOOLEAN DEFAULT false
);
```

---

### `GET /dashboard/{slug}/pharmacy/inventory/movements`

Returns the full stock movement log for auditing.

**Auth:** Tenant JWT  
**Query params:** `productId`, `movementType`, `from`, `to`, `page`, `limit`

**Response `200`:**
```json
{
  "movements": [
    {
      "id": "uuid",
      "product": { "id": "uuid", "nameGeneric": "Amoxicillin" },
      "movementType": "SALE_ONLINE",
      "quantityChange": -2,
      "quantityBefore": 50,
      "quantityAfter": 48,
      "referenceId": "order-uuid",
      "note": "Online order #ORD-00234",
      "createdBy": { "name": "System" },
      "createdAt": "2026-06-06T10:30:00Z"
    }
  ],
  "pagination": { "page": 1, "limit": 20, "total": 340, "pages": 17 }
}
```

---

### `POST /dashboard/{slug}/pharmacy/inventory/restock`

Record a stock delivery — new products arrived, add to inventory.

**Auth:** Tenant JWT  
**Request body:**
```json
{
  "items": [
    {
      "productId": "uuid",
      "quantity": 100,
      "costPrice": 900.00,
      "expiryDate": "2027-12-31",
      "supplierNote": "Delivered by Emzor"
    }
  ],
  "note": "Monthly restock from Emzor Pharmaceuticals"
}
```

**Response `201`:**
```json
{
  "success": true,
  "restockId": "uuid",
  "itemsRestocked": 3,
  "movements": [...]
}
```

---

### `POST /dashboard/{slug}/pharmacy/inventory/adjustment`

Manually adjust stock — for damage, theft, expiry removal, counting errors.

**Auth:** Tenant JWT (Admin or Manager only)  
**Request body:**
```json
{
  "productId": "uuid",
  "adjustedQty": 45,
  "reason": "EXPIRY_REMOVAL",
  "note": "12 units expired — batch removed"
}
```

**Response `200`:**
```json
{
  "success": true,
  "product": { "id": "uuid", "nameGeneric": "Amoxicillin" },
  "quantityBefore": 57,
  "quantityAfter": 45,
  "variance": -12
}
```

**Adjustment reasons:** `EXPIRY_REMOVAL`, `DAMAGE`, `THEFT`, `COUNT_CORRECTION`, `OTHER`

---

### `POST /dashboard/{slug}/pharmacy/inventory/reconciliation/start`

Start a new reconciliation session. Locks current system stock counts as baseline.

**Auth:** Tenant JWT (Admin only)  
**Request body:**
```json
{
  "note": "Monthly physical count — June 2026"
}
```

**Response `201`:**
```json
{
  "reconciliationId": "uuid",
  "status": "IN_PROGRESS",
  "totalProducts": 84,
  "startedAt": "2026-06-06T08:00:00Z"
}
```

---

### `GET /dashboard/{slug}/pharmacy/inventory/reconciliation/{id}`

Returns the reconciliation session with all items and their system quantities.

**Auth:** Tenant JWT  
**Response `200`:**
```json
{
  "reconciliation": {
    "id": "uuid",
    "status": "IN_PROGRESS",
    "startedAt": "2026-06-06T08:00:00Z",
    "items": [
      {
        "productId": "uuid",
        "nameGeneric": "Amoxicillin",
        "systemQty": 48,
        "countedQty": null,
        "variance": null,
        "resolved": false
      }
    ]
  }
}
```

---

### `PATCH /dashboard/{slug}/pharmacy/inventory/reconciliation/{id}/count`

Submit physical count for one or more products during a reconciliation session.

**Auth:** Tenant JWT  
**Request body:**
```json
{
  "counts": [
    { "productId": "uuid", "countedQty": 46 },
    { "productId": "uuid", "countedQty": 100 }
  ]
}
```

**Response `200`:** Returns updated items with variances calculated.

---

### `POST /dashboard/{slug}/pharmacy/inventory/reconciliation/{id}/complete`

Complete the reconciliation. Conddo applies all variances and updates stock to counted quantities.

**Auth:** Tenant JWT (Admin only)  
**Response `200`:**
```json
{
  "success": true,
  "completedAt": "2026-06-06T12:00:00Z",
  "summary": {
    "totalProducts": 84,
    "matched": 71,
    "variance": 13,
    "totalVarianceUnits": -24,
    "adjustmentsApplied": 13
  }
}
```

---

### Real-time Stock Events (Redis Pub/Sub)

Every stock change publishes to Redis. The dashboard updates live without refresh.

| Event | Trigger |
|---|---|
| `stock.deducted` | Online order placed or POS sale recorded |
| `stock.restocked` | Restock recorded |
| `stock.adjusted` | Manual adjustment made |
| `stock.low` | Stock falls below `reorderLevel` |
| `stock.out` | Stock reaches zero |
| `reconciliation.variance` | Variance detected during reconciliation |

---

## Section 12B — NEW: Discount System

Discounts can be applied at product level. All discounts created by staff require approval by the Tenant Admin before going live.

### Database additions needed

```sql
CREATE TABLE pharmacy_discounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    product_id      UUID NOT NULL,
    discount_type   VARCHAR(20) NOT NULL,   -- 'PERCENTAGE' | 'FIXED'
    discount_value  DECIMAL(10,2) NOT NULL, -- 20 for 20%, 500 for ₦500 off
    label           VARCHAR(100),           -- e.g. "20% OFF", "Weekend Deal"
    starts_at       TIMESTAMP NOT NULL,
    ends_at         TIMESTAMP,              -- null = no expiry
    status          VARCHAR(20) DEFAULT 'PENDING_APPROVAL',
    -- Statuses: PENDING_APPROVAL, APPROVED, REJECTED, EXPIRED
    created_by      UUID NOT NULL,
    approved_by     UUID,
    approved_at     TIMESTAMP,
    rejection_note  TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);
```

---

### `GET /dashboard/{slug}/pharmacy/discounts`

List all discounts — filterable by status.

**Auth:** Tenant JWT  
**Query params:** `status`, `productId`, `page`, `limit`

**Response `200`:**
```json
{
  "discounts": [
    {
      "id": "uuid",
      "product": { "id": "uuid", "nameGeneric": "Vitamin C 1000mg", "price": 2000.00 },
      "discountType": "PERCENTAGE",
      "discountValue": 20,
      "discountedPrice": 1600.00,
      "label": "20% OFF",
      "startsAt": "2026-06-10T00:00:00Z",
      "endsAt": "2026-06-30T23:59:00Z",
      "status": "PENDING_APPROVAL",
      "createdBy": { "name": "Chioma Obi" },
      "approvedBy": null
    }
  ]
}
```

---

### `POST /dashboard/{slug}/pharmacy/discounts`

Create a discount. Goes into `PENDING_APPROVAL` status immediately.

**Auth:** Tenant JWT (any staff role)  
**Request body:**
```json
{
  "productId": "uuid",
  "discountType": "PERCENTAGE",
  "discountValue": 20,
  "label": "20% OFF — June Promo",
  "startsAt": "2026-06-10T00:00:00Z",
  "endsAt": "2026-06-30T23:59:00Z"
}
```

**Response `201`:**
```json
{
  "success": true,
  "discount": { "id": "uuid", "status": "PENDING_APPROVAL" },
  "message": "Discount submitted for admin approval."
}
```

Conddo notifies the Tenant Admin via dashboard notification that a discount is awaiting approval.

---

### `PATCH /dashboard/{slug}/pharmacy/discounts/{id}/approve`

Approve or reject a pending discount.

**Auth:** Tenant JWT (**Admin role only**)  
**Request body:**
```json
{
  "action": "APPROVE",
  "note": null
}
```

Or to reject:
```json
{
  "action": "REJECT",
  "note": "Price too low — minimum 10% margin required"
}
```

**Response `200`:**
```json
{
  "success": true,
  "discount": { "id": "uuid", "status": "APPROVED" }
}
```

**Notes for backend:**
- Only Admin role can call this endpoint — return `403` for any other role
- On approval, activate discount immediately if `startsAt` is in the past or now
- On rejection, notify the staff member who created it via dashboard notification
- Conddo auto-expires discounts at `endsAt` via a scheduled job

---

### `DELETE /dashboard/{slug}/pharmacy/discounts/{id}`

Cancel and delete a discount.

**Auth:** Tenant JWT (Admin only for approved discounts; creator can delete their own pending ones)

---

## Section 12C — NEW: AI Product Assistant

Reduces the burden of uploading products manually. Pharmacist takes a photo of the drug packaging, Conddo uses Claude AI to extract and generate all product details.

### `POST /dashboard/{slug}/pharmacy/ai/product-from-image`

Takes an uploaded image URL and returns AI-generated product details.

**Auth:** Tenant JWT  
**Request body:**
```json
{
  "imageUrl": "https://cdn.conddo.io/tenants/seb-bayor/uploads/amoxicillin-pack.jpg"
}
```

**Response `200`:**
```json
{
  "suggestion": {
    "nameGeneric": "Amoxicillin",
    "nameBrand": "Amoxil",
    "description": "Amoxicillin is a broad-spectrum penicillin-type antibiotic used to treat bacterial infections including respiratory tract infections, ear infections, and urinary tract infections.",
    "indications": "Upper respiratory tract infections, otitis media, urinary tract infections, skin infections",
    "dosageGuidance": "Adults: 250–500mg three times daily. Children: 25mg/kg/day in divided doses.",
    "warnings": "Do not use if allergic to penicillin or cephalosporins. Complete the full course.",
    "storage": "Store below 25°C in a dry place. Keep out of reach of children.",
    "nafdacNumber": "A4-1234",
    "brand": "GlaxoSmithKline",
    "requiresPrescription": true,
    "suggestedCategory": "prescription"
  },
  "confidence": "high",
  "note": "Please review all fields before saving. AI suggestions are not a substitute for professional verification."
}
```

**Notes for backend:**
- Call Claude API (`claude-sonnet-4-20250514`) with the image and a structured prompt
- Prompt instructs Claude to return only JSON — parse and return to frontend
- Always include `note` reminding pharmacist to verify before saving
- Frontend pre-fills the product form with these suggestions — pharmacist reviews and confirms
- `confidence` is `high`, `medium`, or `low` based on image clarity

---

### `POST /dashboard/{slug}/pharmacy/ai/description`

Generate or improve a product description from basic product name and details.

**Auth:** Tenant JWT  
**Request body:**
```json
{
  "nameGeneric": "Metformin",
  "nameBrand": "Glucophage",
  "indications": "Type 2 diabetes management"
}
```

**Response `200`:**
```json
{
  "description": "Metformin is a first-line oral antidiabetic medication used in the management of type 2 diabetes mellitus. It works by reducing hepatic glucose production and improving insulin sensitivity, helping to control blood sugar levels effectively.",
  "warnings": "Do not use in patients with renal impairment (eGFR < 30). Temporarily discontinue before contrast imaging procedures."
}
```

---

## Section 12D — NEW: Prescription & Usage Reminders

Automated SMS reminders sent via Brevo. Configured per customer by the pharmacist.

### Database additions needed

```sql
CREATE TABLE pharmacy_reminders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    customer_id     UUID NOT NULL,
    product_id      UUID,
    reminder_type   VARCHAR(30) NOT NULL,
    -- Types: REFILL_DUE, DRUG_USAGE, FOLLOW_UP, CUSTOM
    message         TEXT NOT NULL,
    scheduled_at    TIMESTAMP NOT NULL,
    recurrence      VARCHAR(20),
    -- Recurrence: ONCE, DAILY, WEEKLY, MONTHLY
    recurrence_end  TIMESTAMP,
    status          VARCHAR(20) DEFAULT 'SCHEDULED',
    -- Statuses: SCHEDULED, SENT, FAILED, CANCELLED
    sent_at         TIMESTAMP,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);
```

---

### `GET /dashboard/{slug}/pharmacy/reminders`

List all reminders — filterable by type, status, customer.

**Auth:** Tenant JWT  
**Query params:** `customerId`, `reminderType`, `status`, `page`, `limit`

---

### `POST /dashboard/{slug}/pharmacy/reminders`

Create a reminder for a customer.

**Auth:** Tenant JWT  
**Request body:**
```json
{
  "customerId": "uuid",
  "productId": "uuid",
  "reminderType": "REFILL_DUE",
  "message": "Hi {firstName}, your Amlodipine refill is due. Visit our store or order online at seb-bayorpharmaceutical.conddo.io",
  "scheduledAt": "2026-07-01T09:00:00Z",
  "recurrence": "MONTHLY",
  "recurrenceEnd": "2027-01-01T00:00:00Z"
}
```

**Template variables available in `message`:**
- `{firstName}` — customer's first name
- `{productName}` — drug generic name
- `{storeName}` — pharmacy name
- `{websiteUrl}` — tenant website URL

**Response `201`:**
```json
{
  "success": true,
  "reminder": {
    "id": "uuid",
    "status": "SCHEDULED",
    "scheduledAt": "2026-07-01T09:00:00Z",
    "nextSendAt": "2026-07-01T09:00:00Z"
  }
}
```

---

### `PATCH /dashboard/{slug}/pharmacy/reminders/{id}/cancel`

Cancel a scheduled reminder.

**Auth:** Tenant JWT  
**Response `200`:** `{ "success": true }`

---

### `GET /dashboard/{slug}/pharmacy/reminders/due`

Returns reminders due in the next 24 hours. Used by the scheduler to process outgoing SMS.

**Auth:** Internal service only (no public access)

---

### Reminder Scheduler (Background Job)

Runs every hour. Queries `pharmacy_reminders` where `status = SCHEDULED` and `scheduled_at <= NOW()`. For each:
1. Interpolate template variables in `message`
2. Send SMS via Brevo
3. Update `status` to `SENT` and `sent_at` to now
4. If `recurrence` is set and `recurrence_end` has not passed, schedule next occurrence

---

## Section 12E — NEW: Refill Offers

Time-bound discounted pricing offered to returning customers to incentivise refills.

### Database additions needed

```sql
CREATE TABLE pharmacy_refill_offers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    product_id      UUID NOT NULL,
    discount_type   VARCHAR(20) NOT NULL,   -- 'PERCENTAGE' | 'FIXED'
    discount_value  DECIMAL(10,2) NOT NULL,
    valid_days      INTEGER NOT NULL,       -- offer valid X days from last purchase
    max_uses        INTEGER DEFAULT 1,      -- per customer
    message         TEXT,                   -- SMS message to send with offer
    is_active       BOOLEAN DEFAULT true,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE pharmacy_refill_offer_claims (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    offer_id        UUID NOT NULL REFERENCES pharmacy_refill_offers(id),
    customer_id     UUID NOT NULL,
    issued_at       TIMESTAMP DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    used_at         TIMESTAMP,
    order_id        UUID
);
```

---

### `GET /dashboard/{slug}/pharmacy/refill-offers`

List all refill offers.

**Auth:** Tenant JWT

---

### `POST /dashboard/{slug}/pharmacy/refill-offers`

Create a refill offer for a product.

**Auth:** Tenant JWT  
**Request body:**
```json
{
  "productId": "uuid",
  "discountType": "PERCENTAGE",
  "discountValue": 10,
  "validDays": 30,
  "maxUses": 1,
  "message": "Hi {firstName}, refill your {productName} within {validDays} days and get 10% off. Use code {offerCode} at checkout."
}
```

---

### `POST /dashboard/{slug}/pharmacy/refill-offers/{id}/issue`

Issue a refill offer to a specific customer after their order is dispensed.

**Auth:** Tenant JWT  
**Request body:**
```json
{
  "customerId": "uuid",
  "sendSms": true
}
```

**Response `201`:**
```json
{
  "success": true,
  "claim": {
    "id": "uuid",
    "offerCode": "REFILL-XY7Z",
    "expiresAt": "2026-07-06T23:59:00Z"
  }
}
```

---

### `GET /public/{slug}/pharmacy/refill-offer/{offerCode}`

Validates an offer code at checkout.

**Auth:** Site API Key + Customer JWT  
**Response `200`:**
```json
{
  "valid": true,
  "offer": {
    "productId": "uuid",
    "discountType": "PERCENTAGE",
    "discountValue": 10,
    "expiresAt": "2026-07-06T23:59:00Z"
  }
}
```

**If invalid or expired:**
```json
{
  "valid": false,
  "reason": "EXPIRED"
}
```

---

## Section 13 — Webhooks (Routepay)
*(Unchanged from v1)*

---

## Section 14 — Refill Reminders (Scheduled)
*(Now superseded by Section 12D — Prescription & Usage Reminders. The manual nightly job in v1 is replaced by the configurable reminder system above.)*

---

## Updated Seb&Bayor Integration Checklist

Before the Seb&Bayor website goes live on Conddo:

**From v1:**
- [ ] All Section 1–11 public endpoints live and tested
- [ ] Tenant `seb-bayorpharmaceutical` registered in `tenant_sites`
- [ ] Site API key issued and stored securely
- [ ] Seb&Bayor website `app/api/` routes replaced with Conddo API calls
- [ ] Routepay keys configured for Seb&Bayor tenant
- [ ] Brevo sender configured for `info@sebandbayor.com.ng`
- [ ] MinIO bucket created for `seb-bayor` tenant
- [ ] Subdomain `seb-bayorpharmaceutical.conddo.io` pointing to Nginx
- [ ] SSL wildcard certificate covering `*.conddo.io` active
- [ ] Pharmacist dashboard account created
- [ ] Existing products seeded from Seb&Bayor's current database
- [ ] QA sign-off on all endpoints

**New in v2:**
- [ ] `pharmacy_stock_movements` table created and seeded with opening stock
- [ ] `pharmacy_discounts` table created
- [ ] `pharmacy_reminders` table created
- [ ] `pharmacy_refill_offers` and `pharmacy_refill_offer_claims` tables created
- [ ] Redis stock events publishing correctly
- [ ] AI product assistant endpoint tested with sample drug images
- [ ] Reminder scheduler deployed and tested
- [ ] Discount approval notification working in dashboard

---

## Implementation Notes

1. **Stock is always one number** — POS sale, online order, and manual adjustment all modify the same `stockQty` field. Never maintain separate physical and online stock counts.

2. **Every stock change is logged** — no silent updates to `stockQty`. Every change goes through the movement log.

3. **Discount approval is hard** — only `ADMIN` role can approve. The backend must enforce this with a role check, not just a frontend UI lock.

4. **AI suggestions are never auto-saved** — the AI assistant returns suggestions only. The pharmacist always reviews and explicitly saves. Never auto-create products from AI output.

5. **Reminder SMS templates** — always interpolate variables server-side before sending to Brevo. Never send raw template strings to customers.

6. **Refill offer codes** — generate short, uppercase, collision-resistant codes (`REFILL-XXXX`). Check for collisions before issuing.

---

*Questions? Raise on the internal board or flag to the product lead.*
