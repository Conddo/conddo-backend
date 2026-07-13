# Conddo.io — Architecture (current state)

> **Rule for this document:** describe what is actually in the code today. If you find something written here that isn't in the code, delete the sentence — don't leave aspirational architecture as instructions to the next person.

## What Conddo is

A multi-tenant Nigerian SaaS that gives SMEs a website, storefront, POS, marketing, and vertical-specific tools (pharmacy, fashion, real estate, restaurant, retail, professional services, music studio) in one subscription.

Two customer-visible surfaces:
- `getconddo.com` — the tenant dashboard (`conddo-app`, Next.js)
- `studio.getconddo.com` — platform admin (same `conddo-app` deploy, rewritten to `/admin/*` by middleware)

## Deployment shape

**Modular monolith with two backend sidecars, one Postgres, one Redis.**

```
                             ┌──────────────────────┐
                             │  Cloudflare wildcard │
                             │  *.getconddo.com     │
                             └──────────┬───────────┘
                                        │
                     ┌──────────────────┴──────────────────┐
                     ▼                                     ▼
       ┌──────────────────────┐              ┌──────────────────────┐
       │  Vercel              │              │  EC2 + Caddy         │
       │  conddo-app          │              │  api.getconddo.com   │
       │  (Next.js 14, App)   │              │  (wildcard TLS)      │
       └──────────┬───────────┘              └──────────┬───────────┘
                  │ HTTPS                               │
                  └──────────────────┬──────────────────┘
                                     │
                                     ▼
                 ┌──────────────────────────────────────┐
                 │  conddo-api  (Spring Boot 3.3, JVM)  │
                 │  - Auth + JWT                        │
                 │  - Every business service            │
                 │  - Admin surface                     │
                 │  - Public tenant-site catalog        │
                 └────────┬─────────────────────────────┘
                          │
       ┌──────────────────┼──────────────────┐
       ▼                  ▼                  ▼
┌──────────────┐   ┌───────────────┐   ┌──────────────┐
│  Postgres 16 │   │  Redis 7      │   │  MinIO       │
│  RLS per     │   │  cache + bus  │   │  S3-compat   │
│  tenant      │   │  + sessions   │   │  assets      │
└──────────────┘   └───────────────┘   └──────────────┘
       ▲                  ▲
       │                  │
┌──────┴──────────┐   ┌───┴──────────────┐
│ conddo-payments │   │ conddo-studio    │
│ (Spring Boot)   │   │ (Spring Boot)    │
│ RoutePay        │   │ website / ads /  │
│ webhooks        │   │ design jobs      │
└─────────────────┘   └──────────────────┘
```

Three Maven modules build to three JARs. All three connect to the same Postgres (own tables, no joins across service boundaries). `conddo-api` is the primary; the sidecars run alongside on the same EC2 host via Docker Compose.

## Multi-tenancy

- Every domain table has `tenant_id UUID NOT NULL`.
- Every row is under a Postgres RLS policy:
  ```sql
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
         OR current_setting('app.cross_tenant', true) = 'true')
  ```
- A servlet filter reads the JWT's `tenantId` claim and stores it in a ThreadLocal (`TenantContext`).
- Service methods bind the GUC for the transaction via `@TenantScoped` (aspect) OR by calling `tenantSession.bind()` inline (older sites).
- `@TenantScoped(crossTenant = true)` widens RLS for admin reads across tenants. Aspect clears the GUC in a `finally`.

**Non-tenant tables:**
- `staff_users` — internal Handel Cores staff (SUPER_ADMIN). Global unique email.
- `tenants` itself.

## Auth

- **Tenant users** (`users` table, tenant-scoped): `/auth/login` with `tenantSlug + email + password`.
- **Staff** (`staff_users`, global): `/auth/staff/login` with `email + password`. Feeds `studio.getconddo.com`.
- JWT: RS256 signed. Claims: `sub`, `tenantId` (nullable for staff), `role`, `staffRole`, `vertical`, `plan`, `activeModules` (see below).
- Refresh: httpOnly cookie `conddo_rt`, rotated on use, family-revocation on reuse.
- Google Sign-In: verified server-side against Google's JWKS.

## Module registry — how the sidebar is built

The tenant's active tools are computed by `ModuleResolver`:

```
active = VerticalToolMatrix[vertical × plan]  ∪ opt-ins  −  opt-outs
```

- **`VerticalToolMatrix`** — loads `classpath:verticals/*.yml`. Each vertical file lists starter/business/pro tools. Adding a vertical = dropping a YAML file.
- **`tenant_module_overrides`** — per-tenant opt-in / opt-out rows.
- Frontend calls `GET /api/v1/tenant/modules/active` on mount → the resolver returns the current effective set. No JWT reissue needed for opt-in changes to take effect.
- `ManifestCatalogue` maps tool ids → sidebar sections (label, icon, path, order).
- Sidebar renders `[Home] + manifests + [Settings]` and splices in a few FE-only pages via `VERTICAL_EXTRAS` in `useAppNav.ts`.

## Event bus

`DomainEventBus` (in `io.conddo.core.events`). Every domain event implements the `DomainEvent` marker.

- `bus.publish(event)` fires locally via Spring's `ApplicationEventPublisher` (all in-JVM `@EventListener` methods run) AND relays to Redis pub/sub channel `conddo.domain.events`.
- Sibling pods subscribe via `RedisDomainEventSubscriber`, deserialise, and call `publishLocalOnly` — the receive path never re-relays (loop-prevented).
- **Rule for listeners:** be idempotent, or enqueue a job. Every listener runs on every pod. If your listener sends SMS or charges a card, it must dedupe by event id — otherwise it fires N times on an N-pod cluster.

