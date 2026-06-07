# Site Registration Admin — Spec

**Status**: Draft. Implementing this unblocks Seb&Bayor and every future
tenant-website integration without raw SQL or developer involvement.

**Why this exists**: Today, registering a `tenant_sites` row and issuing
a Site API Key requires direct DB access. The
[WEBSITE_INTEGRATION_SPEC.md](./WEBSITE_INTEGRATION_SPEC.md) and
[PHARMACY_PUBLIC_API_SPEC.md](./PHARMACY_PUBLIC_API_SPEC.md) integrations
are blocked on the manual provisioning step. This spec defines a small
Studio-side admin feature so **ops staff (not developers) can register
sites, issue + rotate API keys, and approve QA from a UI**.

**Audience**: BE team (Studio backend + conddo-backend admin endpoints) +
Studio FE team.

---

## 1. Scope

What this ships:

- New admin page `/admin/platform/sites` in Studio (next to the existing
  `/admin/platform/tenants` and `/admin/platform/users` pages).
- Backend endpoints for ops to: list tenant sites, register a new site,
  view a single site's details + audit log, rotate the API key, toggle
  `qa_approved` and `is_active`, edit metadata (subdomain, custom
  domain, hosting provider, submitted URL).
- API-key reveal pattern that matches industry norm — plaintext shown
  **exactly once** at issue / rotate, never again.

What this **does not** ship:

