# Conddo.io Backend — Agent Handoff & Action List

> **Read this entire file before writing any code.** You are continuing work on
> the Conddo.io backend. This document is your single source of truth for what
> exists, how it works, the rules you must follow, and what to build next. The
> product spec (PRD) lives **outside this repo** (`../conddo_prd_v1.3.docx`,
> confidential), so the parts you need are summarised here. Current spec is
> **v1.3**, which added §22 (Conddo Studio) and §23 (AI Assistant Layer) —
> see the roadmap in §7.

---

## 0. TL;DR / Prime directives

- This repo (`conddo-backend`) is the **Java Spring Boot backend** for Conddo.io,
  a multi-tenant SaaS for Nigerian SMEs. The **frontend is a separate repo**
  (`landing/`, Next.js — not your concern here).
- **Phase 0 is done and compiles.** It establishes the spine: **multi-tenant
  isolation enforced by PostgreSQL Row Level Security (RLS).**
- **The #1 rule: never break tenant isolation.** Details in §3. If you only
  remember one thing: every tenant-scoped table needs an RLS policy, the app
  connects to Postgres as a **non-owner** role, and each transaction sets
  `app.tenant_id`.
- **Schema changes go through Flyway only** (`ddl-auto: validate`). Never let
  Hibernate generate DDL. Never edit an already-applied migration.

---

## 1. What this project is

Conddo.io gives a Nigerian small business a complete digital presence (website +
operations + marketing) in one subscription, pre-configured per business
"vertical" (Pharmacy, Fashion, etc.). Backend stack (per PRD): **Java Spring
Boot, PostgreSQL (with RLS for multi-tenancy), Redis, MinIO, Docker.**

Every business is a **tenant**. Four roles: `SUPER_ADMIN` (Handel Cores staff),
`TENANT_ADMIN`, `STAFF`, `CUSTOMER`.

---

## 2. Current state — what already exists (Phase 0 + Phase 1 auth)

Multi-module Maven project, **`./mvnw clean verify` is green** (19 tests: unit +
Testcontainers integration). Phase 0 (RLS spine) and Phase 1 item 1 (auth) done.

```
backend/                         <- this repo root (= conddo-backend)
├── pom.xml                      parent (Spring Boot 3.3.5, Java 17)
├── mvnw / mvnw.cmd              Maven wrapper (no system Maven needed)
├── infra/                       Docker Compose dev stack (you own this)
│   ├── docker-compose.yml       postgres:16, redis:7, minio
│   ├── .env.example             copy to .env for local
│   └── postgres/init/01-app-user.sh   creates the runtime DB role on first boot
├── conddo-core/                 Core Platform library (no main method)
│   └── src/main/java/io/conddo/core/
│       ├── tenant/              TenantContext, TenantSession, TenantContextMissingException
│       ├── auth/                JwtService, AuthService, StaffAuthService, RefreshTokenService, PasswordResetService, LockoutPolicy, Role, InternalRole, …
│       ├── notify/              NotificationPort (+ LoggingNotificationPort stub)
│       ├── common/              ApiResponse, ApiError  (the standard API envelope)
│       ├── domain/              Tenant, Customer        (JPA entities)
│       ├── repository/          TenantRepository, CustomerRepository
│       └── service/             TenantService, CustomerService
└── conddo-api/                  Spring Boot application
    └── src/
        ├── main/java/io/conddo/
        │   ├── ConddoApplication.java       (@SpringBootApplication, root pkg io.conddo)
        │   ├── api/config/                  StorageConfig, MinioProperties
        │   ├── api/security/                SecurityConfig, JwtConfig, JwtTenantContextFilter, RefreshCookies, …
        │   └── api/web/                      TenantController, CustomerController, AuthController, StaffAuthController, GlobalExceptionHandler, dto/
        ├── main/resources/
        │   ├── application.yml
        │   └── db/migration/                 V1__core_schema … V6__staff_users.sql  (Flyway)
        └── test/java/io/conddo/RlsIsolationTest.java   (Testcontainers — proves isolation; needs Docker)
```

**Working endpoints** (response in the standard envelope, see §6):
- `POST /api/v1/tenants` (signup — also creates the first TENANT_ADMIN); `GET /api/v1/tenants` (listing — SUPER_ADMIN only)
- `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/forgot-password`, `/auth/reset-password`
- `POST /auth/staff/login` — internal staff (SUPER_ADMIN); no tenant slug
- `POST /api/v1/customers` , `GET /api/v1/customers` — CRM, **tenant-scoped via RLS** (tenant from the JWT; SUPER_ADMIN may use `X-Act-As-Tenant`)
- `GET /actuator/health`

**Tables** (Flyway V1–V6): `tenants`, `users`, `refresh_tokens`, `audit_log`,
`customers`, `password_reset_tokens`, `staff_users`. **JPA entities:** `Tenant`,
`Customer`, `User`, `RefreshToken`, `PasswordResetToken`, `StaffUser` (no
`audit_log` entity yet). V3 adds auth columns/tables; V4 grants; V5 hardens the
RLS policies to fail closed on an empty `app.tenant_id` GUC (`NULLIF(...,'')`);
V6 adds the internal `staff_users` table and drops the now-obsolete sentinel
platform tenant.

**RLS enabled:** `customers`, `users`. **Deliberately NOT** on `refresh_tokens`
/ `password_reset_tokens` (credential tables read on *unauthenticated* requests —
protected by an unguessable selector; each row carries its own `tenant_id`; see
the V4 comment) nor on `staff_users` (a global, non-tenant table — §7 item 1).
`audit_log` RLS follows when its writer is built.

**Built:** authentication (§7 item 1 — done). **Not built yet:** subdomain→tenant
(Redis), audit-log writing, the notifications engine (only a `NotificationPort`
stub exists), job queue, billing. See §7.

---

## 3. The architecture you MUST understand: multi-tenancy via RLS

This is the heart of the system. Read carefully.

### The two-role model
- **`conddo_owner`** — owns the tables, runs **Flyway migrations**. Configured
  under `spring.flyway.*` in `application.yml`.
- **`app_user`** — the role the **application** connects as at runtime
  (`spring.datasource.*`). It is **NOT a table owner**, so PostgreSQL RLS
  policies are enforced against it and **cannot be bypassed from Java code**.
- ⚠️ **Never point the application datasource at `conddo_owner`** — owners bypass
  RLS and you'd silently leak every tenant's data to every tenant.

### How a request gets scoped
1. `TenantFilter` (registered in `WebConfig` for `/api/*`) reads the tenant id.
   **Phase 0: from the `X-Tenant-Id` header** (a placeholder). It puts it on
   `TenantContext` (a `ThreadLocal`) and clears it after the request.
