# Conddo.io — Local Infrastructure (Phase 0)

Local development stack: **PostgreSQL 16**, **Redis 7**, **MinIO**. Managed with
Docker Compose.

## Prerequisites

- Docker Desktop running (whale icon solid / "Engine running").

## First run

```bash
cd infra
cp .env.example .env          # adjust if you like (defaults are fine for dev)
docker compose up -d
docker compose ps             # all services should be "healthy"
```

On first start the Postgres container:
1. Creates the database and the **owner** role (`conddo_owner`).
2. Runs `postgres/init/01-app-user.sh`, which creates the **runtime** role
   (`app_user`). This role is *not* a table owner, so Row Level Security
   applies to it — that's how tenant isolation is enforced.

The schema itself (tables + RLS policies) is applied by **Flyway** when the
Spring Boot app boots — see the `conddo-api` module in this repo's root.

## Service endpoints

| Service    | URL / Port                    | Credentials (dev)              |
|------------|-------------------------------|--------------------------------|
| PostgreSQL | `localhost:5432`              | `app_user` / `app_password`    |
| Redis      | `localhost:6379`              | —                              |
| MinIO API  | `http://localhost:9000`       | `conddo` / `conddo_secret_…`   |
| MinIO UI   | `http://localhost:9001`       | `conddo` / `conddo_secret_…`   |

## Common commands

```bash
docker compose up -d        # start
docker compose ps           # status
docker compose logs -f postgres
docker compose down         # stop (keeps data volumes)
docker compose down -v      # stop AND wipe data (fresh schema next boot)
```

> ⚠️ The two Postgres roles matter. `conddo_owner` owns the tables and runs
> migrations; `app_user` is what the application connects as at runtime so that
> RLS is enforced. Never point the app at `conddo_owner` — owners bypass RLS.
