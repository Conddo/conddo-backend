-- Minimal mirror of conddo-api's V1 platform schema (Studio reads these via
-- the PlatformTenant / PlatformUser entities — §23.2). The Studio test
-- container only runs Studio's own migrations, so this seeds the columns
-- Hibernate validates at boot. Real platform deploys (conddo-api's Flyway)
-- own the production schema.

CREATE TABLE IF NOT EXISTS public.tenants (
    id                   UUID PRIMARY KEY,
    name                 TEXT NOT NULL,
    slug                 TEXT NOT NULL UNIQUE,
    vertical_id          TEXT,
    plan_id              TEXT,
    custom_domain        TEXT,
    status               TEXT NOT NULL DEFAULT 'ACTIVE',
    website_status       TEXT NOT NULL DEFAULT 'NOT_STARTED',
    website_published_at TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.users (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    email          TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    full_name      TEXT,
    role           TEXT NOT NULL,
    phone          TEXT,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    google_sub     TEXT
);
