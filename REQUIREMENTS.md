# Conddo.io — Product Requirements

**Status:** Living document
**Last updated:** 2026-06-03
**Owner:** Mickercode
**Cross-references:** [ARCHITECTURE](./ARCHITECTURE.md) · [ACTION_LIST](./ACTION_LIST.md) · [VERTICALS](./VERTICALS.md) · [DEPLOY](./DEPLOY.md)

This is the front-door spec — what Conddo.io is, who it's for, what it must
do, and where the detailed specs live. Everything below either references or
summarises an existing doc; if you need the depth, follow the link.

---

## Table of Contents

1. [Product](#1-product)
2. [Scope](#2-scope)
3. [Users & Roles](#3-users--roles)
4. [Functional Requirements — Tenant Platform (conddo-app)](#4-functional-requirements--tenant-platform-conddo-app)
5. [Functional Requirements — Service-to-Service](#5-functional-requirements--service-to-service)
6. [Non-Functional Requirements](#6-non-functional-requirements)
7. [Architecture Overview](#7-architecture-overview)
8. [Deployment Topology](#8-deployment-topology)
9. [Success Metrics](#9-success-metrics)
10. [Constraints & Assumptions](#10-constraints--assumptions)
11. [Open Questions](#11-open-questions)
12. [Doc Map](#12-doc-map)

---

## 1. Product

### 1.1 What Conddo.io is

A **multi-tenant SaaS platform for Nigerian small and medium businesses**.
Each tenant gets:

- A **professional website** at `<their-slug>.conddo.io`, automatically built
  by the Conddo production team and tuned to their industry.
- A **dashboard** to run the business day-to-day: customers, orders, bookings,
  inventory, payments, marketing, analytics, staff.
- **Naira-native payments** — accept money online and in-person without a
  dollar card, with Paystack on the rail today and RoutePay planned.
- **Industry-aware tools** — the platform knows whether you sell clothes,
  medicines, deliveries, food, consultations, retail goods, or events, and
  shows you the fields and screens that match.

### 1.2 Who it's for

**Nigerian SMEs** with one operator or a small team. Concretely:

- **Fashion brands** running custom or ready-to-wear at small scale
- **Pharmacies** managing stock, prescriptions, and NAFDAC compliance
- **Logistics operators** dispatching last-mile deliveries
- **Food & beverage** — restaurants, caterers, suppliers
- **Consultants** invoicing retainers and projects
- **Retail** — single-store and small chains
- **Event planners** selling ticketed and bespoke events
- **Beauty** — salons, spas, makeup artists with bookings

Each is a **vertical** with its own pre-configured screens — see
[VERTICALS.md](./VERTICALS.md).

### 1.3 The promise

> **Sell more. Stress less.** One platform, built for your type of business.

In one sentence: a tenant signs up, picks their business type, gets a
working website + dashboard within hours (built by humans on the Conddo
production team), and runs every part of their business from one place.

### 1.4 Differentiators

What sets Conddo.io apart from generic SaaS (Bumpa, Nile, Selar) and from
DIY website builders:

1. **Vertical intelligence.** A pharmacy gets expiry dates and NAFDAC fields.
   A fashion brand gets measurement profiles. A logistics op gets live route
   status. Same platform, different fields — the FE actually proves this with
   a clickable demo on the landing page.
2. **Built website included.** The customer doesn't drag-and-drop a website
   — the Conddo production team builds it for them and ships the link. This
   is the single biggest "done for you" delta vs Wix / Selar.
3. **Naira-native.** No dollar card for ad budgets, payments, or
   subscriptions. SMS and email rails ready for the Nigerian market.
4. **Operations + Website in one.** The customer doesn't run a Shopify *and*
   a Notion *and* an Insta DM-thread — one dashboard owns the full operation.

---

## 2. Scope

### 2.1 In V1 (currently shipping)

**Tenant platform (conddo-app):**
- Public landing page (live at https://conddo-io.vercel.app)
- Staged signup wizard with phone OTP + Google Sign-in (FE shipped, BE in flight)
- Dashboard with KPIs, recent activity, setup checklist
- Customers (CRM)
- Orders (board + detail + stage transitions)
- Bookings (incl. public self-book at `/book/:slug`)
- Inventory
- Payments (incl. Paystack rail)
- Marketing (Social / Email / SMS / Leads / Ads)
- Website (status + content management surface)
- Analytics (revenue, top sellers, customer activity)
- Staff (invite + role management within a tenant)
- Settings (account, billing, connections, API keys, notifications, danger zone)
- Notifications
- Tenant-scoped RLS — every tenant sees only their data

**Platform admin (internal ops):**
- Platform admin UI at `/admin/*` (served by conddo-app, talks to conddo-api)
- Cross-tenant management (tenants list, search, suspend, reactivate)
- Cross-tenant user management (find user, force password reset, suspend)
- Audit log of every cross-tenant admin action

**Service-to-service:**
- conddo-api → conddo-payments: payment initiation + verification
  (`X-Payments-Service-Token`, shared secret)
- conddo-payments → RoutePay (external) and webhook back into the payments
  service

**Infrastructure:**
- conddo-api on Render (Spring Boot 3, Java 21)
- conddo-payments on Render (separate service, own `payments` schema)
- Postgres (Render-managed, shared, separate schemas)
- conddo-app on Vercel (Next.js 14)
- Brevo for transactional email
- Cloudinary for media
- Resend as fallback email

### 2.2 Pending / In-flight V1

- **Google Sign-in (backend half)** — FE wired, backend has staged work
  (V21 migration, `GoogleIdTokenVerifier`, DTOs, tests). See
  [ACTION_LIST §1a](./ACTION_LIST.md).
- **RoutePay payment integration** — separate service (workstream deferred).

### 2.3 Explicitly out of scope (descoped)

- **In-app website builder**. Decision 2026-06-03: staff build websites
  externally (Studio.io, VS Code, Figma, Framer, customer hosting). Conddo
  is the **system of record** for the customer's business data, not the
  website source.

### 2.4 V2 candidates

Not yet on the roadmap, captured for memory:

- Cross-tenant ops bot (e.g. WhatsApp customer service automation)
- AI website-building agent that prepares the brief into a near-finished site
- Customer-facing mobile app (today's mobile-web is mobile-first but not native)
- Workflow automation builder (Zapier-equivalent inside Conddo)

---

## 3. Users & Roles

Two populations interact with the product, on two different identity axes.
**Platform staff are not tenant users; tenant users are not platform staff.**
They authenticate via different endpoints, hold different JWTs, and have
separate database tables.

### 3.1 Platform users (tenant-scoped, RLS)

Source of truth: `users` table in conddo-api. RLS enforces tenant isolation
at the row level.

| Role | Scope | Capabilities |
|---|---|---|
| `TENANT_ADMIN` | One workspace | Full read/write on their tenant: invite staff, manage everything. **Auto-assigned to the signup user.** |
| `STAFF` | One workspace | Day-to-day operations: customers, orders, bookings, etc. No staff management, no billing. |
| `CUSTOMER` | One workspace | End-customer-facing identity (e.g. logged-in shopper on a tenant's website). Limited features. |

### 3.2 Platform staff (cross-tenant, no RLS)

Source of truth: `staff_users` table in conddo-api. Internal Handel Cores
operations team.

| Role | Capabilities |
|---|---|
| `SUPER_ADMIN` | Cross-tenant admin operations via `/api/v1/admin/*`. Authenticates via `/auth/staff/login`. Uses `X-Act-As-Tenant` to scope to a specific tenant per request. **No `BYPASSRLS`** — always honoured. |

## 4. Functional Requirements — Tenant Platform (conddo-app)

Deep dive in [ACTION_LIST §11](./ACTION_LIST.md). Below is the V1 surface
the FE actually consumes, with backend status.

### 4.1 Public surface (no auth)

| Capability | Endpoint(s) | Status |
|---|---|---|
| Landing page (marketing site) | n/a (static) | ✅ |
| Public self-book page `/book/:slug` | `/api/v1/public/bookings/**` | ✅ |
| Tenant signup (Phase 1) | `POST /api/v1/tenants` (legacy) + staged signup wizard (current — see 4.2) | ✅ |
| Login | `POST /auth/login` (email/password + tenantSlug) | ✅ |
| Refresh access token | `POST /auth/refresh` (httpOnly refresh cookie) | ✅ |
| Logout | `POST /auth/logout` | ✅ |
| Forgot / reset password | `POST /auth/forgot-password`, `POST /auth/reset-password` | ✅ |

### 4.2 Staged signup wizard (PRD §6.2)

The current new-tenant flow: Email → OTP → Workspace → Business type → Plan
→ "Ready" landing. Six steps; backend persists a `PendingRegistration` row
until `/complete` creates the tenant atomically.

| Endpoint | What it does |
|---|---|
| `POST /auth/register/start` | Collects fullName, phone, email, password. Issues OTP via email (Brevo) or SMS (later). |
| `POST /auth/register/verify` | Confirms the OTP. Idempotent once verified. |
| `POST /auth/register/resend` | Re-issues the OTP, subject to cooldown + cap. |
| `POST /auth/register/complete` | Creates tenant + admin user atomically, issues JWT + refresh cookie, fires `TenantActivatedEvent`. |
| `POST /auth/google` *(pending BE)* | Sign in with Google ID token + tenantSlug. |
| `POST /auth/register/start-google` *(pending BE)* | Start signup with Google ID token + phone. |

### 4.3 Authenticated tenant surface

Every endpoint scoped to the caller's tenant via JWT `tenant_id` claim →
`JwtTenantContextFilter` → Postgres RLS.

| Module | Endpoints |
|---|---|
| Account / Me | `GET /api/v1/me` |
| Customers | `GET/POST/PATCH /api/v1/customers`, `/api/v1/customers/{id}` (+ customer notes, history) |
| Orders | `GET/POST /api/v1/orders`, `/api/v1/orders/{id}`, stage transitions via `OrderStageController` |
| Bookings | `GET/POST /api/v1/bookings`, `/api/v1/bookings/today`, availability slots |
| Inventory | `GET/POST/PATCH /api/v1/inventory`, stock adjustments |
| Payments | `GET /api/v1/payments`, Paystack init/verify, record payment, send reminder |
| Marketing | `GET/POST /api/v1/marketing/**` — posts, ads, email, SMS, leads |
| Website | `GET /api/v1/website/**`, change-request → production team |
| Analytics | `GET /api/v1/analytics/**` |
| Dashboard | `GET /api/v1/dashboard/summary`, `/dashboard/setup-checklist` |
| Notifications | `GET/PATCH /api/v1/notifications/**` |
| Staff (admin) | `GET/POST/PATCH /api/v1/staff/**` |
| Settings | `GET/PATCH /api/v1/settings/**` — billing, connections, API keys, notification preferences, danger |
| Search (global) | `GET /api/v1/search?q=` |
| Media upload | `POST /api/v1/media/upload` (multipart, Cloudinary) |

### 4.4 Frontend invariants

These are baked into `conddo-app/lib/api/client.ts`. Any backend change that
breaks them silently borks the deployed FE. **Notify before changing:**

1. **Response envelope.** Every response is `{ success, data, meta?, error{code, message, details?} }`.
2. **Silent refresh.** FE retries any 401 once after `POST /auth/refresh` (the httpOnly cookie carries the refresh token cross-site via `credentials: "include"`).
3. **No Bearer on `/auth/*`.** FE strips Authorization on login / refresh / logout / register/* / forgot / reset.
4. **CORS** must support `CONDDO_CORS_ALLOWED_ORIGINS` (exact) and `CONDDO_CORS_ALLOWED_ORIGIN_PATTERNS` (wildcards for Vercel preview URLs).
5. **Refresh cookie** named `conddo_rt`, `Path=/auth`, `HttpOnly`, `Secure` in prod, `SameSite=None` cross-site.
6. **Request timeouts:** 45s JSON, 120s upload. Cold-start sensitive endpoints risk the timeout error.

---

## 5. Functional Requirements — Service-to-Service

Two deployable backend services — `conddo-api` and `conddo-payments` —
communicate over HTTP with a shared service token. The full endpoint
contract lives in [ACTION_LIST §7a](./ACTION_LIST.md).

### 5.1 conddo-api → conddo-payments

- `conddo-api` proxies payment initiation / verification to
  `conddo-payments` (`X-Payments-Service-Token` shared secret).
- `conddo-payments` owns the `payments.*` schema and talks to RoutePay.
- RoutePay webhooks land on `conddo-payments`, which calls back into
  `conddo-api` for tenant-scoped notifications.

### 5.2 Billing integration

- Plan upgrades and renewals call `conddo-payments` for the
  hosted-checkout charge (see [BILLING_TIERS_SPEC.md](./BILLING_TIERS_SPEC.md)).

---

## 6. Non-Functional Requirements

### 6.1 Performance

| Surface | Target | Notes |
|---|---|---|
| API p95 latency (warm) | < 500 ms | Most endpoints. |
| Cold start (Render free tier) | < 60 s | Documented behaviour; FE surfaces it as a clean timeout. |
| FE request timeout | 45 s JSON, 120 s upload | Hard ceiling. |
| SSE heartbeat interval | 30 s | Keeps proxies from cutting the stream. |

### 6.2 Security

| Area | Decision |
|---|---|
| **Tenant isolation** | Postgres RLS, enforced by `tenant_isolation` policies on every tenant-scoped table. Phase-0 + Phase-1 done; coverage tests in `AuthFlowTest`. |
| **JWT** | RSA-256 (15 min). Keys mounted, never committed. |
| **Refresh tokens** | httpOnly cookie with rotation + family-reuse detection. |
| **Lockout** | 5 failed logins → 15 min lockout (exponential backoff). Shared `LockoutPolicy`. |
| **Password hashing** | BCrypt cost 12. |
| **Audit** | `audit_log` table on every state change (tenants, users, payments, signup completion). |
| **CORS** | Exact origins + wildcard patterns. Never `*` — credentials are allowed. |
| **CSRF** | Not needed (no session cookie, refresh cookie is `SameSite=Strict` or `None`+`Secure`). |
| **OWASP Top 10** | Covered via Spring Security defaults + manual `@PreAuthorize` per method. |

### 6.3 Accessibility

| Requirement | Status |
|---|---|
| Keyboard navigation throughout | ✅ |
| Visible focus rings on every interactive element | ✅ via Tailwind `focus:` utilities |
| Colour contrast ≥ 4.5:1 (WCAG AA) | ✅ tokens designed to AA on the light theme |
| `aria-label` on icon-only buttons | ✅ throughout |
| `prefers-reduced-motion` | ✅ HeroPreview freezes; RouteTransition skips |
| Form labels paired with inputs | ✅ Field component throughout |

### 6.4 Internationalisation

- **Currency**: Naira (₦) only. Format as `₦1.84M`, `₦248,500`, etc. — `lib/format.ts` `naira()` helper.
- **Phone**: Nigerian E.164 (`+234…`). FE strips leading zeros, prepends `+234`.
- **Date/time**: `en-NG` locale (`new Date().toLocaleString("en-NG", …)`).
- **Cities**: Lagos, Abuja, Port Harcourt (primary), expanding.
- **No multi-language** in V1 — English only. Verticals + tone vary, not language.

### 6.5 Mobile

| Requirement | Note |
|---|---|
| Mobile-first design | All FE components built for 360px first. |
| Hero stacks vertically | Verified after audit (b06a700). |
| Frame URL pill truncates | Doesn't push chrome dots off-screen. |
| HeroPreview tab strip on mobile | Visible substitute for the hidden sidebar. |
| Pricing cards stack | `grid-cols-1 md:grid-cols-3`. |
| Sidebar drawer on mobile | AppShell has it. |

### 6.6 Email & SMS reliability

- **Email**: Brevo primary (transactional, 300/day free tier), Resend fallback for one-account testing.
- **SMS**: Brevo SMS or Termii (planned).
- **OTP**: 4-digit, 10-min TTL, max 5 attempts, 30s resend cooldown, max 5 resends.

---

## 7. Architecture Overview

Full detail: [ARCHITECTURE.md](./ARCHITECTURE.md).

### 7.1 Deployment topology

Conddo runs on **two deployable backend services** plus the frontend:

```
┌───────────────────────────────────────────────────────────────────┐
│                          CONTROL PLANE (tenant)                    │
│                                                                    │
│   conddo-app (Next.js)  ──→  conddo-api (Spring Boot)             │
│   the customer dashboard      tenant-scoped, RLS-enforced          │
│   + platform admin /admin/*   schema: public.*                     │
│                                                                    │
└────────────────────┬──────────────────────────────────────────────┘
                     │  (payment init / verify)
                     │  X-Payments-Service-Token
                     ▼
┌───────────────────────────────────────────────────────────────────┐
│                    PAYMENTS SERVICE (own schema)                   │
│                                                                    │
│   conddo-payments (Spring Boot)  ──→  RoutePay (external)         │
│   schema: payments.*                                              │
│                                                                    │
└───────────────────────────────────────────────────────────────────┘
```

### 7.2 Why a separate payments service

- **Different uptime profile.** A platform API outage shouldn't kill in-flight card transactions; a payments outage shouldn't kill signups.
- **Regulatory blast radius.** CBN PSSP / PCI considerations are easier to isolate when the module is its own deployable.
- **Provider portability.** Swap RoutePay for another provider without touching `conddo-api`.
- **Webhook surface area.** RoutePay's webhook posts directly to the payments service URL.

### 7.3 Shared Postgres

Both services connect to the same Postgres instance but own different
schemas:

- `public.*` — tenants, users, customers, orders, etc. RLS-enforced. Owned by conddo-api.
- `payments.*` — payment records + per-tenant RoutePay sub-accounts. Owned by conddo-payments.

---

## 8. Deployment Topology

Full detail: [DEPLOY.md](./DEPLOY.md).

### 8.1 Hosting

| Service | Runtime | Where |
|---|---|---|
| conddo-app | Next.js 14 | Vercel (project `conddo.io`) |
| conddo-api | Spring Boot 3 / Java 21 | Render (`conddo-backend`) |
| conddo-payments | Spring Boot 3 / Java 21 | Render (`conddo-payments`) |
| Postgres | Managed | Render |
| Cloudinary | SaaS | Cloudinary |
| Brevo | SaaS | Email + SMS |

### 8.2 Domains

| URL | Purpose |
|---|---|
| `conddo.io` | Marketing site + landing (tenant flow lands here on `/onboarding/create-account`) |
| `<slug>.conddo.io` | Per-tenant subdomain (resolves to tenant via Redis cache → JWT tenant_id) |
| `api.conddo.io` (planned) | API gateway (today: direct `conddo-backend.onrender.com`) |

> **Legacy:** the platform admin UI was historically served at
> `studio.getconddo.com`; it now lives at `/admin/*` on conddo-app.

### 8.3 Critical env vars

Key env vars on the two backend services:

**conddo-api:**
- `CONDDO_DB_*` (URL, app user, owner user, passwords)
- `CONDDO_JWT_*` (issuer, RSA key paths, TTL)
- `CONDDO_CORS_ALLOWED_ORIGINS` + `_PATTERNS`
- `CONDDO_EMAIL_PROVIDER=brevo` + `CONDDO_BREVO_API_KEY` + `CONDDO_EMAIL_FROM` (verified sender)
- `CONDDO_AUTH_COOKIE_SAMESITE=None` + `_SECURE=true` for cross-site Vercel
- `PAYMENTS_BASE_URL` + `PAYMENTS_SERVICE_TOKEN` (the conddo-api → conddo-payments seam)
- `GOOGLE_OAUTH_CLIENT_ID` *(once Google Sign-in lands)*
- `CLOUDINARY_URL`

**conddo-payments:**
- `PAYMENTS_DB_*`
- `PAYMENTS_SERVICE_TOKEN` (same value as conddo-api)
- `ROUTEPAY_*` (client id/secret, webhook secret, base URLs)
- `CONDDO_API_BASE_URL` (for the internal notify callback)

---

## 9. Success Metrics

Measure these — they're the only things that matter.

### 9.1 Tenant adoption

- **Signups completed per week.** Target: 50+ by month 3.
- **First-week active tenants.** Tenant who logs in 3+ times in their first 7 days.
- **First-month retention.** % of new tenants still active in week 4.
- **NPS** at day 30. Survey via email.

### 9.2 Tenant outcomes

- **First sale recorded.** % of tenants who record at least one order in first 2 weeks.
- **Website live.** % of tenants whose website is delivered within 7 days of signup.
- **Marketing campaign sent.** % of tenants who send at least one campaign in first 30 days.

### 9.3 Platform health

- **API p95 latency** (excluding cold start).
- **Signup conversion** (start → verify → complete) at each stage.
- **Render cold-start rate** (downside indicator — should drop when we leave free tier).

---

## 10. Constraints & Assumptions

### 10.1 Constraints

- **Render free tier sleeps after 15 min idle.** Manifests as cold-start latency on the first request after idle. Mitigations: async listeners, FE timeouts, eventual upgrade to paid tier.
- **Brevo free tier: 300 transactional emails/day.** Sufficient for V1 launch; alert when approaching.
- **No multi-region deployment.** All infra in single Render/Vercel region. Latency to Lagos / Abuja acceptable for V1.
- **Nigerian SMS regulation.** Phone OTP is required for tenant signup — Google identifies the user, not the device.
- **One Postgres.** Shared between conddo-api and conddo-payments. Schema isolation only.

### 10.2 Assumptions

- **Most users on mobile, on 3G.** Mobile-first design is non-negotiable. Avoid blocking JS for first paint.
- **English-only V1.** No i18n machinery — copy is direct English with Nigerian context.
- **Naira-native.** No multi-currency. RoutePay + Paystack handle the rail.
- **Manual onboarding for first 100 tenants.** The production team builds every website. Automation comes later if at all.
- **One tenant = one workspace.** No cross-workspace identity in V1 (a person might own two businesses → two separate tenants, separate logins).

---

## 11. Open Questions

These are tracked but unresolved:

1. **Custom domains.** Today every tenant lives at `<slug>.conddo.io`. Do we support `tenant.com` as a custom domain in V1? (Implies cert provisioning + DNS automation.)
2. **Tenant data export.** GDPR-style "give me all my data". Not yet built; should we ship before V1 launch or after?
3. **Tenant deletion / suspension UX.** The platform admin backend covers this; what does the tenant *see* when their workspace is suspended?
4. **Multi-staff billing.** If a tenant has 5 staff, are they per-seat or flat-rate? Today: flat-rate per plan tier.
5. **RoutePay vs. Paystack.** Why both? Pick one, or use Paystack default + RoutePay for specific verticals?
6. **In-app builder.** Currently descoped — staff build externally. Is this permanent (no in-app builder ever) or do we revisit in V2?
7. **Customer mobile app.** Native or PWA? Today: mobile-web only. When (if) does a native app land?

These belong in this doc until they're answered — once decided, move the answer to the relevant section and the constraint to §10.

---

## 12. Doc Map

The full set of project documentation, in roughly the order you'd read them:

### Start here
- **REQUIREMENTS.md** (this doc) — front door, scope, users, requirements.

### Architecture
- [ARCHITECTURE.md](./ARCHITECTURE.md) — high-level architecture + deployment topology.
- [VERTICALS.md](./VERTICALS.md) — per-vertical configuration matrix.

### Backend specs
- [ACTION_LIST.md](./ACTION_LIST.md) — conddo-api roadmap + endpoint catalogue (PRD §6.2, §13.1).
  - §1 Authentication (incl. §1a Google Sign-in)
  - §2 Subdomain → tenant resolution
  - §3 Audit log
  - §4 RLS coverage
  - §5 Notifications engine
  - §6 Job queue + Redis
  - §7 Billing + §7a Payments (conddo-payments)
  - §7b Social integrations
  - §9 AI Assistant Layer
  - §11 Dashboard module → endpoint contracts per screen
- [BILLING_TIERS_SPEC.md](./BILLING_TIERS_SPEC.md) — plan tiers + feature gating.
- [SOCIAL_AND_CREATIVE_SERVICES_SPEC.md](./SOCIAL_AND_CREATIVE_SERVICES_SPEC.md) — social-marketing surface (Ayrshare).
- [WEBSITE_INTEGRATION_SPEC.md](./WEBSITE_INTEGRATION_SPEC.md) — public tenant-site API.

### Deploy / ops
- [DEPLOY.md](./DEPLOY.md) — deploy runbook.
- [README.md](./README.md) — repo-level entry point.

### Frontend repos
- `github.com/Mickercode/conddo.io` — tenant platform FE (Next.js 14)
- `github.com/Mickercode/conddo-backend` (this repo) — backend monorepo (conddo-api + conddo-payments + conddo-core)

---

*Conddo.io — Multi-tenant SaaS for Nigerian SMEs · Built by Handel Cores · Confidential*
