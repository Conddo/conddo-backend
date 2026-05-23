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
