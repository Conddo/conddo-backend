# Social & Creative Services — BE → FE Handoff

**Counterpart to**: [SOCIAL_AND_CREATIVE_SERVICES_SPEC.md](./SOCIAL_AND_CREATIVE_SERVICES_SPEC.md)
**Status**: Phase 1, 2, and 3 BE shipped (2026-06-06). FE can integrate now.
**Base URL**: `https://api.conddo.io`
**Auth**: Bearer JWT on every tenant-facing endpoint unless noted.

This doc lists every endpoint the FE can consume across the social spec
phases, with request / response shapes and the error codes to render
explicit UI for. Where the spec text and the implemented contract differ
(field renames, status code choices, etc.), the implemented shape wins —
that's what's documented here.

---

## Phase 1 — Ayrshare social connect + compose/schedule (commit `b7eccb0`)

### Connect lifecycle

`GET /api/v1/marketing/social/accounts`
→ `{ data: { platforms: string[], profileTitle: string?, lastSyncedAt: string? } }`
Returns the cached `connected_platforms` list. Refreshes from Ayrshare's
`/api/user` if the cache is >10 min old.

`POST /api/v1/marketing/social/connect-link`
Empty body.
→ `{ data: { connectUrl: string } }`
Creates the tenant's Ayrshare User Profile on first call, then returns
Ayrshare's hosted-connect URL. Redirect the tenant there; on return,
re-fetch `/accounts` to refresh the connected list.

`POST /api/v1/marketing/social/accounts/{provider}/disconnect`
Empty body. `{provider}` ∈ `facebook | instagram | linkedin | x | tiktok | youtube | pinterest | gmb`.
→ `{ data: { platforms: [...] } }` (the post-disconnect list).

### Posts

`GET /api/v1/marketing/social/posts?from=<iso>&to=<iso>`
Both query params optional (default: last 30 days → next 60).
→ `{ data: SocialPost[] }`

`POST /api/v1/marketing/social/posts`
```json
{
  "caption": "Launch day! 🚀",
  "media": [{"url": "https://...", "type": "image", "width": 1080, "height": 1080}],
  "scheduledAt": "2026-06-10T09:00:00Z",
  "timezone": "Africa/Lagos",
  "platforms": ["facebook", "instagram"]
}
```
→ 201 `{ data: SocialPost }` with `status: "scheduled" | "publishing" | "published" | "failed"`.
**Immediate-vs-scheduled rule**: if `scheduledAt` is within the next 60s,
the post is published immediately; otherwise Ayrshare-side scheduling is used.

`GET /api/v1/marketing/social/posts/{id}` → `{ data: SocialPost }`
`DELETE /api/v1/marketing/social/posts/{id}` → cancels a `scheduled` post.

### SocialPost wire shape
```ts
{
  id: string,                  // UUID
  caption: string,
  media: any[],                // freeform JSON shape
  scheduledAt: string,         // ISO
  timezone: string,
  status: "draft" | "scheduled" | "publishing" | "published" | "failed",
  ayrsharePostId: string?,     // Ayrshare's scheduledPostId
  targets: SocialPostTarget[],
  createdAt: string
}

SocialPostTarget = {
  provider: string,            // "facebook", "instagram", ...
  status: "pending" | "published" | "failed",
  externalPostId: string?,     // URL or platform-id once delivered
  errorMessage: string?,
  publishedAt: string?
}
```

### Error codes

| HTTP | code | when |
|---|---|---|
| 403 | `PLAN_UPGRADE_REQUIRED` | tenant on Launcher; `social_scheduler` is Growth+ |
| 503 | `SOCIAL_UNCONFIGURED` | `AYRSHARE_API_KEY` not wired (deploy issue) |
| 502 | `AYRSHARE_UPSTREAM` | Ayrshare returned no `profileKey` / no URL |

---

## Phase 2a — Media library (commit `d0c9e07`)

The existing `/api/v1/media` endpoints got width/height + uploadedBy +
video support + per-plan storage caps.

`POST /api/v1/media` (multipart)
Form fields: `file` (required), `purpose` or `kind` (optional),
`width` (int, optional), `height` (int, optional).
→ 201 `{ data: MediaView }`.

`GET /api/v1/media?kind=image&page=0&size=20` → paginated `MediaView[]`.

`GET /api/v1/media/{id}` → `{ data: MediaView }`.

`DELETE /api/v1/media/{id}` → 204.

`GET /api/v1/media/usage` *(new in Phase 2a)*
→ `{ data: { usedBytes: number, capBytes: number } }`
`capBytes = -1` means unlimited (Scaler tier). Use to drive the usage bar
at the top of `/marketing/media`.

### MediaView wire shape
```ts
{
  id: string,
  url: string,
  contentType: string,
  size: number,                // bytes
  originalName: string,
  kind: "image" | "video" | "logo" | "product" | ...,
  width: number?,
  height: number?,
  uploadedBy: string?,         // user UUID
  createdAt: string
}
```

### Error codes

