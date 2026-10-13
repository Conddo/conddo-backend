# Social Media Marketing — Cross-Team Spec

**Status**: Scoping (no code yet). This is sprint-2 work, not weekend launch.
**Scope**: The social-marketing surface only — Ayrshare connect/schedule, social
accounts, media library, and webhooks. The Creative Services marketplace and
Brand Packages features have been removed, and the retired `conddo-studio`
service is no longer part of this flow; all references to them have been
stripped from this spec. The file name is retained for link stability.
**Touches**: conddo-app (FE), conddo-backend (BE auth + posting + scheduling +
media).

The big idea, in one paragraph: A Conddo tenant connects their social
accounts (Facebook Page, Instagram Business, LinkedIn Company, X, TikTok),
and schedules posts to them from the dashboard. That is the whole surface —
connect accounts, compose, schedule, manage the media library, and track
delivery via webhooks.

---

## 1. Vendor — Ayrshare API gateway (decided)

**Decision is made**. We integrate via [Ayrshare](https://www.ayrshare.com),
which gives us a unified API across every channel we care about. **Our
master API key is already in hand** — product holds it, BE just needs to
drop it into **Render's `conddo-backend` service** as `AYRSHARE_API_KEY`
and start hitting the gateway. Only conddo-backend needs the key; no other
service calls Ayrshare directly. No per-platform app review, no OAuth
code paths to write, no provider-specific posting quirks for us to
maintain.

### What Ayrshare covers in one API

| Channel | Notes |
|---|---|
| Facebook Page | Posts, media, scheduling |
| Instagram Business | Photos, carousels, Reels |
| LinkedIn (personal + Company Page) | Shares |
| X (Twitter) | Tweets, threads, media |
| TikTok | Direct video posts |
| YouTube | Shorts + standard video |
| Pinterest | Pins + boards |
| GMB (Google Business) | Posts |
| Threads, Bluesky, Telegram, Reddit | Bonus channels, same API |

One endpoint, `POST https://api.ayrshare.com/api/post`, takes
`{post, platforms[], mediaUrls[], scheduleDate}` and fans out. Ayrshare
handles OAuth refresh, IG Reels containers, X char limits, LinkedIn URN
formats, media re-encoding, and rate limits. We never touch a Meta or
LinkedIn token.

### Tenant model in Ayrshare

Each tenant becomes an **Ayrshare User Profile** under our master account.
- BE calls `POST https://api.ayrshare.com/api/profiles` once per tenant
  to provision a User Profile; stores the returned `profileKey`
  (encrypted) in `tenant_social_profile` (schema in §2).
- Tenant clicks "Connect Facebook" on our `/settings/connections` page →
  BE fetches a fresh hosted-connect URL via Ayrshare
  (`GET /api/profiles/connectLink?profileKey=...`) → FE redirects the
  tenant to that URL → Ayrshare's hosted dialog handles every
  provider-specific OAuth dance → tenant lands back on our Settings page.
- After the redirect, BE calls `GET /api/user?profileKey=...` to refresh
  `connected_platforms` (a JSONB array on `tenant_social_profile`) so the
  Connect button on our FE updates to "Connected" without a page reload.

### Cost note

Pricing (verify before sign-up): Business plan ~$149/month covers ~10
user profiles + unlimited posts; Enterprise scales beyond. **Per-tenant
incremental cost** is the meaningful unit once we cross ~10 active
social-using tenants. Bake the ~$1-2/month/tenant marginal cost into
Growth-plan gross margin — `social_scheduler` is already a Growth-gated
feature, so Launcher tenants don't trigger the meter.

### Env vars in Render (sync:false) — **all on the `conddo-backend` service**

| Var | Source | Use |
|---|---|---|
| `AYRSHARE_API_KEY` | **Already in hand — ask product** | Master API key sent on every request as `Authorization: Bearer ...` |
| `AYRSHARE_WEBHOOK_SECRET` | Generate in Ayrshare dashboard | HMAC signature on incoming delivery / engagement webhooks |
| `CONDDO_SOCIAL_TOKEN_KEY` | `openssl rand -hex 32` | Envelope key for at-rest encryption of each tenant's Ayrshare `profileKey` |

All three are declared (with `sync: false`) in
[`render.yaml`](./render.yaml) under the `conddo-backend` service so the
shape is durable; only the values go into the Render dashboard.

### Native per-platform path — abandoned

A native Meta/LinkedIn/X/TikTok per-provider OAuth integration was the
original plan. We're not doing it. If circumstances ever change (Ayrshare
goes down, terms break, etc.), pull the older revision of this file from
git (commit `526bc3b`) for the native fallback details.

