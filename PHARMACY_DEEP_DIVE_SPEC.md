# Pharmacy Deep Dive — Backend Spec

**Status**: FE shipped 2026-06-05. BE work blocks E2E for any pharmacy tenant.
**Owners**: BE team. FE contract in
[`conddo-app/lib/api/prescriptions.ts`](../conddo-app/lib/api/prescriptions.ts)
and
[`conddo-app/lib/api/inventory.ts`](../conddo-app/lib/api/inventory.ts) (the
new `expiryDate` field).

The pharmacy vertical needs three things real users will touch on day one:
**Prescriptions** (dispensing log), **expiry-aware inventory** (don't sell
expired drugs), and **refill reminders** (SMS when a repeat script is due).
Phase 1 is the minimum to launch this weekend; Phase 2 is the automation /
batch-tracking we want shortly after.

The FE assumes the endpoints below exist and will surface `"Coming soon"` /
empty states if they 500 (the standard `isServerError` fallback). When the
BE deploys this work, the FE just starts rendering real data — no FE change
needed.

---

## Phase 1 — ship this weekend

### 1. `Prescription` entity

New tenant-scoped table. Standard RLS policy (`tenant_id` column + GUC
binding via `TenantContext` — follow the rule in
[ACTION_LIST.md §3](./ACTION_LIST.md#L130)).

```
prescriptions
  id                    UUID PK
  tenant_id             UUID NOT NULL          -- RLS scope
  customer_id           UUID NOT NULL          -- FK customers(id)
  medication            VARCHAR(160) NOT NULL  -- e.g. "Lisinopril 10mg"
  dosage                VARCHAR(120) NULL      -- e.g. "1 tablet daily"
  quantity              INTEGER NULL
  refill_interval_days  INTEGER NULL           -- NULL → one-off
  notes                 TEXT NULL
  issued_at             TIMESTAMPTZ NOT NULL DEFAULT now()
  last_filled_at        TIMESTAMPTZ NULL
  next_refill_due       DATE NULL              -- generated/computed (see below)
  created_at, updated_at  -- standard
```

`next_refill_due` derivation:
- If `refill_interval_days IS NULL` → `next_refill_due = NULL` (one-off)
- Else if `last_filled_at IS NOT NULL` → `last_filled_at::date + refill_interval_days`
- Else (never filled) → `issued_at::date + refill_interval_days`

Implement as a generated column if Postgres allows it for the case
expressions; otherwise recompute in the service layer on every write (the
read path stays a plain SELECT).

Indexes:
- `(tenant_id, next_refill_due)` — list filters frequently sort by this
- `(tenant_id, customer_id)` — customer detail page will fetch by customer

### 2. `Product.expiry_date` (+ `batch_number`)

Pharmacy inventory needs to know when stock expires. Smallest change that
unblocks the FE:

```sql
ALTER TABLE products
  ADD COLUMN expiry_date    DATE        NULL,
  ADD COLUMN batch_number   VARCHAR(80) NULL;
```

Index: `(tenant_id, expiry_date) WHERE expiry_date IS NOT NULL` for the
"expiring within N days" filter.

**FE wire shape** (already used in
[`lib/api/inventory.ts`](../conddo-app/lib/api/inventory.ts)):
```json
{
  "id": "…",
  "name": "Paracetamol 500mg",
  "expiryDate": "2026-09-30",   // YYYY-MM-DD, null if not set
  "batchNumber": "BATCH-2024-A" // optional (used in Phase 2)
}
```

`CreateProductRequest` + `UpdateProductRequest` should accept optional
`expiryDate` (`LocalDate`) and `batchNumber`. On PATCH, an explicit `null`
clears the value (the FE sends `null` when the user clears the date on edit).

`GET /api/v1/inventory/products` gains a `expiringWithinDays=N` query param:
matches products where `expiry_date <= today + N days` (includes already
expired).

### 3. Prescriptions endpoints

All `@PreAuthorize("hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")`
except where noted.

| Method | Path | Body | Returns | Notes |
|---|---|---|---|---|
| GET | `/api/v1/prescriptions` | – | `Prescription[]` | Query params: `search`, `status`, `customerId`, `page`, `size` |
| GET | `/api/v1/prescriptions/{id}` | – | `Prescription` | |
| GET | `/api/v1/prescriptions/summary` | – | `{total, dueSoon, overdue, oneOff}` | Counts only; cheap query |
| POST | `/api/v1/prescriptions` | `CreatePrescriptionRequest` | `Prescription` | `customerId` xor `customerName` (creates the customer if `customerName` only) |
| PATCH | `/api/v1/prescriptions/{id}` | `UpdatePrescriptionRequest` | `Prescription` | |
| DELETE | `/api/v1/prescriptions/{id}` | – | 204 | `TENANT_ADMIN` only |
| POST | `/api/v1/prescriptions/{id}/fill` | – | `Prescription` | Stamps `last_filled_at = now()`, recomputes `next_refill_due`, writes activity row |
| POST | `/api/v1/prescriptions/{id}/remind` | `{message?}` | 204 | Fires SMS via `SmsSender` (same pattern as `OrderService.remind`) — see §5 |

**Status filter values** (server-side bucketing):
- `active` → `refill_interval_days IS NOT NULL AND (next_refill_due IS NULL OR next_refill_due > today + 3)`
- `due_soon` → `next_refill_due BETWEEN today AND today + 3` (inclusive)
- `overdue` → `next_refill_due < today`
- `one_off` → `refill_interval_days IS NULL`

**Wire shape — must match exactly** (FE types in
[`prescriptions.ts`](../conddo-app/lib/api/prescriptions.ts)):
```json
{
  "id": "f7c2…",
  "customerId": "8e1d…",
  "customerName": "Chinedu Okafor",
  "customerPhone": "+2348012345678",
  "medication": "Lisinopril 10mg",
  "dosage": "1 tablet daily",
  "quantity": 30,
  "notes": null,
  "issuedAt": "2026-05-10T09:14:00Z",
  "lastFilledAt": "2026-05-10T09:14:00Z",
  "refillIntervalDays": 30,
  "nextRefillDue": "2026-06-09"
}
```

### 4. `Customer` join on response

`customerName` and `customerPhone` come from the joined `customers` row.
Don't denormalize — read through the foreign key. (Pattern: see
`OrderCard.from(OrderView)` which already joins.)

### 5. SMS reminder — reuse the existing pipe

`POST /prescriptions/{id}/remind` should mirror `OrderService.remind(orderId,
message)`:
- Look up the prescription + customer phone
- If phone is blank → 422 `{ code: "no_customer_phone", message: "Customer has no phone on file." }`
- Build the message:
  - With explicit body → use it verbatim
  - Without → `"Hi {firstName}, your {medication} refill is due {humanDate(next_refill_due)}. Reply to confirm a pickup time. — {tenantName}"`
- `smsSender.send(phone, text)`
- Write an activity row (we'll need a `prescription_activity` table mirroring
  `order_activity`, OR fold prescription reminders into a generic
  `customer_activity` log — your call)
- Return 204 on success

Soft de-dup: don't send to the same phone for the same prescription twice
within 12 hours — guard with a `last_reminded_at` column on `prescriptions`,
or check the activity log.

### 6. Manifest is already wired

`prescriptions` toolId is already in pharmacy's starter tier
([VerticalToolMatrix.java:35](conddo-core/src/main/java/io/conddo/core/registry/VerticalToolMatrix.java#L35))
and the manifest section exists
([ManifestCatalogue.java:37](conddo-core/src/main/java/io/conddo/core/registry/ManifestCatalogue.java#L37)).
No manifest changes needed.

### 7. Seeder

Extend `VerticalSignupSeeder.seedPharmacy()` to insert 3-5 sample
prescriptions for the seeded customers so new pharmacy tenants land on a
populated screen (one repeat, one overdue, one one-off — gives the demo
shape). Also set `expiry_date` on the seeded inventory items (1 expired, 1
expiring in 14 days, the rest fresh) so the expiry banner has something to
say.

### 8. Tests (mirror existing module coverage)

- Repository: tenant isolation (a tenant cannot read another's prescriptions)
- Service: `next_refill_due` derivation across the three input shapes
- Service: `fill` updates `last_filled_at` and recomputes `next_refill_due`
- Controller: each endpoint round-trip
- Controller: `remind` 422 when customer has no phone
- Controller: 403 for `STAFF` on DELETE
- Migration: `expiry_date` accepts null, NULL clears on PATCH

---

## Phase 2 — automation, after launch

### A. Scheduled refill reminders

Cron job that picks up prescriptions due in 3 days and fires the SMS
reminder automatically.

```
@Scheduled(cron = "0 0 9 * * *", zone = "Africa/Lagos")  // 9am daily
public void runRefillReminders() {
  for each tenant with prescriptions WHERE next_refill_due IN (today, today+1, today+2, today+3):
    if no reminder sent in last 24h for that prescription:
      send SMS (same template as Phase 1 §5)
      log to prescription_activity
}
```

Add a per-tenant setting `pharmacy.auto_refill_reminders_enabled` (default
`true`) so opted-out tenants don't auto-blast. Surface that as a settings
toggle on the FE (out of scope for Phase 1; create the column now, FE picks
it up later).

Idempotency: a `prescription_reminder_sent_at` column OR a unique
`(prescription_id, sent_date)` index on the activity table.

### B. Batch / lot tracking

`expiry_date` on `products` is a per-SKU date — fine for small pharmacies
where a SKU rotates as a unit. Bigger pharmacies want per-batch dates
because they restock continuously and old + new batches coexist.

```
product_batches
  id              UUID PK
  tenant_id       UUID NOT NULL  -- RLS
  product_id      UUID NOT NULL FK
  batch_number    VARCHAR(80) NOT NULL
  expiry_date     DATE NOT NULL
  quantity        INTEGER NOT NULL DEFAULT 0
  received_at     TIMESTAMPTZ
```

`product.expiry_date` becomes a derived `MIN(expiry_date)` across all
non-zero batches; `product.stock` becomes `SUM(quantity)`. Adjusting stock
deducts from the oldest-expiring batch first (FEFO).

This is a bigger refactor — punt to Phase 2 deliberately. Phase 1's
single `expiry_date` is the right level of detail for the weekend launch.

### C. Drug-drug interaction check (later)

The empty-state copy already promises "interaction checks" but that's a
dataset / vendor decision (OpenFDA, RxNorm, etc). No work item here yet —
just leaving the placeholder so the BE team knows the FE language is set.

---

## Why this shape

- **Prescription as its own entity, not a Customer field**: a customer can
  have many concurrent scripts; conflating them onto the customer row
  forces a single-medication assumption.
- **`refill_interval_days INTEGER NULL` instead of an enum**: future-proof
  for any cadence (weekly, biweekly, custom 45-day) without enum migrations.
- **Reuse `SmsSender`, not a new reminder service**: the SMS plumbing
  already exists for `OrderService.remind`. Build the cron on top of the
  same primitive — no new vendor integration, no new failure mode.
- **Single `expiry_date` per product first, batches second**: 90% of
  Nigerian pharmacies are small enough that per-SKU expiry tracking is
  enough to launch. Punt batch tracking to Phase 2 to avoid blocking real
  users on a model they may not even need.

---

## Out of scope

- Insurance billing
- Pharmacist-only roles (use existing `STAFF` until we hear demand)
- Controlled-substance audit trail (separate compliance work)
- Multi-pharmacy chains (one tenant = one pharmacy for now)
