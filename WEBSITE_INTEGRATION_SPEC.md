# Tenant Website Integration — Backend Spec

**Status**: FE shipped 2026-06-05 (Website page → Developer Integration panel:
masked API key + reveal/copy/regenerate, QA status chip, quick-start
snippet). BE work blocks real public-API traffic from tenant sites.

**Source product doc**:
[`conddo-website-integration.md`](../conddo-website-integration.md).

**FE contract**:
[`conddo-app/lib/api/website.ts`](../conddo-app/lib/api/website.ts) (`TenantSite`
type, `/website/site` + `/website/site/regenerate-key` endpoints).

The FE assumes `/website/site` exists and renders the panel from its shape;
if it 404s or 5xxs the panel shows "your site's developer toolkit appears
here once Studio registers your website" — no crash.

---

## ⚠ Plan name correction

The source product doc uses **Starter / Growth / Business** for the hosting
matrix. Those names predate the rebrand to **Launcher / Growth / Scaler**
(see [BILLING_TIERS_SPEC.md](./BILLING_TIERS_SPEC.md) +
[conddo-pricing-tiers.md](../conddo-pricing-tiers.md)). Mapping confirmed
with product:

| Old name (source doc) | New canonical name |
|---|---|
| Starter | **Launcher** |
| Business | **Growth** |
| (old) Growth — "subdomain or custom" | Collapsed into Launcher/Growth |
| — | **Scaler** (custom hosting per agreement, new) |

**Corrected hosting matrix** — use this everywhere:

| Plan | Hosting | Domain |
|---|---|---|
| Launcher | Conddo-managed (Vercel) | `tenant.conddo.io` |
| Growth   | 9stacks                 | `tenant.com.ng` (free via 9stacks partnership) + business email |
| Scaler   | Custom per agreement    | Custom domain |

Treat the source doc's plan-name references as superseded; everything else
in it stands.

---

## Phase 1 — ship to enable public tenant sites

### 1. `tenant_sites` table

```sql
CREATE TABLE tenant_sites (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants(id),
    subdomain         VARCHAR(100) UNIQUE,            -- e.g. 'seb-bayor-pharmacy'
    custom_domain     VARCHAR(255) UNIQUE,            -- e.g. 'sebandbayor.com.ng'
    hosting_provider  VARCHAR(50),                    -- 'conddo' | 'vercel' | '9stacks'
    site_type         VARCHAR(50),                    -- 'custom_built' | 'template'
    api_key_hash      VARCHAR(255) NOT NULL,          -- bcrypt of the key, NOT the plaintext
    api_key_last4     VARCHAR(4) NOT NULL,            -- for the masked display "sk_live_••••••••a3f2"
    is_active         BOOLEAN NOT NULL DEFAULT false,
    qa_approved       BOOLEAN NOT NULL DEFAULT false,
    qa_approved_by    UUID REFERENCES staff_users(id),
    qa_approved_at    TIMESTAMPTZ,
    submitted_url     VARCHAR(500),
    created_at        TIMESTAMPTZ DEFAULT now(),
    updated_at        TIMESTAMPTZ DEFAULT now()
);
CREATE UNIQUE INDEX idx_tenant_sites_tenant ON tenant_sites (tenant_id);  -- 1 site / tenant for now
```

**Important deviation from the source doc**: store `api_key_hash` (bcrypt),
not the plaintext. The plaintext is only ever returned **once** — at creation
or regeneration — and shown to the TENANT_ADMIN. After that, only the masked
form (`sk_live_••••••••` + last 4 chars) is retrievable. This is the same
pattern AWS / Stripe use for API keys; the source doc's "VARCHAR(255) NOT
NULL UNIQUE" implies plaintext storage which is a security smell.

Key format: `sk_live_` + 32 random URL-safe base64 chars. Add `sk_test_`
prefix for non-prod environments so a key can't accidentally cross
environments.

### 2. Tenant-facing endpoints

Tenant dashboard endpoints (require the standard JWT auth).

| Method | Path | Auth | Body | Returns | Notes |
|---|---|---|---|---|---|
| GET | `/api/v1/website/site` | TENANT_ADMIN, STAFF | – | `TenantSite` | TENANT_ADMIN gets `apiKey` (plaintext) IF it was just regenerated in this session — otherwise null. Everyone gets `apiKeyMasked`. 404 when no row exists yet. |
| POST | `/api/v1/website/site/regenerate-key` | TENANT_ADMIN only | – | `TenantSite` | Rotates key. Returns the new plaintext exactly once (in `apiKey`); subsequent GETs only return masked. Old key invalidated immediately. |