2. A `@Transactional` service method calls **`tenantSession.bind()`** as its
   first line. That runs
   `SELECT set_config('app.tenant_id', '<uuid>', true)` on the transaction's
   connection. The `true` makes it **transaction-local**, so it resets on commit
   and never leaks across the connection pool.
3. Every RLS policy is
   `tenant_id = current_setting('app.tenant_id', true)::uuid`. The `true`
   (missing_ok) returns NULL when unset → **no rows match → fails closed**, never
   open.

### The rule for any NEW tenant-scoped table
1. Migration: column `tenant_id UUID NOT NULL REFERENCES tenants(id)`, plus
   `created_at TIMESTAMPTZ`.
2. Migration: `GRANT SELECT,INSERT,UPDATE,DELETE ON <table> TO ${app_role};`
   then `ALTER TABLE <table> ENABLE ROW LEVEL SECURITY;` and a
   `CREATE POLICY tenant_isolation ... USING (...) WITH CHECK (...)` (copy the
   pattern from `V2__rls.sql`). `${app_role}` is a Flyway placeholder.
3. Entity: include `tenantId`. Service: call `tenantSession.bind()` inside the
   `@Transactional` method, and set `tenantId` from `TenantContext.require()` on
   create. **Do NOT** add `findByTenantId(...)` — RLS already scopes `findAll()`.

---

## 4. Local environment & toolchain — CRITICAL gotchas

This machine is **Windows 11**. Two shells are available: **PowerShell** and
**git-bash**. Watch out for these — they will waste your time otherwise:

- **JDK 17** is installed (`C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot`). Good — Spring Boot 3 needs 17+.
- **Maven is NOT installed system-wide.** Use the wrapper **`./mvnw`** (downloads
  Maven on first run). A copy also exists at
  `C:\Users\ACER\tools\apache-maven-3.9.9\bin\mvn.cmd` if you need a direct binary.
- **Docker Desktop is installed but the engine may be down.** It had a **pending
  Windows reboot** after install. If `docker` commands fail: reboot Windows, then
  launch Docker Desktop and wait for the whale icon to show **"Engine running."**
  - `docker` is **not on the git-bash PATH** — full path:
    `C:\Program Files\Docker\Docker\resources\bin\docker.exe`. In PowerShell after
    a reboot it's usually on PATH.
- **Git identity** on this machine: `Mickercode <mercy09203@gmail.com>`. This repo
  has **1 commit on `main`, no remote yet** (push pending — see workspace README).
- **`.gitattributes` enforces LF** line endings. Keep shell scripts (`mvnw`,
  `infra/postgres/init/01-app-user.sh`) as LF or they break in the Linux
  container. Don't "fix" them to CRLF.
- **Tests need Docker** (Testcontainers spins up real Postgres). `./mvnw test`
  will fail if the Docker engine isn't running.
- **Testcontainers vs. modern Docker:** Docker Engine 24+/29.x requires API
  ≥ 1.40 and rejects docker-java's legacy 1.32 default with HTTP 400 ("Could not
  find a valid Docker environment"). The parent POM fixes this durably — bumps
  Testcontainers to 1.20.x and pins `-Dapi.version=1.43` via surefire — so plain
  `./mvnw verify` works (no `DOCKER_HOST` needed, even on Docker Desktop). Don't
  revert those without testing on a modern engine.

---

## 5. How to run, build, and test

```bash
# 1. Start the infra (needs Docker engine running)
cd infra
cp .env.example .env          # PowerShell: copy .env.example .env
docker compose up -d
docker compose ps             # wait for all "healthy"
cd ..

# 2. Run the API (Flyway migrates on boot; serves on :8080)
./mvnw spring-boot:run -pl conddo-api

# 3. Build everything + run tests (tests require Docker)
./mvnw clean verify

# 4. Just compile without Docker (no tests)
./mvnw clean test-compile -DskipTests
```

### Prove tenant isolation works (the demo)
```bash
A=$(curl -s -XPOST localhost:8080/api/v1/tenants -H 'Content-Type: application/json' \
     -d '{"name":"Amaka Styles","slug":"amaka-styles","verticalId":"fashion"}' | jq -r .data.id)
B=$(curl -s -XPOST localhost:8080/api/v1/tenants -H 'Content-Type: application/json' \
     -d '{"name":"Wellspring","slug":"wellspring","verticalId":"pharmacy"}' | jq -r .data.id)
curl -s -XPOST localhost:8080/api/v1/customers -H "X-Tenant-Id: $A" -H 'Content-Type: application/json' -d '{"fullName":"Alice"}'
curl -s -XPOST localhost:8080/api/v1/customers -H "X-Tenant-Id: $B" -H 'Content-Type: application/json' -d '{"fullName":"Bob"}'
curl -s localhost:8080/api/v1/customers -H "X-Tenant-Id: $A"   # -> only Alice
curl -s localhost:8080/api/v1/customers -H "X-Tenant-Id: $B"   # -> only Bob
```

---

## 6. Conventions & rules (follow these — don't reinvent)

- **Packages:** core code in `io.conddo.core.*`; app/web/config in `io.conddo.api.*`;
  main class is `io.conddo.ConddoApplication` (root package so scanning covers both
  modules). New domain logic → put entities/repos/services in `conddo-core`,
  controllers/config in `conddo-api`.
- **API envelope (PRD §13.2):** always return `ApiResponse.ok(data)` /
  `ApiResponse.ok(data, meta)`; never raw objects. Errors are produced by
  `GlobalExceptionHandler` as `ApiResponse.fail(ApiError.of(code, message, details))`.
  URLs are versioned: `/api/v1/...`.
- **Flyway:** add new files `V{n}__description.sql` in
  `conddo-api/src/main/resources/db/migration`. **Never modify an applied
  migration** (Flyway checksums them) — always add a new one. `${app_role}`
  placeholder resolves to the runtime role.
- **Entities:** `@GeneratedValue(strategy = GenerationType.UUID)` for ids,
  `@CreationTimestamp` + `@Column(updatable=false)` for `created_at`, map snake_case
  columns with `@Column(name=...)`. Because `ddl-auto: validate`, your entity MUST
  match the Flyway-created table exactly or the app won't boot.
- **Transactions:** tenant-scoped service methods are `@Transactional` and call
  `tenantSession.bind()` first.
- **Secrets:** never commit `.env` (gitignored). Dev passwords live in
  `infra/.env.example` and `application.yml` defaults — fine for local, change for
  prod via env vars (all config keys already support `${ENV_VAR:default}`).

---

## 7. ACTION LIST — Roadmap