- A self-service tenant flow ("let the tenant rotate their own key from
  /settings"). That already exists on the conddo-app side (we built
  `POST /api/v1/website/site/regenerate-key` in
  [WEBSITE_INTEGRATION_SPEC.md §2](./WEBSITE_INTEGRATION_SPEC.md#2-tenant-facing-endpoints)).
  This admin path is for **ops registering a site on a tenant's behalf**.
- Multi-key per site (one site, one active key — same as the spec).
- Granular scopes on the key (single all-or-nothing scope today; covered
  in spec §11 future-work).

---

## 2. Authorization

| Role | Can do |
|---|---|
| `SUPER_ADMIN` | Everything — register, rotate, edit, QA-approve, deactivate |
| `TEAM_LEAD` | Read-only — list + view detail. No mutations. (So leads can answer "is Seb&Bayor's site live?" without escalating to admin.) |
| `QA_REVIEWER` | Read + flip `qa_approved`. No key issuance, no edits. (Their normal review duty extends here.) |
| Other Studio roles | No access. 403 on the route. |

Studio JWT carries `role` already — guard the controller methods with
`@PreAuthorize` per Spring's standard pattern (same as
`PlatformAdminController`).

---

## 3. Backend — Studio API endpoints

All under `/api/jobs` prefix to match Studio's existing API base
(see `backend/conddo-studio/.../web/PlatformAdminController.java` for
the existing tenants + users admin endpoints). The actual data lives in
the `tenant_sites` table the conddo-backend service owns — Studio
backend reads/writes it via its existing DB connection to the shared
Postgres (same pattern Platform Admin uses for `tenants`).

| Method | Path | Auth | Returns |
|---|---|---|---|
| GET | `/admin/platform/sites` | SUPER_ADMIN / TEAM_LEAD / QA_REVIEWER | `TenantSiteSummary[]` |
| GET | `/admin/platform/sites/{id}` | SUPER_ADMIN / TEAM_LEAD / QA_REVIEWER | `TenantSiteDetail` |
| POST | `/admin/platform/sites` | SUPER_ADMIN | `TenantSiteDetail` + `apiKey` (plaintext, once) |
| PATCH | `/admin/platform/sites/{id}` | SUPER_ADMIN | `TenantSiteDetail` |
| POST | `/admin/platform/sites/{id}/rotate-key` | SUPER_ADMIN | `TenantSiteDetail` + `apiKey` (plaintext, once) |
| POST | `/admin/platform/sites/{id}/qa-approve` | SUPER_ADMIN / QA_REVIEWER | `TenantSiteDetail` |
| POST | `/admin/platform/sites/{id}/qa-revoke` | SUPER_ADMIN / QA_REVIEWER | `TenantSiteDetail` |
| POST | `/admin/platform/sites/{id}/activate` | SUPER_ADMIN | `TenantSiteDetail` |
| POST | `/admin/platform/sites/{id}/deactivate` | SUPER_ADMIN | `TenantSiteDetail` |
| GET | `/admin/platform/sites/{id}/audit` | SUPER_ADMIN / TEAM_LEAD / QA_REVIEWER | `SiteAuditEntry[]` |

### Wire shapes

```jsonc
// TenantSiteSummary — table-row sized
{
  "id": "uuid",
  "tenantId": "uuid",
  "tenantName": "Seb & Bayor Pharmaceutical",
  "tenantVertical": "pharmacy",
  "subdomain": "seb-bayorpharmaceutical",
  "customDomain": "sebandbayor.com.ng",
  "hostingProvider": "9stacks",       // 'conddo' | 'vercel' | '9stacks' | null
  "siteType": "custom_built",          // 'custom_built' | 'template' | null
  "apiKeyLast4": "a3f2",               // for the masked display
  "isActive": true,
  "qaApproved": false,
  "createdAt": "2026-06-07T10:00:00Z",
  "qaApprovedAt": null
}

// TenantSiteDetail — single-row detail, adds the timestamps + meta
{
  ...TenantSiteSummary,
  "submittedUrl": "https://staging.sebandbayor.com.ng",
  "qaApprovedBy": null,                // staff id, null until approved
  "qaApprovedByName": null,
  "lastKeyRotatedAt": null,
  "lastKeyRotatedBy": null,
  "updatedAt": "2026-06-07T10:00:00Z"
}

// On POST and rotate-key — plaintext returned ONCE
{
  "site": TenantSiteDetail,
  "apiKey": "sk_live_AbCd1234...32chars"
}

// SiteAuditEntry
{
  "id": "uuid",
  "siteId": "uuid",
  "action": "REGISTERED" | "KEY_ROTATED" | "QA_APPROVED" | "QA_REVOKED"
          | "ACTIVATED" | "DEACTIVATED" | "METADATA_UPDATED",
  "byStaffId": "uuid",
  "byStaffName": "Ops Lead",
  "detail": "Approved after Seb&Bayor staging review",
  "at": "2026-06-07T10:00:00Z"
}
```

### Request bodies

```jsonc
// POST /admin/platform/sites  (register a new site)
{
  "tenantId": "uuid",                  // required — must exist
  "subdomain": "seb-bayorpharmaceutical",   // optional, but at least one of subdomain/customDomain required
  "customDomain": "sebandbayor.com.ng", // optional
  "hostingProvider": "9stacks",         // optional
  "siteType": "custom_built",            // optional
  "submittedUrl": null                   // optional
}
// Response: TenantSiteDetail + plaintext apiKey (only time it's revealed)

// PATCH /admin/platform/sites/{id}  (edit metadata; NEVER edits key)
{
  "subdomain": "...",
  "customDomain": "...",
  "hostingProvider": "...",
  "siteType": "...",
  "submittedUrl": "..."
}
// All fields optional, only provided fields change. The key + qa state
// + active state are NOT touched here — those have dedicated routes.

// POST /admin/platform/sites/{id}/qa-approve
{
  "note": "Site looks good — all endpoints pulling correctly, mobile OK."
}
// Stamps qa_approved=true, qa_approved_by=jwt.sub, qa_approved_at=now.
// Writes a SiteAuditEntry with the note.
```

### Validation

- `tenantId` must reference an existing tenant.
- `subdomain` UNIQUE constraint already enforced by `tenant_sites`
  (existing schema in [WEBSITE_INTEGRATION_SPEC.md §2](./WEBSITE_INTEGRATION_SPEC.md#2-tenant-facing-endpoints)).
  On conflict → 409 with `code: "SUBDOMAIN_TAKEN"`.
- `customDomain` UNIQUE constraint same — 409 with `code: "CUSTOM_DOMAIN_TAKEN"`.
- `customDomain` must look like a domain (regex
  `^[a-z0-9-]+(\.[a-z0-9-]+)+$`, no scheme).
- One row per tenant (UNIQUE INDEX on `tenant_id`) — 409 with
  `code: "TENANT_ALREADY_HAS_SITE"` on the POST. The PATCH +
  rotate-key + qa-approve flows are how you update an existing one.

### Key generation

Match the spec's algorithm exactly so the key works identically to
what the existing tenant-side regenerate endpoint produces:

```java
// Pseudocode
String generateApiKey() {
    byte[] raw = SecureRandom.getInstanceStrong().generateSeed(24);  // 32 base64 chars
    String key = "sk_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    return key;
}

void issueOrRotate(TenantSite site, String plaintext) {
    site.apiKeyHash = BCrypt.hashpw(plaintext, BCrypt.gensalt());
    site.apiKeyLast4 = plaintext.substring(plaintext.length() - 4);
    site.lastKeyRotatedAt = Instant.now();
    site.lastKeyRotatedBy = currentStaffId();
    save(site);
    audit(site, "KEY_ROTATED", "Issued key ending in " + site.apiKeyLast4);
    // Plaintext is returned to the controller, then to the FE, then forgotten.
}
```

`sk_test_` prefix in non-prod environments so a key can't accidentally
cross environments.

---

## 4. Studio FE pages

### 4.1 List — `/admin/platform/sites/page.tsx`

Layout: table similar to `/admin/platform/tenants`.

Columns:
- **Tenant** — name + vertical pill (e.g. "Seb & Bayor / pharmacy"),
  links to `/admin/platform/tenants/{tenantId}`
- **Site** — `subdomain.conddo.io` OR `customDomain` (whichever is set)
- **Status** — chip: "Live" (active + qa_approved), "Pending QA"
  (active + !qa_approved), "Draft" (!active), "Inactive" (deactivated)
- **API key** — `sk_live_••••••a3f2` (masked, monospace)
- **Last activity** — most recent of qaApprovedAt, lastKeyRotatedAt,
  createdAt
- **Actions** — kebab menu → view detail, rotate key (if SUPER_ADMIN),
  QA approve (if SUPER_ADMIN or QA_REVIEWER)

Top-right: **"Register site"** button (SUPER_ADMIN only — hidden for
other roles) → opens `RegisterSiteModal`.

Search box: by tenant name or domain (substring match — client-side is
fine if the table is small; server-side `?search=` once it grows past
~100 rows).

Filter chips: All / Live / Pending QA / Draft / Inactive.

### 4.2 Detail — `/admin/platform/sites/[id]/page.tsx`

Sections, top-to-bottom:

**Header card**
- Tenant name + vertical pill + link to tenant detail
- Status chip (Live / Pending QA / Draft / Inactive)
- `subdomain.conddo.io` and `customDomain` shown side-by-side, each
  with a copy button

**API key**
- Masked field `sk_live_••••••a3f2` (always shown)
- Plaintext value (only visible immediately after issue/rotate — see
  RotateKeyResultModal below). After the modal closes, plaintext is
  gone.
- "Rotate key" button (SUPER_ADMIN only) → confirms with the same
  warning the conddo-app version uses ("This will break any
  integration using the current key until you redeploy with the new
  one"), then POSTs `/rotate-key` and opens RotateKeyResultModal.

**Metadata**
- Editable fields (inline edit pattern matching tenant detail):
  hosting provider (select), site type (select), submitted URL
  (text). SUPER_ADMIN only. On save → PATCH.

**QA panel**
- "Approve QA" button (SUPER_ADMIN or QA_REVIEWER) → opens a confirm
  modal with a "QA note" textarea (optional) → POST /qa-approve. Once
  approved, shows "Approved by {staff} on {date}" and the button
  changes to "Revoke approval" (SUPER_ADMIN only).

**Active toggle**
- A switch: Active / Inactive. SUPER_ADMIN only. Flipping fires
  /activate or /deactivate. Inactive sites have public endpoints
  return 403 `SITE_NOT_LIVE` per the existing public-route guard.

**Audit log**
- Reverse-chronological list of `SiteAuditEntry` rows (latest first).
  Each row: action chip + actor + relative time + optional detail.

### 4.3 New components

**`RegisterSiteModal`** — opened from the list page's "Register site"
button.

Form:
- Tenant picker — typeahead search over tenants. Already-registered
  tenants are filtered out (the BE's UNIQUE INDEX on `tenant_id`
  enforces this; the FE filter is just so they don't show up).
- Subdomain text input (optional). Auto-suggested from the tenant's
  slug, editable.
- Custom domain text input (optional). At least one of subdomain /
  custom domain must be provided — show validation if both empty.
- Hosting provider select (conddo / vercel / 9stacks).
- Site type select (custom_built / template).
- Submit → POST → opens RegisterSiteResultModal with the plaintext key.

**`RotateKeyResultModal`** / **`RegisterSiteResultModal`** — same
shape. Shows the plaintext key once.

UX:
- Big yellow banner: "Copy this key now — you won't see it again."
- Code block with the plaintext key + Copy button.
- "I've saved the key" button (only enabled after Copy is clicked, or
  after 5s — prevents accidental dismissal).

### 4.4 Sidebar nav

Add to Studio's `LEAD` + `ADMIN_EXTRAS` arrays in `StudioShell.tsx`:

```ts
const ADMIN_EXTRAS: NavItem[] = [
  { label: "Platform Tenants", href: "/admin/platform/tenants", icon: Building2 },
  { label: "Platform Users", href: "/admin/platform/users", icon: UserCog },
  { label: "Platform Sites", href: "/admin/platform/sites", icon: Globe },   // NEW
];
```

`Globe` is already imported by Studio for the tenant detail page.

For QA_REVIEWER role: also add the entry to `QA` nav (since reviewers
can flip QA approval), but read-only. Or alternatively the existing
QA Queue gains a new sub-tab "Sites awaiting QA" — product call.

---

## 5. Three core UX flows

### 5.1 Provisioning a new site (the Seb&Bayor unblock)

```
Ops opens /admin/platform/sites
  ↓
Clicks "Register site"
  ↓
RegisterSiteModal:
  - picks "Seb & Bayor Pharmaceutical" from the tenant picker
  - subdomain auto-fills "seb-bayorpharmaceutical" (editable)
  - enters customDomain: "sebandbayor.com.ng"
  - selects hosting: "9stacks", siteType: "custom_built"
  - submits
  ↓
BE inserts tenant_sites row + audit entry, returns the row + plaintext key
  ↓
RegisterSiteResultModal opens with the plaintext key
  - ops copies to clipboard
  - clicks "I've saved the key"
  - modal closes; the plaintext is gone server-side from anywhere visible
  ↓
Ops shares the key with the Studio dev rewiring Seb&Bayor's website
  (via 1Password, secure channel — never email or Slack DM)
  ↓
Site appears in the list with status "Draft" until the dev submits
the deployed URL, then status flips to "Pending QA"
```

### 5.2 Rotating a compromised key

```
Suspicion of leak → ops opens /admin/platform/sites/{id}
  ↓
Clicks "Rotate key"
  ↓
Confirm modal: "This will break the current integration until the dev
redeploys with the new key. Continue?"
  ↓
On confirm → BE issues new key, invalidates old hash, writes audit entry
  ↓
RotateKeyResultModal — same plaintext-once UX
  ↓
Ops shares new key with the Studio dev, who redeploys
```

### 5.3 QA approval before go-live

```
Studio dev submits the live URL on their job → status flips to "Pending QA"
  ↓
QA reviewer opens /admin/platform/sites or /qa
  ↓
Reviews the live URL against the PHARMACY_PUBLIC_API_SPEC.md
  Seb&Bayor Integration Checklist
  ↓
On the site detail page → "Approve QA" → confirm with QA note
  ↓
BE flips qa_approved=true, qa_approved_by + qa_approved_at stamped,
audit entry written
  ↓
Public endpoints now serve real traffic (the existing route guard
already checks qa_approved before is_active).
```

---

## 6. Audit log

Every mutation writes a `site_audit_log` row:

```sql
CREATE TABLE site_audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id         UUID NOT NULL REFERENCES tenant_sites(id) ON DELETE CASCADE,
    action          VARCHAR(40) NOT NULL,
    by_staff_id     UUID NOT NULL,    -- references staff_users(id) — Studio's table
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_site_audit_site_created ON site_audit_log (site_id, created_at DESC);
```

Actions emitted:
- `REGISTERED` — on POST /sites
- `KEY_ROTATED` — on POST /rotate-key (also on the very first issue)
- `QA_APPROVED` — on POST /qa-approve (detail = note)
- `QA_REVOKED` — on POST /qa-revoke
- `ACTIVATED` / `DEACTIVATED` — on the toggles
- `METADATA_UPDATED` — on PATCH (detail = JSON-encoded field diff)

The detail view's audit panel reads these via GET /sites/{id}/audit.

---

## 7. Visibility on the tenant side

After ops provisions a site, the tenant's own conddo-app
`/website` page light up automatically — the `SiteIntegrationPanel`
component I shipped on the conddo-app side
([`components/app/SiteIntegrationPanel.tsx`](../conddo-app/components/app/SiteIntegrationPanel.tsx))
already calls `GET /api/v1/website/site` which returns the row this
admin tool just inserted. The masked key, copy button, regenerate
button, QA status chip — all populate from that response.

**One thing the tenant CANNOT see**: the audit log. Only ops sees who
rotated when. If product wants tenants to see their own key history,
that's a follow-up.

---

## 8. Tests

| What | Why |
|---|---|
| 403 on every endpoint for `DEVELOPER` / `DESIGNER` / `WRITER` roles | Auth gate must hold |
| 403 on mutations for `TEAM_LEAD` (reads OK) | Read/write split |
| Plaintext key returned on POST + rotate-key, NOT on GET | Stripe pattern |
| `api_key_hash` correctly bcrypt-verifies against the returned plaintext | Round-trip correctness |
| Public route guard rejects requests bearing the old key after rotate-key | Invalidation |
| Public route guard rejects an inactive site (403 SITE_NOT_LIVE) | Deactivate works |
| Public route guard rejects an unapproved site (403 SITE_NOT_LIVE) | QA gate works |
| UNIQUE constraint violation → 409 SUBDOMAIN_TAKEN / CUSTOM_DOMAIN_TAKEN / TENANT_ALREADY_HAS_SITE | Clear error codes |
| Audit log entry created for each mutation, captures by_staff_id | Traceability |

---

## 9. Future work (out of scope for v1)

- **Multiple keys per site** — for staged rollouts (issue a new key,
  let both work briefly, retire the old). Today: rotation is atomic.
- **Per-endpoint scopes** — read-only key for a marketing widget vs
  full read/write key for the cart. Today: one key, all endpoints.
- **Self-service key history for tenants** — the conddo-app side
  shows the *current* key only. A tenant who wants to know who
  rotated when has to ask ops.
- **Bulk site provisioning** — if Conddo onboards 50 pharmacies at
  once, a CSV-driven flow would speed this up. Today: one at a time
  through the modal.

---

## 10. Implementation order

1. BE — `tenant_sites` schema is already in place from
   WEBSITE_INTEGRATION_SPEC; add `site_audit_log` migration.
2. BE — Studio's `PlatformAdminController` gains the 9 routes from §3.
3. BE — Wire the public-route guard to reject when `qa_approved=false`
   OR `is_active=false` (if not already enforced).
4. Studio FE — new `lib/sites.ts` API surface, `/admin/platform/sites`
   list page, `/admin/platform/sites/[id]` detail page,
   `RegisterSiteModal` + `RotateKeyResultModal` shared component.
5. Studio FE — `StudioShell.tsx` nav entry added to ADMIN_EXTRAS.
6. Manual smoke test: register Seb&Bayor's site via the new UI,
   share the plaintext key with the Studio dev, validate the public
   API responds correctly with the issued key.

Total scope: ~2 days BE, ~1 day Studio FE. Saves every future tenant
integration ~30 min of raw SQL + scattered comms.

---

*Questions on this spec? Raise on the internal board or flag to
product lead.*