---

## 2. Connect flow — tenant attaches social accounts via Ayrshare

```
Tenant Settings → Connected Accounts → "Connect Facebook"  (or any other channel)
   ↓
FE: POST /api/v1/marketing/social/connect-link  {provider: "facebook"}
   ↓
BE: lookup tenant_social_profile for this tenant.
    - If no row yet → POST https://api.ayrshare.com/api/profiles
      with `{title: tenant.name}` to create the profile. Store the
      returned `profileKey` + `profileTitle` in tenant_social_profile.
    - If row exists → reuse the existing profileKey.
   Then: GET https://api.ayrshare.com/api/profiles/connectLink
         ?profileKey=...   (returns a one-time hosted-connect URL)
   ↓
BE response: { connectUrl: "https://app.ayrshare.com/social/..." }
   ↓
FE: window.location = connectUrl (or open in a new tab)
   ↓
Tenant authorises the platform inside Ayrshare's hosted dialog.
Ayrshare lands them back on a URL we configured in their dashboard
(suggested: https://app.conddo.io/settings/connections?reconnect=1).
   ↓
On the return, FE re-fetches GET /api/v1/marketing/social/accounts.
BE refreshes connected_platforms by calling
  GET https://api.ayrshare.com/api/user?profileKey=...
and returns the updated list.
   ↓
FE re-renders the provider rows: green "Connected" pill + "Disconnect" button.
```

### Schema (one table — Ayrshare bundles all connections under one profile)

```sql
CREATE TABLE tenant_social_profile (
    tenant_id              UUID PRIMARY KEY REFERENCES tenants(id),
    ayrshare_profile_key   TEXT NOT NULL,                   -- AES-GCM encrypted at rest
    ayrshare_profile_title VARCHAR(160),                    -- mirrors tenant.name when created
    connected_platforms    JSONB NOT NULL DEFAULT '[]',     -- ["facebook","instagram",...]
    last_synced_at         TIMESTAMPTZ,                     -- last successful /api/user refresh
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`ayrshare_profile_key` is encrypted with the envelope key in
`CONDDO_SOCIAL_TOKEN_KEY` (32 bytes). Use a `key_version` byte prefix on
each ciphertext so we can rotate keys later without a data migration.

### Disconnect

`POST /api/v1/marketing/social/accounts/{provider}/disconnect` →
BE calls `DELETE https://api.ayrshare.com/api/social/unlink` with the
profileKey + provider — Ayrshare revokes that one channel and leaves
the others connected. BE then refreshes `connected_platforms` from
Ayrshare's `/api/user` and writes it back.

### Webhook for status changes

Ayrshare can fire webhooks when a user disconnects from their end, when
a token goes stale, or on post delivery. Wire `AYRSHARE_WEBHOOK_SECRET`
into Ayrshare's dashboard and expose:

```
POST /webhooks/ayrshare    (no auth — verify the HMAC signature header)
```

Handle these event types:
- `account.disconnected` → refresh connected_platforms; notify tenant if
  this breaks any scheduled posts.
- `account.token.refresh.failed` → flag the tenant; the next scheduled
  post to that channel fails fast with a "Reconnect" CTA.
- `post.published` / `post.failed` → update `social_post_targets.status`
  + `external_post_id` + `error_message` (schema in §3).

---

## 3. Compose + schedule a post

The existing **marketing/social** page already has a SchedulePostModal stub.
This is the contract it needs:

### Schema