| HTTP | code | when |
|---|---|---|
| 413 | `MEDIA_STORAGE_CAP` | plan-tier cap hit (`{used + incoming} > capBytes`) |
| 413 | `FILE_TOO_LARGE` | single-file limit exceeded |
| 409 | `CONFLICT` | unsupported MIME type (not image/* + video/* + application/pdf) |

---

## Phase 2b — Creative services marketplace (commit `f0eb2aa`)

Tenant picks a paid creative offering, writes a brief, attaches media
references, pays, and a Studio job is created. When Studio delivers, the
final media flows back to the request (and onto the social post if linked).

`GET /api/v1/creative-services/offerings`
→ `{ data: Offering[] }`
No plan gate — open catalog to all authenticated tenants.

`POST /api/v1/creative-services/requests`
```json
{
  "offeringCode": "design_static",
  "brief": "Launch announcement post for Instagram, tone playful, deadline Fri",
  "attachedMedia": ["<mediaAssetId>", ...],   // optional
  "socialPostId": "<socialPostId>"             // optional — if attached to a post
}
```
→ 201 `{ data: { request: Request, checkoutUrl: string | null } }`
If the tenant has an **active brand-package subscription** that includes
this `offeringCode` with quota remaining, `checkoutUrl` is `null`,
`request.priceKobo` is `0`, and `request.status` jumps straight to
`"queued"` (bundle ride — see Phase 3). Otherwise `checkoutUrl` is the
RoutePay hosted URL — redirect the tenant there.

`GET /api/v1/creative-services/requests` → tenant's history.
`GET /api/v1/creative-services/requests/{id}` → detail.

### Wire shapes
```ts
Offering = {
  code: string,                // "design_static", "design_reels", ...
  name: string,
  description: string,
  priceKobo: number,
  turnaroundHours: number,
  jobType: "CREATIVE_DESIGN" | "CREATIVE_VIDEO" | "CREATIVE_AD"
}

Request = {
  id: string,
  status: "pending_payment" | "queued" | "in_progress" | "delivered" | "cancelled",
  offering: Offering,
  brief: string,
  attachedMedia: string[],     // media asset UUIDs
  socialPostId: string?,
  priceKobo: number,           // 0 on a bundle ride
  paymentReference: string?,
  studioJobId: string?,
  studioJobNumber: string?,    // "CD-2001", surfaced on the status pane
  deliveryMedia: any[]?,       // populated when delivered
  deliveredAt: string?,
  createdAt: string,
  updatedAt: string
}
```

### Seeded catalog (codes are stable; prices may shift)

| code | name | priceKobo |
|---|---|---|
| `design_static` | Static Design | 500000 (₦5,000) |
| `design_reels` | Reels / Vertical Video Edit | 1500000 (₦15,000) |
| `ad_creative_static` | Ad Creative (Static) | 800000 (₦8,000) |
| `ad_creative_video` | Ad Creative (Video) | 2000000 (₦20,000) |
| `brand_kit_starter` | Brand Starter Kit | 5000000 (₦50,000) |

### Error codes

| HTTP | code | when |
|---|---|---|
| 404 | `NOT_FOUND` | unknown `offeringCode` |
| 503 | `PAYMENTS_UNAVAILABLE` | conddo-payments unreachable |
| 409 | `QUOTA_EXHAUSTED` | bundle includes this code but used up (see Phase 3 details) |

---

## Phase 3 — Brand Package subscriptions (commit pending — coming on `main` next)

A tenant subscribes to a monthly bundle that auto-includes N creative
service jobs at a flat NGN price. Active subscribers ride the bundle on
the existing `POST /api/v1/creative-services/requests` flow (no extra
endpoint needed for consumption — the BE checks quota automatically).

`GET /api/v1/brand-packages/offerings`
→ `{ data: BrandOffering[] }`
Open catalog. Launcher tenants can browse to see what they'd unlock.

`GET /api/v1/brand-packages/subscription`
→ `{ data: { subscription: Subscription | null, offering: BrandOffering | null } }`
The tenant's current live subscription, or both `null` when there isn't one.

`POST /api/v1/brand-packages/subscription`
```json
{ "offeringCode": "starter_brand" }
```
→ 201 `{ data: { subscription: Subscription, offering: BrandOffering, checkoutUrl: string } }`
Status starts `pending_payment`; redirect to `checkoutUrl`. On RoutePay
success the BE flips to `active` and seeds the first usage period.

`POST /api/v1/brand-packages/subscription/cancel`
Empty body.
→ `{ data: Subscription }` with `status: "cancelled"` and `cancelledAt` set.
The current period's quota stays usable until `currentPeriodEnd` — no
immediate revoke (the spec wording is "subscribes to a recurring bundle";
renewal just doesn't fire).

### Wire shapes
```ts
BrandOffering = {
  code: string,                // "starter_brand", "growth_brand", "pro_brand"
  name: string,
  description: string,
  monthlyPriceKobo: number,
  includes: { [creativeServiceOfferingCode]: number }
                               // e.g. { design_static: 4, design_reels: 1 }
}

Subscription = {
  id: string,
  status: "pending_payment" | "active" | "past_due" | "cancelled",
  currentPeriodStart: string,
  currentPeriodEnd: string,
  paymentReference: string?,
  cancelledAt: string?,
  createdAt: string
}
```

### Seeded catalog

| code | name | monthly | includes |
|---|---|---|---|
| `starter_brand` | Starter Brand Package | ₦25,000 | 4 design_static, 1 design_reels |
| `growth_brand` | Growth Brand Package | ₦45,000 | 8 design_static, 2 design_reels, 2 ad_creative_static |
| `pro_brand` | Pro Brand Package | ₦85,000 | 16 design_static, 4 design_reels, 2 ad_creative_static, 2 ad_creative_video |

### Bundle ride mechanics (important for `/creative-services/requests`)

When a tenant with an `active` subscription POSTs a creative request:
1. BE checks the subscription's offering `includes` for the request's `offeringCode`.
2. **Code not included** (e.g. `starter_brand` doesn't include
   `ad_creative_static`): falls through to the normal paid flow —
   `checkoutUrl` populated, `priceKobo` is the catalog price.
3. **Code included + quota remaining**: ride the bundle — `checkoutUrl`
   is `null`, `priceKobo` is `0`, `status` is `"queued"` immediately, the
   per-period usage counter increments, Studio job fires.
4. **Code included + quota used up**: 409 `QUOTA_EXHAUSTED` with
   `details: [{ field: "offeringCode", message: "used N/N" }]`. The tenant
   can either wait for the next period, pay per-job (FE has to call a
   separate endpoint we'd build for that — currently no override), or
   upgrade the package.

### Error codes

| HTTP | code | when |
|---|---|---|
| 403 | `PLAN_UPGRADE_REQUIRED` | Launcher tenant; `brand_package_subscription` is Growth+ |
| 409 | `ALREADY_SUBSCRIBED` | tenant has a live subscription (status ∈ pending_payment, active, past_due) |
| 409 | `QUOTA_EXHAUSTED` | bundle ride attempted but used up (rendered on `/creative-services/requests` POST) |
| 404 | `NOT_FOUND` | unknown `offeringCode`, or no subscription on cancel |
| 503 | `PAYMENTS_UNAVAILABLE` | conddo-payments unreachable |

---

## Cross-cutting

### Standard envelope

All endpoints return:
```ts
{ success: true, data: T, meta?: {...} }
| { success: false, error: { code: string, message: string, details?: FieldError[] } }
```
**Exception**: the dashboard's `RequiresFeatureInterceptor` returns
`{ success: false, error: "PLAN_UPGRADE_REQUIRED", message: "...", upgrade_url: "..." }`
(string `error`, not object) — match the spec's `PlanGate` component
which reads both shapes.

**Other exception**: `POST /api/v1/public/{slug}/pharmacy/orders` 409
`STOCK_SHORTAGE` returns `{ error: "STOCK_SHORTAGE", items: [...] }`
outside the envelope, per the public-website integration spec.

### Webhooks (no FE impact, listed for awareness)

- `POST /webhooks/ayrshare` — Scheduled + Social actions, HMAC-verified.
- `POST /api/v1/internal/payments/notify` — conddo-payments → conddo-backend,
  service-token authed. Routes to booking / order / creative / brand-package
  by the populated id.
- `POST /api/v1/internal/creative-services/{id}/delivered` —
  conddo-studio → conddo-backend, service-token authed.

### Render env vars (for ops awareness)

These need to be set on the `conddo-backend` Render service before the
real money flow + Ayrshare work:

- `AYRSHARE_API_KEY` — master Bearer token
- `AYRSHARE_WEBHOOK_SECRET` — HMAC secret
- `CONDDO_SOCIAL_TOKEN_KEY` — 32-byte AES-256 envelope key (hex or base64)
- `STUDIO_SERVICE_TOKEN` — shared secret with conddo-studio for `/internal/creative-services/{id}/delivered`
- `PAYMENTS_SERVICE_TOKEN` — shared with conddo-payments (already set)

### What the FE can NOT do yet (BE follow-ups)

- **Brand-package upgrades / downgrades**: the current `POST /subscription`
  rejects with 409 ALREADY_SUBSCRIBED when there's a live row. Upgrade
  flow is a Phase 3b BE concern.
- **Renewal cron**: subscriptions don't auto-renew yet. First-month works;
  month two requires a Phase 3b cron we'll wire next.
- **Per-job override when quota exhausted**: no FE-callable endpoint to
  "skip the bundle and pay per-job for THIS request". Either build a
  `?force=paid` flag on the existing endpoint or defer.
- **Studio admin tooling for the catalogs**: offerings + brand packages
  are seeded in migrations; updating prices requires a SUPER_ADMIN
  endpoint we haven't built (Phase 3 spec §8 — Studio FE task).
