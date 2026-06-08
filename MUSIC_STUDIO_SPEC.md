# Music Studio Vertical — Spec

**Status**: Phase 1 BE shipped (V22 migration, deposit flow,
`BookingEvent` DTO extensions, `VerticalSignupSeeder.seedMusicStudio`).
Phase 1 FE shipped 2026-06-08 (`/sessions` resource-grouped board,
`NewSessionModal` with deposit checkout). Phase 2 = manifest entry +
`/sessions` sidebar surface for music-studio tenants. Phase 3 (later) =
public customer-website API like Pharmacy has.

**Audience**: BE team (manifest wiring), Studio team (future pubic API
endpoints), product (pricing decisions on deposit-first bookings).

---

## 1. Why this vertical is different

Music studios live or die on **bookings + deposits**. A typical Lagos /
Abuja studio loses 20-40% of nominal bookings to no-shows: a customer
reserves an evening session, doesn't show, the room sits idle, the
engineer gets paid for nothing. Conddo's killer feature for this
vertical is **deposit-at-booking**: a customer can't lock the slot
without paying a partial fee through RoutePay first. If they don't pay,
the slot doesn't reserve.

Everything else (orders, customers, payments dashboard, analytics)
works the same as other verticals. The vertical-specific shape lives in
**bookings** — and the FE surface for that is `/sessions`.

---

## 2. Vertical entry — already shipped

`VerticalToolMatrix.java` already has:

```java
Map.entry("music-studio", tiers(
        List.of("website", "crm", "bookings", "payments", "inventory", "analytics"),
        List.of("staff", "marketing.social", "marketing.email",
                "marketing.sms", "projects"),
        List.of("marketing.ads", "music-school"))),
```

> ⚠ **BE update needed** to surface `/sessions` in the music-studio
> sidebar: add `sessions.music-studio` to the starter tier in
> `VerticalToolMatrix.java` and a matching section in
> `ManifestCatalogue.java`:
>
> ```java
> // VerticalToolMatrix.java — music-studio starter tier:
> List.of("website", "crm", "bookings", "sessions.music-studio",
>         "payments", "inventory", "analytics"),
>
> // ManifestCatalogue.java — alongside the other deep-dive sections:
> section("sessions", "Sessions", "headphones", "/sessions", 58,
>         "sessions.music-studio");
> ```
>
> `headphones` is already in
> [`conddo-app/lib/manifest/icons.ts`](../conddo-app/lib/manifest/icons.ts).
> The FE page is shipped at
> [`conddo-app/app/sessions/page.tsx`](../conddo-app/app/sessions/page.tsx)
> — flips on the moment the manifest carries the toolId.

---

## 3. Data model — already shipped (V22 migration)

The `bookings` table got four new nullable columns. These are **always
null on non-music-studio bookings**, so the legacy bookings page for
beauty / professional-services / etc. renders unchanged:

```sql
ALTER TABLE bookings
    ADD COLUMN resource_id          UUID,                          -- soft FK to inventory_products.id
    ADD COLUMN session_type         TEXT,                          -- see §3.1
    ADD COLUMN deposit_amount_kobo  BIGINT,
    ADD COLUMN deposit_status       TEXT NOT NULL DEFAULT 'NONE'
        CHECK (deposit_status IN ('NONE','PENDING_DEPOSIT','DEPOSIT_PAID','REFUNDED'));

CREATE INDEX idx_bookings_resource_window
    ON bookings (tenant_id, resource_id, starts_at, ends_at)
    WHERE resource_id IS NOT NULL AND status NOT IN ('cancelled');
```

### 3.1 Session type enum

Stored as `TEXT`, validated at the service layer against this set:

| Value | UI label | Used by |
|---|---|---|
| `RECORDING` | Recording | Live rooms, vocal booths — most common |
| `MIXING` | Mixing | Mixing engineer sessions |
| `MASTERING` | Mastering | Mastering engineer sessions |
| `PODCAST` | Podcast | Podcast booths, 2-mic setups |
| `REHEARSAL` | Rehearsal | Band rehearsal rooms |
| `LESSON` | Lesson | Music-school tier (vocal coaching, instrument lessons) |
| `OTHER` | Other | Fallback |

The FE drives chip colour + icon from this value. New types can be
added without an FE change — the chip falls back to "Other" styling
for unknown values.

### 3.2 Resources = inventory products

A music studio's **rooms / booths** live in the existing `products`
table. Each product's `price` is the **hourly rate**. The seeder
already creates three sample rooms on signup (Studio A — Live Room +
SSL + Neumann ₦25k/hr, Studio B — Vocal Booth ₦15k/hr, Podcast Booth
₦10k/hr).

This means no new schema for "rooms" — just a different lens on
inventory. The `/sessions` FE filters inventory to active products and
groups bookings under them.

### 3.3 Deposit status lifecycle