```sql
CREATE TABLE social_posts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants(id),
    author_user_id        UUID NOT NULL REFERENCES users(id),
    caption               TEXT NOT NULL,
    media                 JSONB,                  -- [{url, type:'image'|'video', width, height}, …]
    scheduled_at          TIMESTAMPTZ NOT NULL,
    timezone              VARCHAR(64) NOT NULL DEFAULT 'Africa/Lagos',
    status                VARCHAR(20) NOT NULL,   -- 'draft' | 'scheduled' | 'publishing' | 'published' | 'failed'
    created_at, updated_at
);

-- One row per target channel — a post can be cross-posted.
CREATE TABLE social_post_targets (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id               UUID NOT NULL REFERENCES social_posts(id) ON DELETE CASCADE,
    social_account_id     UUID NOT NULL REFERENCES social_accounts(id),
    external_post_id      VARCHAR(255),           -- after successful publish
    status                VARCHAR(20) NOT NULL,   -- 'pending' | 'published' | 'failed'
    error_message         TEXT,
    published_at          TIMESTAMPTZ,
    UNIQUE (post_id, social_account_id)
);
```

### Endpoints

| Method | Path | Returns | Notes |
|---|---|---|---|
| GET    | `/api/v1/marketing/social/accounts` | `{platforms: [{provider, connected: boolean, externalName?}]}` | Reads `tenant_social_profile.connected_platforms`; refresh from Ayrshare's `/api/user` if `last_synced_at` > 10 min |
| POST   | `/api/v1/marketing/social/connect-link` | `{connectUrl}` | Creates the tenant's Ayrshare profile on first call; returns the hosted-connect URL |
| POST   | `/api/v1/marketing/social/accounts/{provider}/disconnect` | 204 | Calls Ayrshare's `/api/social/unlink` for that one provider |
| GET    | `/api/v1/marketing/social/posts?status=&from=&to=` | `SocialPost[]` | |
| POST   | `/api/v1/marketing/social/posts` | `SocialPost` (created) | If `scheduled_at <= now() + 1 minute`, publish immediately via Ayrshare; otherwise store as `scheduled` |
| PATCH  | `/api/v1/marketing/social/posts/{id}` | `SocialPost` | Only `status='scheduled'` posts are editable |
| DELETE | `/api/v1/marketing/social/posts/{id}` | 204 | Cancels schedule (also delete from Ayrshare if it has a `scheduledPostId`) |
| POST   | `/api/v1/marketing/social/posts/{id}/publish-now` | `SocialPost` | Immediate publish |
| POST   | `/webhooks/ayrshare` | 200 | Status changes from Ayrshare (see §2 — disconnect, token failure, post.published, post.failed) |

### Publish via Ayrshare

Single endpoint, no per-provider code:

```
POST https://api.ayrshare.com/api/post
Headers: Authorization: Bearer ${AYRSHARE_API_KEY}
Body: {
  post:        social_posts.caption,
  platforms:   ["facebook", "instagram", "linkedin", ...],   // from social_post_targets
  mediaUrls:   ["https://res.cloudinary.com/..."],          // pre-uploaded to Cloudinary (§4)
  profileKey:  tenant_social_profile.ayrshare_profile_key,  // identifies the tenant
  scheduleDate: scheduled_at.toISOString()                  // omit for immediate
}
```

Ayrshare responds with one entry per platform `{id, postUrl, status,
errors[]}`. Write that into `social_post_targets`:
- success → `status='published'`, `external_post_id=...`, `published_at=now()`
- failure → `status='failed'`, `error_message=...`

### Two scheduling strategies — pick one

**Strategy A (Ayrshare-side scheduling, recommended)**: send the post with
`scheduleDate` and let Ayrshare publish at the requested time. Our BE
stores the `scheduledPostId` Ayrshare returns and listens for
`post.published` / `post.failed` webhooks to update the row. **Pros**:
no cron on our side, no scheduling drift, no orphan posts if our BE is
down at the wrong minute. **Cons**: cancel-after-schedule means a DELETE
to Ayrshare's `/api/post/{id}`.

**Strategy B (our-side cron)**: store posts as `status='scheduled'` and
run a `@Scheduled` job every minute that picks up due posts and fires
them immediately via Ayrshare. **Pros**: simpler cancellation. **Cons**:
duplicates Ayrshare's scheduler; needs idempotency on retry.

**Recommendation: Strategy A** unless the BE team has a strong
preference for owning the schedule. Cancellation via Ayrshare's DELETE
is one extra HTTP call.