The "one-time plaintext" pattern is what the FE expects — the panel keeps
the new key in memory after regenerate, but a page refresh loses it. UX is
identical to Stripe.

**Wire shape** (matches FE `TenantSite` type):
```json
{
  "id": "f7c2…",
  "tenantId": "8e1d…",
  "subdomain": "seb-bayor-pharmacy",
  "customDomain": null,
  "hostingProvider": "vercel",
  "siteType": "custom_built",
  "apiKey": "sk_live_AbCd…32chars",   // ONLY non-null on POST regenerate (and only on TENANT_ADMIN GET in that session)
  "apiKeyMasked": "sk_live_••••••••a3f2",
  "isActive": true,
  "qaApproved": false,
  "qaApprovedAt": null,
  "submittedUrl": "https://staging.seb-bayor.com.ng",
  "createdAt": "2026-06-01T10:00:00Z"
}
```

### 3. Public endpoints — what tenant websites call

All routed under `/api/v1/public/{slug}/...`. Auth via the
`X-Conddo-Site-Key` header (the key created above). Resolve flow:

1. Parse `{slug}` from the path.
2. Pull `tenant_sites.api_key_hash` for that slug; bcrypt-compare against
   the header value. **Constant-time** compare; 401 on mismatch.
3. Check `tenant_sites.is_active` AND `tenant_sites.qa_approved`. If either
   is false → 403 `SITE_NOT_LIVE`. (Pre-QA sites can only be hit from the
   Studio reviewer's session — see §6 for the workaround.)
4. Bind `TenantContext` to the resolved tenant_id (Postgres GUC) for the
   rest of the request.
5. Apply per-key rate limiting (token bucket; suggested 120 req/min/key).
   429 with `Retry-After` on overflow.

| Method | Path | Body | Returns | Module gate |
|---|---|---|---|---|
| GET | `/api/v1/public/{slug}/store-info` | – | `{name, logo, address, phone, hours, social}` | Always-on |
| GET | `/api/v1/public/{slug}/announcements` | – | `Announcement[]` | Always-on (returns `[]` if not used) |
| GET | `/api/v1/public/{slug}/pharmacy/products` | – | `Product[]` (public-safe fields only) | `inventory.pharmacy` toolId required |
| GET | `/api/v1/public/{slug}/pharmacy/categories` | – | `Category[]` | `inventory.pharmacy` |
| GET | `/api/v1/public/{slug}/pharmacy/availability` | – | `Slot[]` (next 14 days) | `bookings` toolId — Phase 2 (Growth plan only) |
| POST | `/api/v1/public/{slug}/pharmacy/orders` | `{items, customer, delivery_address}` | `{id, status, total}` | `inventory.pharmacy` — **stock recheck at submit, FOR UPDATE lock** |
| POST | `/api/v1/public/{slug}/pharmacy/consultations` | `{patient, contact, slot, reason}` | `{id, status}` | `bookings` |

**Stock race condition** (called out in the source doc): order submission
must `SELECT ... FOR UPDATE` the product rows in `inventory.pharmacy`,
re-verify each item's stock vs the cart, and rollback the transaction on
shortage with a structured 409:
```json
{ "error": "STOCK_SHORTAGE", "items": [{"productId": "abc", "available": 1, "requested": 2}] }
```
The FE retries are cheaper than the customer placing a bad order.

**Public-safe fields only**. The internal Product entity has
`reorderThreshold`, `lowStock`, expiry batches, etc. The public DTO strips
these to: `{id, name, sku, price, stock_available: boolean, category, image_url}`.
Stock is a boolean not an integer — leaks less business intel and is what
shopping carts actually care about.

### 4. Module gating per plan

The public endpoint should gate on **both** axes:
- The tenant's `verticalToolMatrix` activates the module (e.g. `inventory.pharmacy`)
- The tenant's plan unlocks it (e.g. Growth+ for `bookings`, Launcher+ for
  `inventory.pharmacy`)