```
                  NONE                  (default — non-music-studio bookings stop here)
                   │
   collect deposit ▼
            PENDING_DEPOSIT             (slot reserved; RoutePay checkout open)
                   │
       webhook ✓   ▼
            DEPOSIT_PAID                (slot locked in; session is real)
                   │
   refund event ▼ (or session cancel)
            REFUNDED                    (terminal — deposit returned)
```

The deposit row itself lives in `conddo-payments` (separate service);
the `deposit_status` column on `bookings` flips when payments' webhook
callback fires. The deposit_amount_kobo is captured at-init so we can
compare against what was actually paid.

---

## 4. Endpoints — already shipped

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/bookings/range?from=&to=` | Returns `BookingEvent[]` with the new fields (resourceId, sessionType, depositAmountKobo, depositStatus). Legacy bookings keep their pre-MS-2 shape — new fields are just `null`. |
| GET | `/api/v1/bookings/{id}` | Same with deposit fields. |
| POST | `/api/v1/bookings` | `CreateBookingRequest` now accepts optional `resourceId` + `sessionType`. Non-music-studio tenants just don't send them. |
| **POST** | **`/api/v1/bookings/init-with-deposit`** | Music-studio's killer flow. Body: `InitBookingWithDepositRequest`. Returns `{booking, checkoutUrl}` — the FE redirects to RoutePay. Booking lands as `PENDING_DEPOSIT`; flips to `DEPOSIT_PAID` on the webhook callback. |

### 4.1 InitBookingWithDepositRequest shape (live)

```java
public record InitBookingWithDepositRequest(
        UUID customerId,                       // either customerId or customerName
        String customerName,
        @NotBlank @Email String customerEmail, // required — RoutePay needs an email
        @NotNull UUID resourceId,
        String sessionType,
        @NotNull OffsetDateTime start,
        @NotNull OffsetDateTime end,
        String service,
        BigDecimal amount,
        @Positive long depositAmountKobo,
        String returnUrl,                      // where RoutePay redirects after pay
        String notes
) {}
```

The FE constructs `returnUrl` from `window.location.origin + "/sessions"`
so the customer lands back on the studio's dashboard view of the booking.

### 4.2 Resource overlap check

The service layer should reject a request when there's an existing
non-cancelled booking on the same resource that overlaps `[start, end)`
— 409 `RESOURCE_DOUBLE_BOOKED` with the conflicting booking id.

The composite index `idx_bookings_resource_window` makes the lookup
cheap. If the BE hasn't enforced this yet, it's a gap to close: today's
FE doesn't have a calendar conflict UI, so the BE catching it server-
side is the safety net.

---

## 5. FE work — Phase 1 shipped 2026-06-08

### 5.1 `/sessions` page

Resource-grouped board, one section per active inventory product
(studio room). Each section shows that room's bookings for the
selected day sorted by start time. KPI strip above: total sessions,
revenue booked, deposits collected (₦), deposits pending (count —
warning chip if non-zero).

Day navigation: Prev / Today / Next via `dayOffset` state.

Each session card shows:
- Session type chip (icon + label, colour by type)
- Deposit status chip (when not NONE)
- Customer name (linked to /customers/{id} when there's an id)
- Time range (`fmtRange`)
- Optional amount (₦)
- Optional notes (clamped to 2 lines)
- "Resend deposit link" CTA when `PENDING_DEPOSIT` — toast says
  "coming soon" until the BE ships the resend endpoint (see §6.1)

"Unassigned" catch-all column: bookings created via the generic
/bookings flow that don't have a `resourceId`. Useful so existing
data isn't hidden when a music studio's first dashboard view loads.

Graceful states:
- `inventory_products` is empty for the tenant → "No studios set up
  yet" empty state pointing at `/inventory` to add rooms
- BE 5xx → empty state via QueryBoundary
- BE 403 PLAN_UPGRADE_REQUIRED → PlanGate (gated on `bookings`
  feature which is Growth+)

### 5.2 `NewSessionModal`

Schedules a session — two paths share the same form:

**Without deposit** — POST `/bookings`. Used for established
customers, lessons, etc. No checkout, just creates the booking.

**With deposit** (default, recommended) — POST
`/bookings/init-with-deposit`. Returns `checkoutUrl`; FE redirects to
RoutePay; customer pays; webhook flips status to `DEPOSIT_PAID` and
locks the slot.

Form fields:
- Customer picker (with email auto-fill when CRM has it)
- Customer email (required when deposit on; RoutePay receipt)
- Studio / room dropdown — sourced from `inventoryApi.list()`, each
  option labelled with its hourly rate
- Session type — 7-option chip grid (RECORDING / MIXING / MASTERING /
  PODCAST / REHEARSAL / LESSON / OTHER), each with its lucide icon
- Date + start time + duration (preset hours: 1, 2, 3, 4, 6, 8)
- Live cost summary: `{duration}h × {rate}/hr = ₦total`
- Deposit toggle (default ON) — when ON, deposit amount input with
  "50% of total" quick-set chip; suggested = 50% of session total
- Notes (optional)

Validation: customer required, email required when deposit-on,
resource + date + time required, deposit must be > 0 and ≤ total.

---

## 6. Phase 2 — manifest wiring (one-line BE change)

**BE punchlist for this week** (drops the music-studio FE deep-dive
into the sidebar):

1. Edit `conddo-core/.../registry/VerticalToolMatrix.java` —
   add `sessions.music-studio` to music-studio's starter tier.
2. Edit `conddo-core/.../registry/ManifestCatalogue.java` —
   `section("sessions", "Sessions", "headphones", "/sessions", 58,
   "sessions.music-studio");`.

That's it. The FE page is shipped; the icon mapping is shipped; the
JWT-refresh on next login carries `sessions.music-studio` in
activeModules; the sidebar resolves the section and the link appears.

### 6.1 Optional Phase 2 BE endpoint

`POST /api/v1/bookings/{id}/resend-deposit-link` — issues a fresh
RoutePay checkout URL for a `PENDING_DEPOSIT` booking and (optionally)
re-sends it to the customer's email / WhatsApp. The FE has the button
already; it just toasts "coming soon" until this lands.

Behaviour:
- Returns 404 if booking has `deposit_status != 'PENDING_DEPOSIT'`
- Returns `{checkoutUrl}` — same shape as the deposit-init endpoint
- Writes a `BookingActivity` row "Resent deposit link"

---

## 7. Phase 3 — public customer-website API (later)

Music studios will eventually want their own website (like Seb&Bayor's
pharmacy site) where customers browse rooms + book sessions
themselves. That's a future spec; same shape as
[`PHARMACY_PUBLIC_API_SPEC.md`](./PHARMACY_PUBLIC_API_SPEC.md) with
endpoints like:

```
GET  /api/v1/public/{slug}/studio/rooms              # the room catalogue
GET  /api/v1/public/{slug}/studio/availability       # available slots per room
POST /api/v1/public/{slug}/studio/sessions           # book + deposit init in one call
GET  /api/v1/public/{slug}/studio/sessions/{id}      # status / receipt
```

Riding on the same `tenant_sites` + Site-API-Key infrastructure
WEBSITE_INTEGRATION_SPEC.md describes. Not blocking Phase 1.

---

## 8. Pricing / plan gates

Music studio fits the existing tier shape:

| Plan | Music-studio tenant gets |
|---|---|
| Launcher | Website, CRM, Payments, Analytics. Bookings + Inventory **GATED** (matches the rest of the platform — see [BILLING_TIERS_SPEC.md](./BILLING_TIERS_SPEC.md)). |
| Growth | + Bookings + Inventory + `/sessions` deep-dive + Staff + Marketing tools |
| Scaler | + projects (multi-room session tracking, engineer payroll, etc.) + the music-school sub-tier |

`/sessions` is gated on the `bookings` feature key. A music-studio
tenant on Launcher hits `/sessions` → 403 PLAN_UPGRADE_REQUIRED → FE
PlanGate renders "Sessions — Growth plan is required" (consistent
with how Pharmacy + Fashion handle their gated screens).

In practice **no music studio should be on Launcher** — Conddo
onboarding should default them to Growth at signup. That's a one-line
change in the signup wizard's plan-suggestion logic.

---

## 9. What still uses the generic /bookings page

The generic [`/bookings`](../conddo-app/app/bookings/page.tsx) page is
still there for music-studio tenants — it's just the same data through
a different lens (week grid, no per-room grouping). Use cases:
- Tenants browsing availability across all rooms in one view
- The customer self-book widget (Phase 3, public site)

The new `/sessions` page is **additive**, not a replacement.

---

## 10. Tests (BE side)

When BE wires the manifest entry:
- Manifest for a music-studio Growth tenant must include the
  `sessions` section (smoke test in `AuthFlowTest.java` style).
- The init-with-deposit endpoint must reject a `resourceId` that
  isn't in the tenant's `products` table → 422.
- Concurrent init-with-deposit on the same resource + overlapping
  time → 409 `RESOURCE_DOUBLE_BOOKED` (assuming §4.2 is enforced).
- Deposit webhook arrival flips status to `DEPOSIT_PAID` exactly
  once (idempotent on duplicate webhook deliveries).

---

## 11. Out of scope (v1)

- Multi-resource bookings (one session spanning 2 rooms — band +
  vocalist).
- Recurring bookings ("every Tuesday 6-8pm for 8 weeks").
- Engineer assignment + payroll tracking — that's the `projects`
  tool which lands in the business tier and is its own spec.
- Music-school sub-tier (lesson plans, student progress, recurring
  monthly fees) — Scaler-only, separate spec.
- Equipment hire (gear rental on top of studio time).

---

*FE shipped: `lib/api/bookings.ts` MS-2 type extensions,
`components/app/NewSessionModal.tsx`, `app/sessions/page.tsx`.
Companion BE work: one-line manifest update outlined in §6.*