Present in tree:
- `OrderCreatedEvent`, `OrderStageChangedEvent`, `BookingCreatedEvent`, `DiscountPendingApprovalEvent`, `TenantActivatedEvent`.

Missing Redis silently no-ops the relay so single-pod deployments (tests, local dev) still work.

## Pricing (V67, Pricing v2)

Five customer-facing plans. Prices in Naira (kobo in the DB).

| Plan | Monthly | Quarterly (15% off) | Yearly (30% off) | Credits/mo |
|---|---|---|---|---|
| Free    | ₦0      | ₦0       | ₦0       | 100 |
| Student | ₦3,000  | ₦7,650   | ₦25,200  | 300 |
| Starter | ₦5,000  | ₦12,750  | ₦42,000  | 500 |
| Growth  | ₦15,000 | ₦38,250  | ₦126,000 | 3,000 |
| Pro     | ₦30,000 | ₦76,500  | ₦252,000 | 10,000 |

**Internal tier mapping** — `VerticalToolMatrix` still keys on the three tier names `starter / business / pro`. Product-tier resolution:

- `free`, `student`, `starter` → `starter` (same tool set; different credit budgets + price)
- `growth` → `business`
- `pro` → `pro`

Legacy pre-V67 names (`launcher`, `scaler`) are still accepted so JWTs minted before the migration keep working until they expire.

**Billing cycles** — `monthly / quarterly / yearly`. `BillingService.cycleDays()` is the single source of truth (30 / 90 / 365).

**Trial** — `TenantService.create()` and `provisionFromRegistration()` provision on `starter` for 14 days. On trial end without payment the tenant should downgrade to `free` (not yet automated).

## Payment providers

Two, deliberately split by surface — never mix in one flow:

- **Paystack** — all subscriptions (Conddo plan billing + program enrolments). `PaystackGateway` interface + `Http`/`Dormant` implementations in `conddo-core/paystack`. Webhook signature verified with the Paystack secret.
- **RoutePay** — one-off tenant → customer receipts (deposits, POS, walk-ins). Lives in the separate `conddo-payments` service. Webhook verification in `RoutePayWebhookVerifier`.

## AI

`AiGateway` interface. Two adapters:
- `HttpOpenRouterGateway` (`@Primary` in prod) — DeepSeek / Claude / Gemini via OpenRouter's OpenAI-compatible endpoint.
- `HttpAnthropicGateway` — direct Anthropic, kept for vision (pharmacy product assistant) until vision migrates to OpenRouter.

Per-action model routing (`AiModelSelector`): classifier + brief default to `deepseek/deepseek-chat-v3.1:free`; website/marketing/insight buckets keyed by `CreditActions.*`. Overrideable via env vars.

Every call goes through the credit + verified-email guard. Reservation → confirm|release semantics on the `tenant_credit_accounts` table.

## Frontend

Next.js 14 App Router, one deploy, one Vercel project.

- `app/` — 60+ route groups. Tenant surfaces at the root; admin at `/admin/*`; managed tenant sites rendered from `/sites/[host]`.
- `middleware.ts` — subdomain routing. `studio.getconddo.com → /admin/*`. Everything else that isn't apex or a dev host is treated as a tenant site.
- `lib/api/*` — 43 typed API clients, one per resource. Envelope unwrap in `lib/api/client.ts`.
- `hooks/useResource.ts` — React Query wrapper; new screens use this. Legacy `useApiQuery` still works.
- `hooks/useAppNav.ts` — reads live module list, renders sidebar. Falls back to `[Home, Settings]` if the live source is unreachable. **Never** falls back to a universal list.

## Data model highlights

- **Flyway V1..V66** — sequential migrations. RLS + policies added in V26 onwards. V66 is the credit-tables GRANT fix.
- **`tenants`** — the identity axis. `vertical_id`, `plan_id`, `booking_link_slug`, `custom_domain`.
- **`users`** — tenant-scoped auth. `google_sub` links Google identity.
- **`staff_users`** — non-tenant, SUPER_ADMIN lives here.
- **`orders` / `order_items` / `order_payments`** — the pipeline. Stage names come from `OrderStageService` (per-tenant overrides or vertical defaults).
- **`tenant_sites`** — public-website records (self-activation, QA approval).
- **`real_estate_properties` / `real_estate_deals` / `property_viewings` / `commission_entries`** — the real-estate vertical.
- **`tenant_credit_accounts` / `credit_transactions` / `credit_topups`** — credit ledger for AI + automation calls.

## Testing

- Unit tests in each Maven module.
- Integration tests use Testcontainers Postgres — the same schema Flyway applies to prod.
- CI: GitHub Actions. Two workflows — `CI` (compile + tests) and `Release · lean` (build + push image to GHCR). Deployment on EC2 is a Docker Compose pull + restart.

## Things this document deliberately does not claim

- We are not on Kubernetes.
- We are not microservices. See §"Deployment shape".
- We do not have per-tenant databases.
- We do not have distributed tracing.
- We do not have a service mesh, mTLS, or per-service auth.
- Every listener runs on every pod. There is no leader election.

If any of the above changes, update this section rather than adding a new "aspirational" one.