Reuse the `@RequiresFeature` annotation from
[BILLING_TIERS_SPEC.md §5](./BILLING_TIERS_SPEC.md#5-feature-gating) — apply
to each public controller method. On miss return 403
`PLAN_UPGRADE_REQUIRED`, **but** for public traffic include a developer-
friendly message: "This module isn't enabled on the merchant's plan." (No
upgrade CTA — the customer browsing the merchant's site can't upgrade
anything.)

### 5. Subdomain routing (DevOps)

Wildcard DNS:
```
A    *.conddo.io    →    [Vercel / load-balancer IP]
```

Wildcard SSL via Let's Encrypt DNS challenge (one cert covers everything):
```bash
certbot certonly --dns-cloudflare -d "*.conddo.io" -d "conddo.io"
```

The Nginx pattern in the source doc still applies (extracts `$tenant`
slug, sets `X-Tenant-Slug` header). The Next.js frontend at port 3000 reads
that header on every request and uses it as the resolved tenant identity
for the public site code path. (Note: this is NOT the same as the dashboard
auth flow — dashboard uses JWT; public-site path uses subdomain + per-site
API key.)

**Open Q: Growth `.com.ng` domains via 9stacks** — until the 9stacks
provisioning API is confirmed (open product item), Studio team registers
custom_domain rows manually. The DNS and SSL for those domains live on
9stacks' side; we only need to know about them via `tenant_sites.custom_domain`.

### 6. Pre-QA preview access

QA reviewers need to open the site before `qa_approved=true`. Two
acceptable workarounds:

- **Reviewer header**: the public endpoint accepts an `X-Conddo-QA-Token`
  header issued to logged-in `SUPER_ADMIN` Studio reviewers. When present
  and valid, bypass the `qa_approved` gate. Token short-lived (1h) and
  scoped to a specific site by the issuing endpoint.
- **Preview slug**: an opt-in `preview-` slug prefix (e.g.
  `preview-seb-bayor.conddo.io`) that serves the site regardless of approval
  status, with a "PREVIEW — NOT LIVE" injected banner. Studio team shares
  this URL with the QA reviewer.

Either works. Pick one; the source doc doesn't specify.

### 7. WebSocket endpoint (defer to Phase 2)

`wss://api.conddo.io/ws/public/{slug}` for push updates. Phase 1 sites can
poll the relevant GETs every 30-60s — covers stock + availability for
launch. WebSocket adds non-trivial infra (sticky sessions / Redis pub-sub)
that we don't need yet.

### 8. Tests

- Auth: missing key → 401. Wrong key → 401 in constant time.
- Auth: valid key, `is_active=false` → 403 SITE_NOT_LIVE.
- Auth: valid key, `qa_approved=false` → 403 SITE_NOT_LIVE.
- Tenant isolation: key for tenant A cannot read tenant B's data
  (even by passing B's slug in the URL).
- Stock race: two concurrent POST /pharmacy/orders against a stock=1
  product → one succeeds, one 409 STOCK_SHORTAGE.
- Rate limit: 121st request in a minute → 429 with `Retry-After`.
- Regenerate key: old key returns 401 immediately; new key works.
- Public DTO scrub: response for /pharmacy/products does NOT contain
  `reorderThreshold`, `cost`, or any other internal field.

---

## Phase 2 — automation + scale

### A. 9stacks provisioning API

If 9stacks confirms an API:
- Move custom_domain provisioning into the upgrade flow
- POST to 9stacks at `tenant_subscriptions.status = active && plan = growth`
- Webhook from 9stacks → flip `tenant_sites.custom_domain` + `hosting_provider = '9stacks'`
- Trigger DNS record on our side (the SLD points the .com.ng at the
  tenant's Vercel deploy)

Until then, Studio team handles manually — `tenant_sites.custom_domain` is
populated by a Studio admin endpoint.

### B. WebSocket push for live data

`wss://api.conddo.io/ws/public/{slug}` with channels:
- `stock.updated` — every `inventory_adjustments` row writes a message
- `availability.updated` — every booking change in the next 14 days
- `order.status` — order state changes (websites can show real-time order
  tracking to logged-in customers later)

Auth: same `X-Conddo-Site-Key` (as a query param on the WS handshake, since
custom headers don't work in browser WS).

### C. Per-key analytics

A `tenant_site_requests` table or Prometheus counter for:
- requests / day / endpoint / key
- 4xx + 5xx rates
- avg latency

Surface on `/website` as "API usage" alongside the existing visits/enquiries
KPIs. Helps the tenant know whether their site dev built something efficient.

### D. Key scoping

Today the key is all-or-nothing. Later we may want per-endpoint scopes
(read-only key for a marketing widget, full key for the cart). Add a
`scopes` JSONB column to `tenant_sites` (or move to a `tenant_site_keys`
table when we need multiple keys per site).

---

## ACTION_LIST integration

This spec maps to:
- ACTION_LIST.md §11.2 (Website module) — extend the row schema to mention
  `tenant_sites` table
- ACTION_LIST.md §13 (Public API surface) — net-new section if we don't
  have one

I added a pointer in §11.2 in this same commit.

---

## Out of scope

- Tenant-managed page builder (Studio team owns this)
- E-commerce checkout / payment processing on the public site (defer until
  RoutePay public-endpoint integration lands)
- Per-customer accounts on the tenant's site (each merchant site treats
  customers as guests for now)
- Multi-language / i18n on public endpoints
- Search / full-text indexing of catalogues