---

## 4. Media library — uploads

Posts attach images / videos. Use the same Cloudinary integration the rest
of the platform uses, with a new `media_assets` table scoped per tenant:

```sql
CREATE TABLE media_assets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    cloudinary_id   VARCHAR(255) NOT NULL,
    url             TEXT NOT NULL,
    kind            VARCHAR(20) NOT NULL,    -- 'image' | 'video'
    width, height   INTEGER,
    bytes           BIGINT,
    uploaded_by     UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ DEFAULT now()
);
```

Endpoints:
- `POST /api/v1/media` (multipart) → uploads to Cloudinary, writes the row
- `GET  /api/v1/media?kind=image&page=` → paginated library
- `DELETE /api/v1/media/{id}`

Per-tenant storage soft cap: 500 MB on Launcher, 5 GB on Growth, unlimited on
Scaler. Surface via a usage bar at the top of the media library.

---

## 5. FE work (conddo-app)

Pages / components to build, in roughly this order:

| Order | Surface | What |
|---|---|---|
| 1 | `/settings/connections` (restore from "Coming soon") | List of providers, "Connect" buttons that hit the BE's `connect` endpoint and redirect to the Ayrshare hosted dialog, per-account "Disconnect" with a confirm modal |
| 2 | `/marketing/social` composer | Multi-account target selector (chips), media picker from library + upload, date/time picker, schedule button. Already partially scaffolded in [SchedulePostModal](../conddo-app/components/app/SchedulePostModal.tsx). |
| 3 | `/marketing/media` (new) | Grid view of `media_assets`, upload CTA, delete, usage bar |

---

## 6. Plan gating

| Feature | Launcher | Growth | Scaler |
|---|---|---|---|
| `social_scheduler` (connect + post + schedule) | — | ✓ | ✓ |
| `media_library` | basic (500MB) | ✓ (5GB) | ✓ (unlimited) |

---

## 7. Open product questions

- Provider mix beyond Phase 1 (FB+IG) — confirm priority of LinkedIn vs X vs TikTok.
- Ad management (Meta ads budget top-up) — is that a separate Phase? Today the FE has an empty "Ads" tab.

---

## 8. Out of scope (call out so nobody builds it by accident)

- DM / messenger replies (totally different API set; ChatOps surface, not marketing)
- Reels analytics / engagement scoring (Meta provides this — defer until v2)
- Cross-tenant content templates (multi-tenant data sharing — explicit no)
- AI image generation (separate vendor decision)
- White-glove community management (not a product, that's a services contract)

---

## 9. Phasing

### Ayrshare path (recommended, ~1 week per phase)

**Phase 1 (this sprint, ~1 week)**: Sign up for Ayrshare Business, store
the master `AYRSHARE_API_KEY`. Schema lands (`social_accounts`,
`social_posts`, `social_post_targets`). Ship the connect endpoint
(creates an Ayrshare User Profile per tenant, returns the hosted
connect URL). Ship the schedule endpoint (proxies to Ayrshare's
`/api/post`). FE Connected Accounts page already shipped — the BE
flips the provider `status` from `pending_approval` → `live` and the
Connect button starts opening the Ayrshare hosted dialog.

**Phase 2 (~1 week)**: Media library live. Scheduling + publishing covers
FB + IG + LinkedIn + X + TikTok + YouTube + Pinterest + GMB all at once
(Ayrshare covers them as one API).

**Phase 3**: Analytics rollup (Ayrshare provides post-level analytics —
we just aggregate + present).

### Native path (fallback only)

**Phase 1**: Meta app registration starts (~3-week App Review clock),
schema lands, connect/disconnect endpoints work in dev. FE buttons
stay disabled with "Awaiting Meta approval".

**Phase 2 (3-4 wks)**: FB + IG live after App Review.

**Phase 3 (6-8 wks)**: LinkedIn (separate Marketing Developer Platform
approval).

**Phase 4**: X, TikTok, ads, analytics.

The 2-4 week App Review delay is the **only blocker on the critical path**
of the native path. If we go native, submit Meta + LinkedIn apps the day
the BE schema lands so the wall-clock starts ticking.
