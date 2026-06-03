# Conddo.io — Product Requirements

**Status:** Living document
**Last updated:** 2026-06-03
**Owner:** Mickercode
**Cross-references:** [ARCHITECTURE](./ARCHITECTURE.md) · [ACTION_LIST](./ACTION_LIST.md) · [conddo_studio_combined](./conddo_studio_combined.md) · [conddo_infrastructure](./conddo_infrastructure.md) · [SERVICE_TOPOLOGY](./SERVICE_TOPOLOGY.md) · [VERTICALS](./VERTICALS.md) · [FRONTEND_STATUS](./FRONTEND_STATUS.md) · [BACKEND_STATUS](./BACKEND_STATUS.md) · [DEPLOY](./DEPLOY.md)

This is the front-door spec — what Conddo.io is, who it's for, what it must
do, and where the detailed specs live. Everything below either references or
summarises an existing doc; if you need the depth, follow the link.

---

## Table of Contents

1. [Product](#1-product)
2. [Scope](#2-scope)
3. [Users & Roles](#3-users--roles)
4. [Functional Requirements — Tenant Platform (conddo-app)](#4-functional-requirements--tenant-platform-conddo-app)
5. [Functional Requirements — Conddo Studio](#5-functional-requirements--conddo-studio)
6. [Functional Requirements — Service-to-Service](#6-functional-requirements--service-to-service)
7. [Non-Functional Requirements](#7-non-functional-requirements)
8. [Architecture Overview](#8-architecture-overview)
9. [Deployment Topology](#9-deployment-topology)
10. [Success Metrics](#10-success-metrics)
11. [Constraints & Assumptions](#11-constraints--assumptions)
12. [Open Questions](#12-open-questions)
13. [Doc Map](#13-doc-map)

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
   — the Conddo production team (internal Handel Cores staff in **Conddo
   Studio**) builds it for them and ships the link. This is the single
   biggest "done for you" delta vs Wix / Selar.
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

**Conddo Studio (internal ops platform):**
- Staff auth (separate JWT, separate refresh-token model)
- Worker loop: claim → start → submit jobs
- QA queue + review with section-grouped checklists
- Admin: operations dashboard, all jobs (reassign / escalate / extend SLA),
  staff CRUD, job types CRUD, design standards library, lead actions
- Performance dashboard (jobs completed, target progress, first-pass QA,
  revisions received)
- Notifications + header bell (SSE-driven, no polling)
- Real-time job board / QA queue refresh via SSE (`/api/jobs/events`)
- Asset uploads (Cloudinary, drafts + deliverables)
- AI assistant API client (copy generation, palette, image ranking — UI lands
  with the builder workflow)
- Bootstrap-first-admin seeder for fresh deploys

**Service-to-service:**
- Platform tenant signup → Conddo Studio job auto-create
  (`POST /api/jobs/intake` with shared service token)
- Async listener with timeout — Studio downtime never blocks signup

**Infrastructure:**
- conddo-api on Render (Spring Boot 3, Java 21)
- conddo-studio on Render (separate service, separate JWT, separate port)
- Postgres (Render-managed, shared between both services, separate schemas)
- conddo-app on Vercel (Next.js 14)
- conddo-studio FE on Vercel (Next.js 14, separate project)
- Brevo for transactional email
- Cloudinary for media
- Resend as fallback email

### 2.2 Pending / In-flight V1

- **Google Sign-in (backend half)** — FE wired, backend has staged work
  (V21 migration, `GoogleIdTokenVerifier`, DTOs, tests). See
  [ACTION_LIST §1a](./ACTION_LIST.md).
- **Studio Export/Import** — the offline builder workflow.
  See [conddo_studio_combined §22](./conddo_studio_combined.md#22-job-export--import).
- **Studio Platform Admin** — Studio ADMINs managing tenants + tenant users
  cross-platform. See [conddo_studio_combined §23](./conddo_studio_combined.md).
- **RoutePay payment integration** — separate service (workstream deferred).

### 2.3 Explicitly out of scope (descoped)

- **In-app website builder** (`§21`, formerly Phase 11). Decision 2026-06-03:
  staff build websites externally (Studio.io, VS Code, Figma, Framer,
  customer hosting). Conddo Studio is the **system of record** for the
  brief, assets, AI suggestions, design standards, and QA history — not the
  website source. The export/import (`§22`) is the bridge to external
  tooling.

### 2.4 V2 candidates

Not yet on the roadmap, captured for memory:

- Cross-tenant ops bot (e.g. WhatsApp customer service automation)
- AI website-building agent that prepares the brief into a near-finished site
- Customer-facing mobile app (today's mobile-web is mobile-first but not native)
- Workflow automation builder (Zapier-equivalent inside Conddo)

---

## 3. Users & Roles

Three populations interact with the product, on three different identity
axes. **Studio staff are not platform users; platform users are not Studio
staff.** They authenticate via different endpoints, hold different JWTs, and
have separate database tables.

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

### 3.3 Studio staff (internal ops team, no RLS)

Source of truth: `studio.staff` table in conddo-studio service. The Handel
Cores production team.

| Role | Capabilities |
|---|---|
| `DEVELOPER` | Sees jobs matching their skills. Claims, builds, submits. |
| `DESIGNER` | Same workflow, design jobs. |
| `WRITER` | Same workflow, copy jobs. |
| `QA_REVIEWER` | QA queue + review. Sees only the QA surface. |
| `TEAM_LEAD` | All worker views + admin reads + lead actions (reassign, extend SLA, escalate). |
| `ADMIN` | All of the above + staff CRUD + job-type CRUD + design-standards CRUD + (planned) cross-tenant management of platform users. |

The Studio bootstrap-first-admin seeder (`StudioAdminBootstrap.java`) plants
the very first ADMIN when `studio.staff` is empty, driven by env vars. After
that, ADMINs invite the rest of the team.

---

## 4. Functional Requirements — Tenant Platform (conddo-app)

Deep dive in [ACTION_LIST §11](./ACTION_LIST.md). Below is the V1 surface
the FE actually consumes, with backend status. Endpoint-level inventory:
[FRONTEND_STATUS §2](./FRONTEND_STATUS.md).

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

Listener `TenantActivationListener` runs `@Async @TransactionalEventListener
AFTER_COMMIT` — calls Studio's `/api/jobs/intake` to auto-create a
`WEBSITE_BUILD` job. **Async** so a sleeping Studio never blocks signup.

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
| Website | `GET /api/v1/website/**`, change-request → Studio intake |
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

## 5. Functional Requirements — Conddo Studio

Deep dive in [conddo_studio_combined.md](./conddo_studio_combined.md). What
the FE consumes today (42 of 42 user-facing endpoints):

### 5.1 Auth (`/api/jobs/auth/*`)

| Endpoint | Notes |
|---|---|
| `POST /login` | HMAC JWT (HS256), separate from platform's RSA. |
| `POST /refresh` | **Body-based refresh token** (not cookies — different domain). |
| `POST /logout` | Revokes refresh token. |
| `GET /me` | Returns the staff profile. **Carries Bearer token** (the one auth-namespaced endpoint that does). |

### 5.2 Jobs (`/api/jobs/*`)

| Endpoint | Role | Notes |
|---|---|---|
| `GET /my-jobs` | All staff | Jobs assigned to me. |
| `GET /available` | All staff | Unclaimed jobs matching my skills. |
| `GET /{id}` | All staff | Job detail. |
| `POST /{id}/claim` | All staff | Optimistic lock on status. |
| `PATCH /{id}/start` | Assignee | ASSIGNED → IN_PROGRESS. |
| `POST /{id}/submit` | Assignee | IN_PROGRESS → SUBMITTED. |
| `POST /{id}/ai-suggest` | All staff | Claude copy generation. |
| `POST /ai/palette` | All staff | Claude colour palette. |
| `POST /{id}/rank-images` | All staff | Claude vision image ranking. |

### 5.3 QA (`/api/jobs/qa/*`)

| Endpoint | Role |
|---|---|
| `GET /queue` | QA_REVIEWER+ |
| `POST /{id}/start` | QA_REVIEWER+ |
| `POST /{id}/approve` | QA_REVIEWER+ |
| `POST /{id}/return` | QA_REVIEWER+ |
| `GET /{id}/scan` | QA_REVIEWER+ — AI QA scan |

### 5.4 Admin (`/api/jobs/admin/*`)

| Endpoint | Role |
|---|---|
| `GET /dashboard` | TEAM_LEAD+ |
| `GET/POST/PATCH /staff` | ADMIN (POST, PATCH) / TEAM_LEAD+ (GET) |
| `POST /jobs` | ADMIN |
| `PATCH /{id}/reassign` / `extend-sla` / `escalate` | TEAM_LEAD+ |
| `GET/POST/PATCH/DELETE /job-types` | TEAM_LEAD reads, ADMIN mutates |
| `GET/POST/PATCH/DELETE /design-standards` | TEAM_LEAD reads, ADMIN mutates |

### 5.5 Realtime (`/api/jobs/events` — SSE)

One persistent connection per browser tab. Events filtered by the
authenticated staff's role/skills. The FE uses `@microsoft/fetch-event-source`
because browser EventSource can't set Authorization headers.

| Event | Recipients | FE handler |
|---|---|---|
| `hello` | Subscriber | Ignored |
| `heartbeat` | Subscriber (every 30s) | Ignored |
| `notification.created` | Targeted staff | Bell + page silent-refetch |
| `job.created` / `claimed` | Skill-matched | Available + Available Jobs page refetch |
| `job.started` / `submitted` | Assignee + QA_REVIEWER | QA queue + My Jobs |
| `job.approved` / `revision_requested` | Assignee | My Jobs + QA queue |
| `job.reassigned` / `escalated` / `sla_extended` | Affected | Admin dashboards + My Jobs |
| `sla.tick` | TEAM_LEAD + ADMIN (every 5 min) | Operations dashboard |

### 5.6 Assets, Performance, Notifications

| Endpoint | Notes |
|---|---|
| `POST/GET/DELETE /api/jobs/{jobId}/assets` | Cloudinary-backed, 10MB per file. |
| `GET /api/jobs/performance/me` / `/{staffId}` | Returns `{jobsCompleted, jobsTarget, firstPassQaRate, revisionsReceived}`. |
| `GET /api/jobs/notifications` (`?unread=true`) | Feed + unread count. |
| `PATCH /api/jobs/notifications/{id}/read` | Single. |
| `PATCH /api/jobs/notifications/read-all` | Bulk. |

### 5.7 Pending Studio capabilities

| Capability | Spec | Why |
|---|---|---|
| **Export/Import** (`§22`) | `GET /api/jobs/{id}/export` (ZIP), `POST /api/jobs/{id}/import` (multipart) | Staff take a job offline, build externally, re-import the result. This is the builder workflow. |
| **Platform Admin** (`§23`) | `GET/PATCH/DELETE /api/jobs/admin/platform/tenants/**` + `/users/**` | Studio ADMINs manage tenants + tenant users cross-platform. Read-only first (Phase 13a), mutations second (Phase 13b). |

---

## 6. Functional Requirements — Service-to-Service

Defined in [SERVICE_TOPOLOGY.md](./SERVICE_TOPOLOGY.md). Today:

### 6.1 Platform → Studio job intake

On `TenantActivatedEvent`:
- `TenantActivationListener` runs **async** (`@Async`, `@EnableAsync` on `ConddoApplication`)
- Calls `HttpStudioJobGateway` with the brief
- Gateway uses RestClient with **5s connect / 10s read timeout** — never blocks signup
- Studio's `POST /api/jobs/intake` validates `X-Studio-Service-Token` and creates a `WEBSITE_BUILD` job
- Brief includes: `tenantId`, `tenantSlug`, `businessName`, `vertical`, `plan`, `websiteType`, `recommendedSections`, `contactEmail`, `contactPhone`

**Required env vars on conddo-backend:**
- `STUDIO_BASE_URL=https://conddo-studio.onrender.com`
- `STUDIO_SERVICE_TOKEN=<same value as on conddo-studio service>`

If either is unset, `HttpStudioJobGateway` no-ops and the tenant is created without a Studio job (recoverable via manual `POST /api/jobs/intake`).

### 6.2 Future S2S surfaces

- **Studio → Platform** (planned, §23 Phase 13a/13b): Studio ADMIN endpoints read `public.tenants` and `public.users` via shared Postgres (Studio runs as `conddo_owner` role with cross-schema access).
- **Platform → Sites** (planned): when `conddo-sites` ships, Studio will tell it which tenant got a new published site.

---

## 7. Non-Functional Requirements

### 7.1 Performance

| Surface | Target | Notes |
|---|---|---|
| API p95 latency (warm) | < 500 ms | Most endpoints. |
| Cold start (Render free tier) | < 60 s | Documented behaviour; FE surfaces it as a clean timeout. |
| FE request timeout | 45 s JSON, 120 s upload | Hard ceiling. |
| SSE heartbeat interval | 30 s | Keeps proxies from cutting the stream. |
| SLA monitor scan | 5 min (configurable) | Studio job board. |
| Performance recalc | Daily 02:00 UTC | Per-staff monthly snapshots. |

### 7.2 Security

| Area | Decision |
|---|---|
| **Tenant isolation** | Postgres RLS, enforced by `tenant_isolation` policies on every tenant-scoped table. Phase-0 + Phase-1 done; coverage tests in `AuthFlowTest`. |
| **JWT** | RSA-256 on platform (15 min); HMAC-SHA256 on Studio (8 hour). Keys mounted, never committed. |
| **Refresh tokens** | Platform: httpOnly cookie with rotation + family-reuse detection. Studio: body-based with refresh token in localStorage. |
| **Lockout** | 5 failed logins → 15 min lockout (exponential backoff). Shared `LockoutPolicy`. |
| **Password hashing** | BCrypt cost 12. |
| **Audit** | `audit_log` table on every state change (tenants, users, payments, signup completion). |
| **CORS** | Exact origins + wildcard patterns. Never `*` — credentials are allowed. |
| **CSRF** | Not needed (no session cookie, refresh cookie is `SameSite=Strict` or `None`+`Secure`). |
| **OWASP Top 10** | Covered via Spring Security defaults + manual `@PreAuthorize` per method. |

### 7.3 Accessibility

| Requirement | Status |
|---|---|
| Keyboard navigation throughout | ✅ across both FEs |
| Visible focus rings on every interactive element | ✅ via Tailwind `focus:` utilities |
| Colour contrast ≥ 4.5:1 (WCAG AA) | ✅ tokens designed to AA on both light + dark themes |
| `aria-label` on icon-only buttons | ✅ throughout |
| `prefers-reduced-motion` | ✅ HeroPreview freezes; RouteTransition skips |
| Form labels paired with inputs | ✅ Field component throughout |

### 7.4 Internationalisation

- **Currency**: Naira (₦) only. Format as `₦1.84M`, `₦248,500`, etc. — `lib/format.ts` `naira()` helper.
- **Phone**: Nigerian E.164 (`+234…`). FE strips leading zeros, prepends `+234`.
- **Date/time**: `en-NG` locale (`new Date().toLocaleString("en-NG", …)`).
- **Cities**: Lagos, Abuja, Port Harcourt (primary), expanding.
- **No multi-language** in V1 — English only. Verticals + tone vary, not language.

### 7.5 Mobile

| Requirement | Note |
|---|---|
| Mobile-first design | All FE components built for 360px first. |
| Hero stacks vertically | Verified after audit (b06a700). |
| Frame URL pill truncates | Doesn't push chrome dots off-screen. |
| HeroPreview tab strip on mobile | Visible substitute for the hidden sidebar. |
| Pricing cards stack | `grid-cols-1 md:grid-cols-3`. |
| Sidebar drawer on mobile | StudioShell + AppShell both have it. |

### 7.6 Email & SMS reliability

- **Email**: Brevo primary (transactional, 300/day free tier), Resend fallback for one-account testing.
- **SMS**: Brevo SMS or Termii (planned).
- **OTP**: 4-digit, 10-min TTL, max 5 attempts, 30s resend cooldown, max 5 resends.
- **High-priority Studio events** mirrored to email (`§13.4` — Brevo via `STUDIO_EMAIL_PROVIDER=brevo`).

---

## 8. Architecture Overview

Full detail: [ARCHITECTURE.md](./ARCHITECTURE.md), [conddo_infrastructure.md](./conddo_infrastructure.md).

### 8.1 Two-plane model

Conddo.io runs on **two planes**:

```
┌───────────────────────────────────────────────────────────────────┐
│                       CONTROL PLANE (tenant)                       │
│                                                                    │
│   conddo-app (Next.js)  ──→  conddo-api (Spring Boot)             │
│   the customer dashboard      tenant-scoped, RLS-enforced          │
│                               schema: public.*                     │
│                                                                    │
└────────────────────┬──────────────────────────────────────────────┘
                     │  (signup → intake)
                     │  X-Studio-Service-Token
                     ▼
┌───────────────────────────────────────────────────────────────────┐
│                  PRODUCTION PLANE (internal ops)                   │
│                                                                    │
│   conddo-studio FE       ──→  conddo-studio (Spring Boot)         │
│   (Next.js)                   non-RLS, owner-role                  │
│   the build team              schemas: studio.*, jobs.*           │
│                                                                    │
└───────────────────────────────────────────────────────────────────┘
                     │
                     ▼  (writes the website artefacts;
                         live site lives externally)
              external builder tools
              (Studio.io / Figma / Framer)
```

### 8.2 Why two services

- **Different blast radius.** Tenant data is RLS-isolated; Studio data is internal. Mixing them in one service either compromises RLS or restricts the production team.
- **Different identity model.** Platform uses RSA JWTs + httpOnly refresh cookies; Studio uses HMAC JWTs + body-based refresh.
- **Different deploy cadence.** Ops tooling can iterate faster without risking customer auth.
- **Different operational characteristics.** Studio has SSE streams and AI workloads; conddo-api is request/response.

### 8.3 Shared Postgres

Both services connect to the same Postgres instance but own different
schemas:

- `public.*` — tenants, users, customers, orders, etc. RLS-enforced. Owned by conddo-api.
- `studio.*` — staff, job_types, design_standards. No RLS (internal only). Owned by conddo-studio.
- `jobs.*` — job records, activity, notifications. No RLS. Owned by conddo-studio.

Studio runs as the `conddo_owner` role — full access to all schemas. This
lets it read `public.tenants` / `public.users` directly for the planned
§23 Platform Admin endpoints, without HTTP hops.

---

## 9. Deployment Topology

Full detail: [DEPLOY.md](./DEPLOY.md), [conddo_infrastructure.md](./conddo_infrastructure.md).

### 9.1 Hosting

| Service | Runtime | Where |
|---|---|---|
| conddo-app | Next.js 14 | Vercel (project `conddo.io`) |
| conddo-studio FE | Next.js 14 | Vercel (project `conddo-studio`) |
| conddo-api | Spring Boot 3 / Java 21 | Render (`conddo-backend`) |
| conddo-studio (backend) | Spring Boot 3 / Java 21 | Render (`conddo-studio`) |
| Postgres | Managed | Render |
| Cloudinary | SaaS | Cloudinary (one account, shared by both services) |
| Brevo | SaaS | Email + SMS |

### 9.2 Domains

| URL | Purpose |
|---|---|
| `conddo.io` | Marketing site + landing (tentant flow lands here on `/onboarding/create-account`) |
| `<slug>.conddo.io` | Per-tenant subdomain (resolves to tenant via Redis cache → JWT tenant_id) |
| `studio.conddo.io` | Conddo Studio FE (internal ops team) |
| `api.conddo.io` (planned) | API gateway (today: direct `conddo-backend.onrender.com`) |

### 9.3 Critical env vars

See [infra/conddo-studio.env.example](./infra/conddo-studio.env.example) for the Studio template. Key on both backends:

**conddo-api:**
- `CONDDO_DB_*` (URL, app user, owner user, passwords)
- `CONDDO_JWT_*` (issuer, RSA key paths, TTL)
- `CONDDO_CORS_ALLOWED_ORIGINS` + `_PATTERNS`
- `CONDDO_EMAIL_PROVIDER=brevo` + `CONDDO_BREVO_API_KEY` + `CONDDO_EMAIL_FROM` (verified sender)
- `CONDDO_AUTH_COOKIE_SAMESITE=None` + `_SECURE=true` for cross-site Vercel
- `STUDIO_BASE_URL` + `STUDIO_SERVICE_TOKEN` (the signup → Studio job seam)
- `GOOGLE_OAUTH_CLIENT_ID` *(once Google Sign-in lands)*
- `CLOUDINARY_URL`

**conddo-studio:**
- `STUDIO_DB_*`
- `STUDIO_JWT_SECRET` (≥ 32 bytes)
- `STUDIO_CORS_ALLOWED_ORIGINS` + `_PATTERNS`
- `STUDIO_SERVICE_TOKEN` (same value as conddo-api)
- `STUDIO_BOOTSTRAP_ADMIN_EMAIL` + `_PASSWORD` + `_NAME` (first-deploy only; remove after first login)
- `CLOUDINARY_URL`
- `CLAUDE_API_KEY` *(optional — AI assistant)*
- `STUDIO_EMAIL_PROVIDER=brevo` + Brevo creds *(optional — email mirror)*

---

## 10. Success Metrics

Measure these — they're the only things that matter.

### 10.1 Tenant adoption

- **Signups completed per week.** Target: 50+ by month 3.
- **First-week active tenants.** Tenant who logs in 3+ times in their first 7 days.
- **First-month retention.** % of new tenants still active in week 4.
- **NPS** at day 30. Survey via email.

### 10.2 Tenant outcomes

- **First sale recorded.** % of tenants who record at least one order in first 2 weeks.
- **Website live.** % of tenants whose Studio job reaches DELIVERED within 7 days of signup.
- **Marketing campaign sent.** % of tenants who send at least one campaign in first 30 days.

### 10.3 Studio operations

- **Average time from job created → delivered.** Target: < 72h for `WEBSITE_BUILD`.
- **First-pass QA approval rate.** % of jobs approved without a revision round. Target: > 70%.
- **SLA breach rate.** % of jobs that hit AMBER/RED. Target: < 10%.
- **Studio staff jobs/week.** Per-staff throughput.

### 10.4 Platform health

- **API p95 latency** (excluding cold start).
- **Signup conversion** (start → verify → complete) at each stage.
- **Render cold-start rate** (downside indicator — should drop when we leave free tier).

---

## 11. Constraints & Assumptions

### 11.1 Constraints

- **Render free tier sleeps after 15 min idle.** Manifests as cold-start latency on the first request after idle. Mitigations: async listeners, FE timeouts, eventual upgrade to paid tier.
- **Brevo free tier: 300 transactional emails/day.** Sufficient for V1 launch; alert when approaching.
- **No multi-region deployment.** All infra in single Render/Vercel region. Latency to Lagos / Abuja acceptable for V1.
- **Nigerian SMS regulation.** Phone OTP is required for tenant signup — Google identifies the user, not the device.
- **One Postgres.** Shared between conddo-api and conddo-studio. Schema isolation only.

### 11.2 Assumptions

- **Most users on mobile, on 3G.** Mobile-first design is non-negotiable. Avoid blocking JS for first paint.
- **English-only V1.** No i18n machinery — copy is direct English with Nigerian context.
- **Naira-native.** No multi-currency. RoutePay + Paystack handle the rail.
- **Manual onboarding for first 100 tenants.** Studio team builds every website. Automation comes later if at all.
- **One tenant = one workspace.** No cross-workspace identity in V1 (a person might own two businesses → two separate tenants, separate logins).

---

## 12. Open Questions

These are tracked but unresolved:

1. **Custom domains.** Today every tenant lives at `<slug>.conddo.io`. Do we support `tenant.com` as a custom domain in V1? (Implies cert provisioning + DNS automation.)
2. **Tenant data export.** GDPR-style "give me all my data". Not yet built; should we ship before V1 launch or after?
3. **Tenant deletion / suspension UX.** §23 Platform Admin spec covers the backend; what does the tenant *see* when their workspace is suspended?
4. **Multi-staff billing.** If a tenant has 5 staff, are they per-seat or flat-rate? Today: flat-rate per plan tier.
5. **RoutePay vs. Paystack.** Why both? Pick one, or use Paystack default + RoutePay for specific verticals?
6. **In-app builder.** Currently descoped — staff build externally. Is this permanent (no in-app builder ever) or do we revisit in V2?
7. **Customer mobile app.** Native or PWA? Today: mobile-web only. When (if) does a native app land?

These belong in this doc until they're answered — once decided, move the answer to the relevant section and the constraint to §11.

---

## 13. Doc Map

The full set of project documentation, in roughly the order you'd read them:

### Start here
- **REQUIREMENTS.md** (this doc) — front door, scope, users, requirements.

### Architecture
- [ARCHITECTURE.md](./ARCHITECTURE.md) — high-level architecture (control plane / production plane).
- [SERVICE_TOPOLOGY.md](./SERVICE_TOPOLOGY.md) — how the services talk to each other.
- [conddo_infrastructure.md](./conddo_infrastructure.md) — full infrastructure cookbook (deploy, Postgres, Redis, MinIO, Nginx, Docker).
- [VERTICALS.md](./VERTICALS.md) — per-vertical configuration matrix.

### Backend specs
- [ACTION_LIST.md](./ACTION_LIST.md) — conddo-api roadmap + endpoint catalogue (PRD §6.2, §13.1).
  - §1 Authentication (incl. §1a Google Sign-in)
  - §2 Subdomain → tenant resolution
  - §3 Audit log
  - §4 RLS coverage
  - §5 Notifications engine
  - §6 Job queue + Redis
  - §7 Billing
  - §11 Dashboard module → endpoint contracts per screen
- [conddo_studio_combined.md](./conddo_studio_combined.md) — Conddo Studio system (2,200+ lines).
  - §1–§14 Studio architecture, job system, AI, assets, QA, SLA, standards, performance
  - §15 REST API reference
  - §16 Frontend structure
  - §17 Environment variables
  - §18 Docker configuration
  - §19 Implementation sequence (phases)
  - §21 ⚠️ Website Builder API (OUT OF SCOPE — retained as reference only)
  - §22 Job Export & Import (the builder workflow — pending)
  - §23 Platform Admin (cross-tenant management — pending)
  - §24 Implementation rules

### Status / handoff
- [FRONTEND_STATUS.md](./FRONTEND_STATUS.md) — what each FE consumes, what's pending on the backend.
- [BACKEND_STATUS.md](./BACKEND_STATUS.md) — what's shipped backend-side, what's pending.

### Deploy / ops
- [DEPLOY.md](./DEPLOY.md) — deploy runbook.
- [README.md](./README.md) — repo-level entry point.
- [infra/conddo-studio.env.example](./infra/conddo-studio.env.example) — Render env-var template for the Studio service.

### Frontend repos
- `github.com/Mickercode/conddo.io` — tenant platform FE (Next.js 14)
- `github.com/Mickercode/conddo-studio` — Studio FE (Next.js 14)
- `github.com/Mickercode/conddo-backend` (this repo) — backend monorepo (conddo-api + conddo-studio + conddo-core)

---

*Conddo.io — Multi-tenant SaaS for Nigerian SMEs · Built by Handel Cores · Confidential*