This is your roadmap. Each item notes the PRD section and a definition of done.
**Items 1–7 are Phase 1 — build these now, top-down; auth unblocks the most.**
Items 8–9 are later phases (newly specified in PRD v1.3); they're captured here
so the scope and key data model are known — **do not build them yet.**

> 📋 **Per-module dashboard API contract is in §11.** The frontend dashboard
> module is being built screen-by-screen (Next.js, other repo). §11 audits every
> dashboard page + functionality and specifies the **REST endpoints each one
> needs**, so you can build module APIs in parallel with the frontend. The
> infra items below (auth, notifications, jobs, billing) are prerequisites that
> several §11 modules depend on.

### 1. Authentication & authorisation  ✅ DONE (PRD §6.2, §12.1)
Implemented end-to-end; the Testcontainers `AuthFlowTest` proves it. Key decisions
and where they live:
- **Access token:** RSA-256 JWT, 15-min, claims `sub`/`tenant_id`/`role`
  (`JwtService`; keys via `conddo.security.jwt.*`, gitignored dev pair, prod
  `CONDDO_JWT_*`).
- **Refresh token:** opaque `selector.verifier` (selector indexed; BCrypt-12
  verifier in `token_hash`), 30-day httpOnly + `SameSite=Strict` cookie scoped to
  `/auth`; **rotation + family-reuse detection** (a replayed revoked token kills
  the family — committed via `REQUIRES_NEW` in `RefreshTokenReuseGuard` so the
  kill survives the rejecting transaction).
- **Lockout:** 5 failures → 15-min, exponential backoff (shared `LockoutPolicy`).
- **Tenant resolution:** from the JWT `tenant_id` claim (`JwtTenantContextFilter`,
  in the chain after authentication). The Phase-0 `X-Tenant-Id` header +
  `TenantFilter`/`WebConfig` are **removed**.
- **Two role axes (per the directive below):** tenant users live in `users`
  (RLS-scoped, `Role` = TENANT_ADMIN/STAFF/CUSTOMER). Internal staff live in a
  **separate `staff_users`** table (no tenant_id, no RLS, `InternalRole`).
  **SUPER_ADMIN is internal staff** — it authenticates via `/auth/staff/login`,
  gets a tenant-less token, and scopes to a tenant per request via
  `X-Act-As-Tenant` (honoured only for SUPER_ADMIN). No BYPASSRLS, no owner
  datasource — RLS is always enforced. The Conddo Studio roles (§22) slot into
  `staff_users`/`InternalRole` when built. **Phase-1 staff get an access token
  only; staff refresh tokens are deferred to Phase 3.**
- **Password reset:** `/auth/forgot-password` + `/auth/reset-password`; delivery
  via `NotificationPort` — a logging **stub** until item 5. Reset revokes all of
  the user's refresh tokens.
- **Method security:** `@EnableMethodSecurity`; e.g. `TenantService.findAll` is
  `@PreAuthorize("hasRole('SUPER_ADMIN')")`. 401/403 use the standard envelope.
- **First admin:** created atomically by tenant signup (`TenantService.create`).

Original requirements (kept for reference; all satisfied):
- Add `spring-boot-starter-security`.
- **Access token:** JWT, **RSA-256** (private key signs, public key verifies),
  **15-min** expiry, carries `sub` (user id), `tenant_id`, `role`.
- **Refresh token:** opaque random string, **bcrypt-hashed** in `refresh_tokens`,
  30-day expiry, delivered as `httpOnly` + `Secure` + `SameSite=Strict` cookie.
  Implement **rotation** (each use issues a new one, old one revoked) and
  **token-family invalidation** (reuse of a revoked token kills the family).
- **Account lockout:** 5 failed logins → 15-min lockout (exponential backoff).
- Add `User` entity + `UserRepository` (table already exists). Password hashing:
  **BCrypt cost 12**.
- Endpoints: `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`,
  `POST /auth/forgot-password`, `POST /auth/reset-password` (PRD §13.1).
- **Tenant resolution:** after auth, set `TenantContext` from the JWT's
  `tenant_id` claim (in a filter that runs after authentication), replacing the
  `X-Tenant-Id` placeholder. `SUPER_ADMIN` needs a bypass story (it has no single
  tenant) — design it (e.g., a role with `BYPASSRLS`, or explicit tenant selection).
