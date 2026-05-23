# Conddo.io — Backend (Phase 0 / Core Platform)

Spring Boot 3 multi-module backend. Phase 0 establishes the spine everything
else hangs off: **multi-tenancy enforced by PostgreSQL Row Level Security**.

```
backend/                  (this repo — conddo-backend)
  pom.xml                 parent (multi-module)
  conddo-core/            Core Platform — tenancy, persistence, domain, services
  conddo-api/             Spring Boot app — REST API, config, Flyway migrations
  infra/                  Docker Compose dev stack (Postgres 16, Redis 7, MinIO)
```

## How tenant isolation works

1. Every tenant-scoped table has `tenant_id` and an RLS policy:
   `tenant_id = current_setting('app.tenant_id', true)::uuid`.
2. The app connects to Postgres as **`app_user`** — a *non-owner* role, so RLS
   is enforced and cannot be bypassed from application code.
3. Per request, `TenantFilter` resolves the tenant (Phase 0: `X-Tenant-Id`
   header) onto `TenantContext`.
4. Per transaction, `TenantSession.bind()` runs
   `SELECT set_config('app.tenant_id', '<uuid>', true)` so RLS scopes every
   query. The setting is transaction-local — it never leaks across the pool.
5. Migrations run as the **owner** role (`conddo_owner`); the app runs as
   `app_user`. Two roles, on purpose — owners bypass RLS.

If the tenant context is missing, `current_setting(..., true)` is NULL and
queries match **no rows** — isolation fails closed.

## Prerequisites

- JDK 17+
- The infra stack running: `cd infra && docker compose up -d` (Postgres/Redis/MinIO live in this repo)

## Run

```bash
# from the repo root
./mvnw spring-boot:run -pl conddo-api          # starts on :8080, Flyway migrates on boot
```

Build / test:

```bash
./mvnw clean verify                            # compile + run tests (tests need Docker)
```

## Try the isolation demo

```bash
# Create two tenants
A=$(curl -s -XPOST localhost:8080/api/v1/tenants -H 'Content-Type: application/json' \
     -d '{"name":"Amaka Styles","slug":"amaka-styles","verticalId":"fashion"}' | jq -r .data.id)
B=$(curl -s -XPOST localhost:8080/api/v1/tenants -H 'Content-Type: application/json' \
     -d '{"name":"Wellspring Pharmacy","slug":"wellspring","verticalId":"pharmacy"}' | jq -r .data.id)

# Add a customer to each (note the X-Tenant-Id header)
curl -s -XPOST localhost:8080/api/v1/customers -H "X-Tenant-Id: $A" \
     -H 'Content-Type: application/json' -d '{"fullName":"Alice"}'
curl -s -XPOST localhost:8080/api/v1/customers -H "X-Tenant-Id: $B" \
     -H 'Content-Type: application/json' -d '{"fullName":"Bob"}'

# Each tenant sees ONLY its own customer — enforced by Postgres, not app code
curl -s localhost:8080/api/v1/customers -H "X-Tenant-Id: $A"   # -> Alice
curl -s localhost:8080/api/v1/customers -H "X-Tenant-Id: $B"   # -> Bob
```

Health: `curl localhost:8080/actuator/health`

## What's next (Phase 1)

Auth (RSA-256 JWT + refresh-token rotation, PRD §6.2/§12.1), the audit log
writer, subdomain → tenant resolution via Redis, the notifications engine, and
billing. The tenancy + RLS foundation here is what they all build on.