- **⚠️ Two role axes — decide this NOW, it shapes the schema (PRD v1.3 §22):**
  the four roles above are *tenant-facing*. v1.3 §22 (Conddo Studio) introduces
  *internal Handel Cores staff* roles — **Website Developer, QA Reviewer,
  Production Team Lead** — who are **not tenant-scoped** (they work across all
  tenants' website jobs). Do **not** shoehorn them into the `users` table with a
  nullable `tenant_id`. Recommended: a separate `staff_users` table + an
  `internal_role` enum, authenticated through the same JWT machinery but never
  setting `app.tenant_id`. This keeps `users` + RLS cleanly tenant-scoped and
  the internal tool isolated. Getting this right now avoids a painful migration
  when Conddo Studio (Phase 3) lands.
- **Method-level security:** annotate service methods with required roles
  (`@PreAuthorize`), not just controllers (PRD §6.2).
- _Done when:_ a user logs in, gets tokens, calls `/api/v1/customers` with no
  `X-Tenant-Id` (tenant comes from the JWT), and only sees their tenant's data;
  refresh rotation + lockout covered by tests.

### 2. Subdomain → tenant resolution via Redis (PRD §6.3)
- `businessname.conddo.io` → resolve subdomain to `tenant_id`, cached in Redis
  (refresh ~5 min). Complements the JWT claim. Redis starter is already wired.

### 3. Audit log writer (PRD §6.5, §12.5)
- Write to `audit_log` on sensitive actions: user, ip, user-agent, action,
  resource, `before_state`/`after_state` (JSONB). Add RLS to `audit_log`.

### 4. Finish RLS coverage
- Add RLS policies to `refresh_tokens` (scope via the owning user's tenant) and
  any new tenant-scoped tables, per the §3 pattern.

### 5. Notifications engine (PRD §6.4)
- Centralised service all modules call. Channels: **Resend** (email), **Termii**
  (SMS, Nigerian). Event-driven; modules fire events, the engine delivers.

### 6. Job queue + event bus on Redis (PRD §6.5, §6.6)
- Background jobs (persisted, retried) and an internal event bus (Redis Pub/Sub)
  so modules stay decoupled.

### 7. Billing (PRD §14)
- Paystack subscriptions; tiers; 14-day trial; failed-payment grace period.

---

> **Items 8–9 below are LATER PHASES — newly specified in PRD v1.3. Do not build
> them during Phase 1. They are documented so the data model and scope are known
> and so the Phase 1 auth/role design accounts for them.**

### 8. Conddo Studio — internal build tool (PRD v1.3 §22, build "Phase 3")
The internal web app the salaried production team uses to build every customer
website. **Internal staff only — not tenant-scoped** (see the two-role-axis note
in item 1). It does NOT touch tenant RLS.
- **Actors:** Website Developer, QA Reviewer, Production Team Lead, Super Admin
  (the `internal_role` axis).
- **Job state machine — 8 states, in order:**
  `QUEUED → ASSIGNED → IN_PROGRESS → SUBMITTED → IN_REVIEW → REVISION → APPROVED → LIVE`.
  Model as a `website_jobs` table + a `job_events` history table (who/when/state
  transition) for the audit trail and SLA tracking.
- **Likely tables:** `website_jobs` (business brief snapshot, vertical, plan tier,
  assigned_developer, sla_deadline, state), `job_events`, `website_sections`
  (per-job configured sections + chosen variant + content JSONB),
  `qa_reviews` (checklist results, pass/return, feedback), `design_standard_library`.
- **Surfaces:** developer dashboard/job queue (SLA Green/Amber/Red), read-only
  business-brief panel, vertical-specific **section library** (3–4 variants each,
  required vs optional fields), 3-panel build interface (brief | builder | live
  preview at 1280/768/375), auto-applied **brand config**, **pre-submission
  checklist** (blocking checks vs warnings), **QA review** split-screen, and the
  **jobs board** with Team Lead assignment/capacity/performance tools.
- **Note:** SLA timers/queue mechanics ride on the Phase-1 **job queue + Redis**
  (item 6). Typography is fixed: Inter + Geist Mono (matches the landing brand).

### 9. AI Assistant Layer (PRD v1.3 §23, cross-cutting)
Six Claude-powered features inside Conddo Studio (1–5) and the tenant dashboard
(6). **Human-in-the-loop is mandatory — AI only ever drafts; a developer/owner
reviews and approves. Never auto-publish.**
1. **Copy Generator** — drafts section copy from the business brief.
2. **Layout Suggestion** — content-aware section ordering per vertical.
3. **Image Ranker** — vision-based quality/relevance scoring of uploaded photos.
4. **Colour Palette Generator** — WCAG-AA palette from the primary colour
   (algorithm + model). (Mirrors the landing-page token system.)
5. **Pre-submission QA Scanner** — catches what rule checks can't (template-y
   copy, tone drift, facts inconsistent with the brief, generic CTAs).
6. **Business Marketing Assistant** — captions / email / SMS / ad copy in the
   tenant dashboard.
- **Prompt design:** 4-layer system prompt (identity → copy guidelines incl. a
  banned-AI-clichés list → vertical tone guide injected from config →
  section-specific instructions), business brief as user context.
- **Safeguards:** unedited AI copy is flagged in QA; generation logs stored per
  job; model fallback degrades gracefully (Studio marks AI offline, build
  continues, SLA clock does **not** pause).
- **⚠️ Model IDs:** the PRD pins `claude-sonnet-4-20250514`, which is dated. When
  we build this, target the **current Sonnet (`claude-sonnet-4-6`)** for text and
  a current vision-capable model for the Image Ranker, and **use the `claude-api`
  skill** so it ships with **prompt caching** (the 4-layer system prompt is a
  perfect cache candidate) from day one.

---

## 8. Pitfalls — do NOT do these

- ❌ Point the app datasource at `conddo_owner` (bypasses RLS — catastrophic leak).
- ❌ Add manual `WHERE tenant_id = ?` filtering in queries — rely on RLS; manual
  filtering is redundant and a bug magnet.
- ❌ Forget `tenantSession.bind()` in a new tenant-scoped `@Transactional` method
  → queries return **nothing** (RLS fails closed) and you'll waste an hour confused.
- ❌ Change `ddl-auto` away from `validate` / let Hibernate create schema. Use Flyway.
- ❌ Edit an already-applied Flyway migration (checksum mismatch on boot). Add a new `V{n}`.
- ❌ Commit `.env`, secrets, or `target/`. Convert shell scripts to CRLF.
- ❌ Run tests expecting them to pass without Docker running.

---

## 9. Open questions that affect the backend (PRD §20)

These are unresolved product decisions — flag them, don't silently assume:
- VPS provider (Hetzner vs DigitalOcean) for self-hosting.
- Managed PostgreSQL vs self-hosted from day one.
- Disaster recovery: backup frequency, RTO, RPO.
- Meta API rate-limit strategy across many tenants (later, marketing phase).
- CDN (Cloudflare?) for static assets.

---

## 10. Quick reference

| Thing | Value |
|---|---|
| Run API | `./mvnw spring-boot:run -pl conddo-api` (port 8080) |
| Build+test | `./mvnw clean verify` (needs Docker) |
| Start infra | `cd infra && docker compose up -d` |
| App DB role (runtime) | `app_user` / `app_password` (non-owner — RLS applies) |
| Migration DB role | `conddo_owner` / `owner_password` |
| DB / Redis / MinIO | `localhost:5432` / `:6379` / `:9000` (console `:9001`) |
| Tenant context | from the access-token `tenant_id` claim (`JwtTenantContextFilter`); SUPER_ADMIN: `X-Act-As-Tenant` header |
| JWT dev keys | gitignored; generate with `openssl` (see README) or rely on the CI step; prod uses `CONDDO_JWT_*` |
| Java | 17 | Maven | `./mvnw` (no system install) |

When in doubt, read `conddo-api/.../RlsIsolationTest.java` and `V2__rls.sql` — they
encode the isolation contract precisely.

---

## 11. Dashboard Module — Page & Functionality Audit + API Contract

This is the **frontend → backend contract for the tenant dashboard**. The dashboard
is the authenticated app a business owner/staff uses day-to-day. The frontend
(Next.js, separate repo `conddo-app`) is being built screen-by-screen; several
screens are **already built against hardcoded demo data** and now need real APIs.
This section audits **every page + functionality** and lists the **endpoints to
build**. Build these per module; nothing here breaks the §3 RLS contract.

### 11.0 Conventions for everything in this section
- **Base + envelope:** all endpoints are `/api/v1/...` and return the standard
  `ApiResponse` envelope (§6). Lists return `meta` (`page`, `size`, `total`).
- **Tenancy:** every endpoint is **tenant-scoped via the JWT `tenant_id` claim**
  (RLS, §3) unless explicitly marked **PUBLIC**. New tenant-scoped tables follow
  the §3 checklist. No manual `WHERE tenant_id`.
- **Roles:** `TENANT_ADMIN` + `STAFF` may read; writes default to `TENANT_ADMIN`
  unless noted. Annotate with `@PreAuthorize` (§6).
- **List params (standard):** `?search=&page=&size=&sort=` plus per-module filters.
- **Status legend:** ✅ page built (frontend) · ⬜ page not built yet ·
  🔌 endpoint already exists · 🆕 endpoint to build.
- **Response shapes:** the frontend's expected field names live in the demo data
  in the other repo — match them to avoid a mapping layer:
  `conddo-app/lib/demo/customers.ts`, `conddo-app/lib/demo/orders.ts`, and inline
  page data in `conddo-app/app/<route>/page.tsx`.
- **Reference data (build early — drives vertical-aware behaviour):**
  `GET /api/v1/verticals/{id}/config` 🆕 → the vertical's order-pipeline stages,
  measurement fields, and website sections. The dashboard's order stages,
  measurement labels, etc. are **vertical-specific**, not hardcoded.
  - 📋 **Platform Architecture v1.0 — full §1–20** captured in `ARCHITECTURE.md` (this
    repo; canonical master = `conddo_architecture.md` at the workspace root): module/
    capability-tool model, Module Registry/Factory, event bus, YAML vertical defs, RLS,
    gateway, **manifest-driven frontend** (`GET /api/v1/registry/manifests?modules=…`),
    Maven layout, Docker/infra, and the mandatory agent rules (§20). 7-vertical × plan-tier
    tool matrix in `VERTICALS.md`. Where v1.0 and this §11 REST contract disagree,
    **ARCHITECTURE.md wins**; the live backend currently implements an earlier subset
    (auth/me/customers/orders/dashboard-summary/verticals-config).
- **Shared cross-cutting endpoints** (used by many pages) are in §11.12.

---

### 11.1 Home / Dashboard  — `/dashboard` ✅
**Functionality:** greeting; setup-progress banner ("2 of 6 steps"); 4 KPI stat
cards (revenue today, pending orders, new customers, low-stock) each with a delta;
Recent Orders table; Website Status card; Today's Bookings; "New Order" CTA.

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET | `/api/v1/dashboard/summary` | One call returning all KPI cards `{revenueToday, pendingOrders, newCustomers, lowStockItems}` each `{value, delta, tone}`. |
|🆕| GET | `/api/v1/dashboard/setup-checklist` | `{steps:[{key,label,done}], completed, total}` for the setup banner. |
|🆕| POST | `/api/v1/dashboard/setup-checklist/{key}/dismiss` | Mark a setup step done/dismissed. |
| | GET | `/api/v1/orders?sort=recent&size=4` | Recent Orders widget (reuses §11.4). |
| | GET | `/api/v1/bookings?date=today` | Today's Bookings widget (reuses §11.5). |
| | GET | `/api/v1/website/status` | Website Status widget (reuses §11.2). |

### 11.2 Website  — `/website` ⬜
**Functionality:** live/in-progress status, subdomain + custom domain, traffic
(visits/enquiries), configured sections (read-only — the site is built in Conddo
Studio §8), request edits, connect custom domain (PRO). Tenant side is mostly
**read + request-changes**; the actual build lives in §8.

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET | `/api/v1/website` | Site config: `{subdomain, customDomain, status, publishedAt}`. |
|🆕| GET | `/api/v1/website/status` | `{state:'live'|'in_progress', domain, visitsToday, enquiries}`. |
|🆕| GET | `/api/v1/website/sections` | Configured sections + content (read-only snapshot from Studio). |
|🆕| POST | `/api/v1/website/change-requests` | Owner requests an edit → creates a Studio job/revision. |
|🆕| POST | `/api/v1/website/domain` | Connect a custom domain (PRO/plan-gated). |
|🆕| GET | `/api/v1/website/analytics?range=` | Visits, enquiries, top pages over a range. |

### 11.3 Customers (CRM)  — `/customers` ✅ · `/customers/{id}` ✅
**Functionality:** list with filter tabs (All / New this month / High value /
Inactive), search, segments, pagination, multi-select + **bulk** (SMS / email /
add tag / export), add customer, import CSV; **profile**: contact, tag, member-since,
totals (spent / orders / AOV), internal notes, **measurement profile**, order
history, payment history, send SMS/email.

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🔌| GET | `/api/v1/customers?search=&filter=&segment=&page=&size=` | **Extend existing** with filters/search/pagination + `meta`. Row fields: `{id,name,initials,phone,email,totalSpent,orders,lastActive,tag}`. |
|🔌| POST | `/api/v1/customers` | Create (exists). |
|🆕| GET | `/api/v1/customers/{id}` | Profile incl. `totalSpent, orders, avgOrderValue, tag, memberSince`. |
|🆕| PATCH | `/api/v1/customers/{id}` | Update contact/tag. |
|🆕| DELETE | `/api/v1/customers/{id}` | Remove. |
|🆕| GET/PUT | `/api/v1/customers/{id}/notes` | Internal notes (free text). |
|🆕| GET/PUT | `/api/v1/customers/{id}/measurements` | Measurement profile (fields from vertical config). |
|🆕| GET | `/api/v1/customers/{id}/orders` | Order history for the profile. |
|🆕| GET | `/api/v1/customers/{id}/payments` | Payment history for the profile. |
|🆕| POST / DELETE | `/api/v1/customers/{id}/tags` | Add / remove a tag (VIP, New, Lead…). |
|🆕| POST | `/api/v1/customers/import` | CSV import (multipart) → returns created/failed counts. |
|🆕| GET | `/api/v1/customers/export?filter=` | CSV export of the filtered set. |
|🆕| GET | `/api/v1/customers/segments` | Segment definitions + member counts. |
|🆕| POST | `/api/v1/customers/bulk/{action}` | `action ∈ {sms,email,tag,export}`; body `{customerIds:[], payload}`. |
|🆕| POST | `/api/v1/customers/{id}/messages` | Send a single SMS/email (via Notifications §5). |

### 11.4 Orders  — `/orders` (Kanban) ✅ · `/orders/{id}` ✅
**Functionality:** Kanban pipeline grouped by **stage** with per-column counts;
filter (All/Today/This week/Overdue), search; **OVERDUE/URGENT** flags; New Order;
add/rename stage; drag card → change stage. **Detail:** stage stepper + "mark next
stage", order items table, billing summary (total/deposit/balance), record payment,
send reminder, measurements, internal notes, activity log, customer link.
**Stages are vertical-specific** (Fashion default: Received → Measurement Taken →
Fabric Sourced → In Production → Ready for Fitting → Delivered) — load from vertical
config, store per-tenant overrides.

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET | `/api/v1/orders/board?filter=&search=` | Kanban: `{stages:[{name,count,orders:[card]}]}`. Card: `{id,customer,service,amount,date,initials,stage,flag}`. |
|🆕| GET | `/api/v1/orders?filter=&search=&stage=&page=&size=` | Flat list (recent-orders widget, list view). |
|🆕| POST | `/api/v1/orders` | Create order (customer, items, stage, dueDate). |
|🆕| GET | `/api/v1/orders/{id}` | Detail: items, billing `{total,deposit,balance}`, measurements, customer, flag. |
|🆕| PATCH | `/api/v1/orders/{id}` | Update dueDate, flag, fields. |
|🆕| POST | `/api/v1/orders/{id}/transition` | Move to a stage; appends an activity-log event `{from,to,by,at}`. |
|🆕| GET / POST / PATCH / DELETE | `/api/v1/orders/stages` | Manage the tenant's pipeline stages. |
|🆕| GET / POST / PATCH / DELETE | `/api/v1/orders/{id}/items` | Order line items `{description,qty,unitPrice}`. |
|🆕| GET / POST | `/api/v1/orders/{id}/payments` | List / record payments against the order. |
|🆕| POST | `/api/v1/orders/{id}/reminders` | Send a payment/pickup reminder. |
|🆕| GET | `/api/v1/orders/{id}/activity` | Activity log (transitions, messages, payments). |
|🆕| PUT | `/api/v1/orders/{id}/measurements` | Per-order measurements snapshot. |

### 11.5 Bookings  — `/bookings` ✅
**Functionality:** Day/Week/Month calendar of appointments; create booking; week
grid with positioned events; "upcoming this week"; **availability settings**
(working hours per day), **booking duration**, **buffer time**; **shareable booking
link** (clients self-book) + copy; weekly performance (count + projected revenue).

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET | `/api/v1/bookings?from=&to=&view=` | Events in range: `{id,customer,service,start,end,mode,status}`. |
|🆕| POST | `/api/v1/bookings` | Create a booking. |
|🆕| GET / PATCH / DELETE | `/api/v1/bookings/{id}` | Read / reschedule / cancel. |
|🆕| GET | `/api/v1/bookings/upcoming` | Upcoming-this-week list. |
|🆕| GET / PUT | `/api/v1/bookings/availability` | Working hours per weekday, duration, buffer. |
|🆕| GET / POST | `/api/v1/bookings/link` | Shareable link config; POST regenerates the slug. |
|🆕| GET | `/api/v1/bookings/performance?range=week` | `{bookingsThisWeek, revenueProjected}`. |
|🆕| GET | `/api/v1/public/book/{businessSlug}` | **PUBLIC** client-facing availability for self-booking. Rate-limited. |
|🆕| POST | `/api/v1/public/book/{businessSlug}` | **PUBLIC** create a booking request. Rate-limited; bot-protected. |

### 11.6 Inventory  — `/inventory` ⬜
**Functionality:** products/services list, stock levels, **low-stock alerts**
(feeds the dashboard KPI), categories, add/edit product, adjust stock, SKUs/variants.

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET | `/api/v1/inventory/products?search=&category=&lowStock=&page=&size=` | List. |
|🆕| POST / GET / PATCH / DELETE | `/api/v1/inventory/products[/{id}]` | CRUD product. |
|🆕| POST | `/api/v1/inventory/products/{id}/adjust` | Stock adjustment `{delta, reason}`. |
|🆕| GET | `/api/v1/inventory/low-stock` | Items at/below reorder threshold (dashboard KPI source). |
|🆕| GET / POST | `/api/v1/inventory/categories` | Manage categories. |

### 11.7 Payments  — `/payments` ✅
**Functionality:** KPI cards (this month / outstanding / paid invoices / overdue);
filter (All/Received/Outstanding/Overdue) + date range; transactions table; create
invoice; payment link; export CSV; **outstanding-by-customer** with "send reminder";
download receipt. **Depends on Billing/Paystack (§7).**

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET | `/api/v1/payments/summary?range=` | KPI cards: `{thisMonth, outstanding, paidInvoices, overdue}`. |
|🆕| GET | `/api/v1/payments/transactions?filter=&from=&to=&page=&size=` | Txn rows: `{date,customer,description,amount,method,status}`. |
|🆕| GET | `/api/v1/payments/outstanding` | Grouped by customer: `{name,note,amount,tone}` + reminder target. |
|🆕| POST | `/api/v1/payments/reminders` | Send reminder(s) `{customerId|invoiceId}`. |
|🆕| GET / POST / PATCH | `/api/v1/invoices[/{id}]` | Create / read / update invoices. |
|🆕| GET | `/api/v1/invoices/{id}/receipt` | Receipt PDF download. |
|🆕| POST | `/api/v1/payments/links` | Create a Paystack payment link. |
|🆕| GET | `/api/v1/payments/export?filter=&from=&to=` | CSV export. |
|🆕| POST | `/api/v1/webhooks/paystack` | **PUBLIC + signature-verified** payment confirmations → reconcile invoices. |

### 11.8 Marketing  — `/marketing` ✅ · `/marketing/social` ✅ · tabs Ads/Email/SMS/Leads ⬜
**Functionality (Overview):** KPI cards (social reach, post engagement, new leads,
email open rate, ad spend); Upcoming Posts; Active Campaigns (email + SMS) with
stats; **Leads funnel** (New→Contacted→Interested→Converted) + conversion rate;
Quick Actions. **(Social Calendar):** month grid of scheduled posts by platform,
platform filter, schedule/edit/delete/post-now. **Future tabs:** Ads (Meta), Email,
SMS, Leads pipeline. Ties into §5 Notifications and §9 AI item 6 (copy assistant).

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET | `/api/v1/marketing/summary` | Overview KPI cards + deltas. |
|🆕| GET | `/api/v1/marketing/posts?from=&to=&platform=` | Social calendar + upcoming posts. Post: `{id,title,platform,scheduledAt,status}`. |
|🆕| POST / GET / PATCH / DELETE | `/api/v1/marketing/posts[/{id}]` | Schedule/edit/delete a post `{platforms[],content,mediaIds,scheduledAt}`. |
|🆕| POST | `/api/v1/marketing/posts/{id}/publish` | "Post now". |
|🆕| GET / POST | `/api/v1/marketing/campaigns?type=email\|sms&status=` | List / create campaigns. |
|🆕| GET | `/api/v1/marketing/campaigns/{id}` | Stats: `{sent,openRate,delivered,clickRate,status}`. |
|🆕| GET | `/api/v1/marketing/leads/funnel` | Funnel counts per stage + conversion rate. |
|🆕| GET / PATCH | `/api/v1/marketing/leads[/{id}]` | Leads list / move stage. |
|🆕| GET / POST / DELETE | `/api/v1/marketing/connections` | Connected social accounts (IG/FB/X/LinkedIn OAuth). |
|🆕| GET / POST | `/api/v1/marketing/ads` | Meta ad campaigns (later — see §9 open question on Meta rate limits). |
|🆕| POST | `/api/v1/marketing/assistant/generate` | AI copy (caption/email/SMS/ad) — **§9 AI item 6**; human-in-the-loop. |

### 11.9 Analytics  — `/analytics` ⬜
**Functionality:** business analytics — revenue trends, orders over time, customer
growth/retention, top services/products, website traffic, conversion; date range +
export. Largely **read-only aggregation** over orders/payments/customers/website.

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET | `/api/v1/analytics/overview?range=` | Headline metrics. |
|🆕| GET | `/api/v1/analytics/revenue?range=&granularity=` | Revenue time series. |
|🆕| GET | `/api/v1/analytics/orders?range=` | Orders/volume time series. |
|🆕| GET | `/api/v1/analytics/customers?range=` | New vs returning, retention. |
|🆕| GET | `/api/v1/analytics/top?metric=services\|products\|customers` | Leaderboards. |
|🆕| GET | `/api/v1/analytics/traffic?range=` | Website traffic/conversion. |
|🆕| GET | `/api/v1/analytics/export?report=&range=` | CSV/PDF export. |

### 11.10 Staff  — `/staff` ⬜
**Functionality:** staff list, invite staff (email + role), roles/permissions,
edit role, deactivate, resend invite, per-user activity. **Maps to the existing
`users` table** (RLS-scoped, `Role` = TENANT_ADMIN/STAFF). `TENANT_ADMIN` only.

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET | `/api/v1/staff` | List tenant users (TENANT_ADMIN/STAFF) `{name,email,role,status,lastActive}`. |
|🆕| POST | `/api/v1/staff/invite` | Invite `{email,role}` → sends invite (Notifications §5). |
|🆕| GET / PATCH | `/api/v1/staff/{id}` | Read / change role or status (activate/deactivate). |
|🆕| POST | `/api/v1/staff/{id}/resend-invite` | Resend the invite email. |
|🆕| GET | `/api/v1/staff/roles` | Role definitions + permission matrix. |
|🆕| GET | `/api/v1/staff/{id}/activity` | Recent actions (from `audit_log` §3). |

### 11.11 Settings  — `/settings` ✅ (Business Profile)
**Functionality (built):** Business Details (name, tagline, description, **industry
locked**, email, phone, **subdomain locked**, connect domain PRO), Branding (logo,
primary brand colour), Social Handles, Location, Business Hours.
**Sub-sections still to build (⬜):** Subscription & Billing, Notifications,
Connected Accounts, Staff & Permissions (→ §11.10), API Keys, Danger Zone.

| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET / PUT | `/api/v1/settings/business-profile` | Name, tagline, description, email, phone (industry + subdomain read-only). |
|🆕| PUT | `/api/v1/settings/branding` | `{primaryColor}` + logo via media upload (§11.12). |
|🆕| PUT | `/api/v1/settings/social-handles` | IG / X / Facebook / LinkedIn. |
|🆕| PUT | `/api/v1/settings/location` | Street, city, state. |
|🆕| GET / PUT | `/api/v1/settings/business-hours` | Per-weekday on/off + open/close times. |
|🆕| GET / PUT | `/api/v1/settings/notifications` | Notification channel preferences. |
|🆕| GET / POST / DELETE | `/api/v1/settings/api-keys` | List / create / revoke API keys. |
|🆕| GET | `/api/v1/billing/subscription` | Current plan, status, renewal, trial (→ §7). |
|🆕| POST | `/api/v1/billing/change-plan` | Upgrade/downgrade tier (→ §7 Paystack). |
|🆕| GET | `/api/v1/billing/invoices` | Subscription invoice history. |
|🆕| POST | `/api/v1/settings/danger/deactivate` | Deactivate tenant (confirmation-gated). |
|🆕| DELETE | `/api/v1/tenant` | Delete tenant (Danger Zone; hard-gated, owner/SUPER_ADMIN). |

### 11.12 Cross-cutting (shared by many pages)
| | Method | Endpoint | Purpose |
|---|---|---|---|
|🆕| GET / POST | `/api/v1/media` | Upload to MinIO (multipart) → `{mediaId,url}`. Used by branding, posts, products, order photos. |
|🆕| GET | `/api/v1/notifications?unread=` | Topbar bell feed. |
|🆕| POST | `/api/v1/notifications/{id}/read` · `/notifications/read-all` | Mark read. |
|🆕| GET | `/api/v1/search?q=` | Global topbar search across customers/orders/bookings. |
|🆕| GET | `/api/v1/verticals/{id}/config` | Vertical config: order stages, measurement fields, website sections (drives §11.3–11.5). |
|🆕| GET | `/api/v1/me` | Current user + tenant summary (for the sidebar identity: business name, user name/role, initials, subdomain). |

### 11.13 Suggested backend build order
Dependencies first, then highest-leverage modules:
1. **Prereqs:** §7 items 5 (Notifications) + 7 (Billing) unblock Customers-messaging,
   Payments, Staff-invite, Marketing.
2. **`/me` + `/verticals/{id}/config`** (§11.12) — the dashboard shell and every
   vertical-aware screen need these first.
3. **Customers** (§11.3) — extend the existing CRUD; most other modules reference a customer.
4. **Orders** (§11.4) — board + detail + payments-on-order; core operational loop.
5. **Dashboard summary** (§11.1) — aggregates Orders/Customers/Bookings/Website.
6. **Bookings** (§11.5), **Payments/Invoices** (§11.7), **Inventory** (§11.6).
7. **Settings** (§11.11) + **Staff** (§11.10).
8. **Marketing** (§11.8), **Analytics** (§11.9), **Website** (§11.2) — the last two
   lean on Conddo Studio (§8) and aggregation.

> **Frontend status note (updated):** **every dashboard screen in §11.1–11.12 now
> exists** in `conddo-app`, including a typed **API client that already consumes
> these exact endpoints** (`conddo-app/lib/api/client.ts` → `/api/v1/...`, standard
> envelope, Bearer token, `meta` pagination). Two groups:
> - **Newly built screens are fully API-driven** (Website, Inventory, Analytics,
>   Staff, all Settings sub-tabs, all Marketing tabs). They call the endpoints via
>   `useApiQuery` and render loading → error → **empty** → data states. With no
>   backend wired (`NEXT_PUBLIC_API_URL` unset) they show designed empty states; the
>   moment the endpoints return data in the documented shape, they populate — **no
>   adapter layer**.
> - **Earlier screens still render demo data** (`conddo-app/lib/demo/*`,
>   Dashboard/Customers/Orders/Bookings/Payments/Marketing-overview/Social). These
>   get migrated onto the same client once their endpoints land; their demo files
>   already document the exact field names you should return.
>
> **So: match the field names in §11 + the demo files, return the standard envelope,
> and screens light up with zero frontend changes.** Auth: the client sends
> `Authorization: Bearer <token>` from `localStorage` (`conddo_access_token`); a
> login screen will populate it against §7.1.

### 11.14 — Live integration findings & decisions (2026-05-24, from the frontend)
The frontend now consumes the live API end-to-end. Recording what we found so the
backend can align. **Architecture v1.0 is captured in this repo** (`ARCHITECTURE.md` §1–20,
`VERTICALS.md`) — the canonical master is `conddo_architecture.md` at the workspace root.

- **Envelope:** confirmed `{success, data, meta?, error{code,message,details[]}}` (NOT `ok`).
  Frontend handles this; keep it stable.
- **JWT is missing claims:** access token only carries `tenant_id, sub, role, iss, exp, iat`.
  **Add `activeModules`, `vertical`, `plan`** (§4.4) — the manifest-driven frontend (§16) and
  module-access gate (§10) need them. **`/api/v1/registry/manifests` is still 500** — until it
  + the claims ship, the frontend uses a static-nav fallback (ready to flip instantly).
- **Live & wired:** `/auth/*`, signup, `/me`, `/customers` (+`/{id}`), `/orders` (+`/board`,
  `/{id}`), `/dashboard/summary`, `/bookings/*`, `/verticals/{id}/config`, `/inventory/products`,
  `/staff`, `/notifications`, `/settings/business-profile`, `/analytics/overview`.
- **Still 500 (frontend shows clean empty states until live):** `/payments/*`, `/marketing/*`,
  `/website`, `/registry/manifests`, `/customers/{id}/orders`, `/customers/{id}/payments`.
- **Shapes that differed from §11 — frontend adapted; treat THESE as the contract (or tell us to realign):**
  - `/notifications` → `{items:[], unread:0}` (not a bare array).
  - `/analytics/overview` → flat `{revenue, orders, newCustomers, avgOrderValue}` (not `{kpis,...,series}`).
  - `/staff` → rows may have `name: null` (frontend falls back to email); `lastActive` is ISO.
  - `/settings/business-profile` → uses `name` (not `businessName`); `industry`, `subdomain`, nullable fields.
  - `/customers/{id}` → `{name,email,phone,tag,tags[],memberSince(ISO),totalSpent,orders,avgOrderValue,lastActive,notes,measurements}`.
  - `/orders/{id}` → `{reference,service,stage,stages[],flag,dueDate,orderedAt,amount,billing{total,deposit,balance},customer,items[],payments[],measurements,notes,activity[{id,type,title,detail,actor,at}]}`. 👍 great shape, fully wired.
- **`/customers/{id}` & `/orders/{id}` validate id as UUID** (400 on non-UUID) — list/board/detail
  all use the UUID `id`; orders also return a display `reference` (ORD-xxxx).

#### Auth / OTP / Email / SMS — decision
- **Current signup is email + password (no OTP)** via `POST /api/v1/tenants` (then `/auth/login`
  with `email`+`password`+`tenantSlug`). This works; logins succeed (first request after idle is a
  ~30–60s Render cold start — likely why "can't log in / create account" *feels* broken in dev).
- **Provider split (supersedes the single-Brevo note in §1/§18):** **Resend = email**, **Brevo = SMS**.
- **Code wiring done (this repo):** `ResendEmailSender` already existed; **`BrevoSmsSender` added**
  (`conddo-core/notify`, activates on `conddo.notifications.sms.provider=brevo`). `application.yml`
  SMS block now reads `CONDDO_BREVO_API_KEY` / `CONDDO_SMS_BASE_URL` (default `https://api.brevo.com`).
  **Signup OTP now delivers by EMAIL (Resend, free)** — `RegistrationService.start`/`resend` call
  `NotificationService.sendOtpEmail(email, code)` (was `sendOtp`/SMS). SMS via Brevo stays available
  for funded use (order/booking notifications). So **only Resend env is needed for OTP**.
- **Registration (OTP) flow is live at `/auth/register/{start,verify,resend,complete}`** — and
  `complete()` **issues a JWT WITH `vertical`, `plan`, and `activeModules`** (via `toolMatrix.resolve`).
  ⭐ The frontend currently signs up via the simpler `POST /api/v1/tenants` (no OTP, JWT lacks claims).
  **Adopting `/auth/register/*` on the frontend gives free email-OTP AND the JWT claims that unblock
  the manifest-driven shell (§16)** — no separate JWT change needed. (The plain `/auth/login` token
  still needs the claims added for returning users.)
- **To activate — set these env vars on the deployed (Render) backend, then redeploy:**
  - Email (Resend): `CONDDO_EMAIL_PROVIDER=resend`, `CONDDO_RESEND_API_KEY=<key>`,
    `CONDDO_EMAIL_FROM=<verified sender>` (Resend only sends from a verified domain/sender).
  - SMS (Brevo): `CONDDO_SMS_PROVIDER=brevo`, `CONDDO_BREVO_API_KEY=<key>`, `CONDDO_SMS_SENDER_ID=Conddo`.
  - ⚠️ **Brevo free tier has NO SMS credits** (free = email only). OTP-by-SMS won't deliver until SMS
    credits + a registered sender are added. **Free path: deliver OTP by email** (Resend) — small change
    to `NotificationService.sendOtp` (needs the email at OTP time). Flag if you want that switch.
  - Keys live ONLY in Render env, never committed. The plaintext keys shared in chat should be **rotated**.
- **Action for backend:** (1) add the missing JWT claims; (2) implement `/registry/manifests`;
  (3) ship `/payments/*`, `/marketing/*`, `/website`; (4) set the Resend/Brevo env vars above + redeploy
  (code is wired); (5) build `/customers/{id}/orders` + `/customers/{id}/payments`.
- **Test data on the live tenant `conddo-demo-1779614690` (safe to delete):** customer
  `05117bd9-d6eb-40ab-ac99-3e1fe7be96ee` (Test Buyer), order `809bbbfe-…` (ORD-2894).
