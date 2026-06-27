# Conddo AWS Migration — Prep & Inventory

*Companion to `conddo_infrastructure_deployment_doc.md` §1-§7 + §9. Working
inventory snapshot — fill in the bracketed values as you confirm them,
flip checkboxes as work lands. Not a final spec; treat it as the punch
list that turns "we'll migrate to AWS" into "here's what to actually
provision and deploy."*

---

## 0. Current state (Render — what we're moving off)

| Service | Where it runs today | Repo | Container | Notes |
|---|---|---|---|---|
| conddo-backend (Spring Boot API) | Render `conddo-backend` service | `conddo-backend` | Eclipse Temurin 17 (`Dockerfile`) | Free tier cold-starts; the primary pain |
| conddo-studio backend (Spring Boot, ops console API) | Render `conddo-studio` service | `conddo-backend/conddo-studio` | `Dockerfile.studio` | Same JVM stack, different bundle |
| conddo-payments | Render `conddo-payments` service | `conddo-backend/conddo-payments` | `Dockerfile.payments` | Paystack + Routepay handlers |
| Postgres (shared) | Render Managed PostgreSQL | — | — | RLS-enforced; both apps use it via two roles (owner + app_user) |
| Redis | Not yet provisioned | — | — | Health check disabled; planned but never wired |
| MinIO / Cloudinary | Cloudinary external | — | — | Media storage; one external account |
| conddo-app (Next.js FE) | Vercel | `conddo.io` | — | Stays on Vercel per `06-27` decision — not migrating |
| conddo-studio (Next.js FE) | Vercel | `conddo-studio` | — | Stays on Vercel — not migrating |

---

## 1. What's actually moving to AWS

In scope for this migration:

- [x] **conddo-backend** (Java Spring Boot)
- [x] **conddo-studio backend** (Java Spring Boot)
- [x] **conddo-payments** (Java Spring Boot)
- [x] **Postgres** (Render Managed → RDS or self-managed)
- [x] **Redis** (new — will be ElastiCache or self-managed)
- [x] **Two new FastAPI services** per `architecture_shift_ai_provisioning.md`:
  - `ai-provisioning` — Python/FastAPI
  - `workflow-engine` — Python/FastAPI
  - `website-generation` — Python/FastAPI (Phase 6, later)

Out of scope:
- conddo-app (FE) — Vercel
- conddo-studio (FE) — Vercel
- Cloudinary — external SaaS, no change

---

## 2. Service → AWS mapping (decisions still open per Infra Doc §3, §4)

| Concern | Option A | Option B | Pick by |
|---|---|---|---|
| Java app compute | **ECS Fargate** (preferred — no cold start once ≥1 task) | **EC2** (manage OS yourself; cheapest long term) | Decision in Infra Doc §3 |
| Python app compute | **ECS Fargate** (same cluster as Java, smaller tasks) | **Lambda** (only with provisioned concurrency) | Same call — Lambda is OK for AI services if traffic is bursty |
| Postgres | **RDS db.t4g.micro** (free tier 750 hrs/mo for 12 mo, automated backups) | **Self-managed on EC2** | Decision in Infra Doc §4 |
| Redis | **ElastiCache cache.t4g.micro** | **Self-managed on EC2** | Same call as Postgres |
| Container registry | **ECR** (private, integrates with ECS, free for first 500 MB) | — | Default ECR |
| Secrets | **AWS Secrets Manager** | Plain ECS task-definition env | Secrets Manager for prod, env for dev OK |
| Load balancer | **ALB** (Application LB, $16/mo + per-LCU) | NLB | ALB; SSL termination + path routing |
| DNS | **Route 53** | External (Cloudflare, etc.) | Route 53 keeps the AWS story uniform |
| TLS | **ACM** (free) | Let's Encrypt | ACM auto-renews with the ALB |
| IaC | **OpenTofu** (already specified) | Pulumi / CDK | OpenTofu per Infra Doc §2 |
| Logs / Metrics | **CloudWatch** | Datadog | CloudWatch free tier first |
| App errors | **Sentry** (already in use) | — | No change |
| LLM traces | **Opik** (already specified) | — | No change |

---

## 3. The migration inventory — every env var

Compiled from `backend/render.yaml`. **26 env vars** total on the main API service. Each needs to land on AWS via either Secrets Manager (sensitive) or task-definition env (non-sensitive).

### 3.1 Database (Postgres)

| Name | Sensitive? | Current source | AWS landing |
|---|---|---|---|
| `CONDDO_DB_URL` | yes | Render DB connection string | Secrets Manager → ECS task injects |
| `CONDDO_DB_OWNER` | yes | Render | Secrets Manager |
| `CONDDO_DB_OWNER_PASSWORD` | yes | Render | Secrets Manager |
| `CONDDO_DB_APP_USER` | no | `app_user` (from `db/bootstrap/create-app-user.sql`) | Task env var |
| `CONDDO_DB_APP_PASSWORD` | yes | Render | Secrets Manager |

### 3.2 JWT signing

| Name | Sensitive? | Current source | AWS landing |
|---|---|---|---|
| `CONDDO_JWT_PRIVATE_KEY` | yes | Render Secret File mount `/etc/secrets/jwt_private.pem` | Secrets Manager + ECS file mount, or use AWS KMS |
| `CONDDO_JWT_PUBLIC_KEY` | yes | Same mechanism | Same |
| `CONDDO_JWT_ISSUER` | no | `https://api.conddo.io` → **change to `https://api.getconddo.com`** | Task env |

### 3.3 Frontend integration

| Name | Sensitive? | Current value | New value after migration |
|---|---|---|---|
| `CONDDO_APP_BASE_URL` | no | `https://conddo-io.vercel.app` | `https://getconddo.com` |
| `CONDDO_CORS_ALLOWED_ORIGINS` | yes | comma-sep origins | `https://getconddo.com,https://studio.getconddo.com` |
| `CONDDO_AUTH_COOKIE_SECURE` | no | `true` | unchanged |
| `CONDDO_AUTH_COOKIE_SAMESITE` | no | `Strict` | unchanged (api + app share `getconddo.com`) |

### 3.4 Redis

| Name | Sensitive? | Current value | AWS landing |
|---|---|---|---|
| `CONDDO_REDIS_HEALTH_ENABLED` | no | `false` | flip to `true` once ElastiCache is provisioned |
| `CONDDO_REDIS_URL` *(new)* | yes | — | ElastiCache endpoint, set per environment |

### 3.5 Media (Cloudinary — unchanged)

| Name | Sensitive? | Source |
|---|---|---|
| `CLOUDINARY_URL` | yes | Cloudinary dashboard, no change |

### 3.6 Ayrshare social

| Name | Sensitive? |
|---|---|
| `AYRSHARE_API_KEY` | yes |
| `AYRSHARE_WEBHOOK_SECRET` | yes |
| `CONDDO_SOCIAL_TOKEN_KEY` | yes |

### 3.7 Studio backend (its own service)

| Name | Sensitive? | Notes |
|---|---|---|
| `STUDIO_BASE_URL` | no | `https://studio.getconddo.com` |
| `STUDIO_SERVICE_TOKEN` | yes | Internal HMAC for cross-service auth |
| `STUDIO_DB_URL` | yes | Same RDS instance, different role |
| `STUDIO_DB_USER` | yes | |
| `STUDIO_DB_PASSWORD` | yes | |
| `STUDIO_JWT_SECRET` | yes | |
| `STUDIO_JWT_ISSUER` | no | `https://studio-api.getconddo.com` |
| `STUDIO_CORS_ALLOWED_ORIGINS` | yes | Studio FE origin |

### 3.8 New for AI services (per architecture addendum)

| Name | For which service | Source |
|---|---|---|
| `CLAUDE_API_KEY` | both AI services | already in use |
| `OPENROUTER_API_KEY` | both AI services | new — sign up at openrouter.ai |
| `OPENROUTER_BASE_URL` | both AI services | `https://openrouter.ai/api/v1` (constant) |
| `DEEPSEEK_MODEL` | both AI services | e.g. `deepseek/deepseek-chat` (OpenRouter model id) |
| `CLAUDE_MODEL` | fallback / compliance | e.g. `anthropic/claude-sonnet-4-6` |
| `JINA_API_KEY` | website-generation | new — sign up |
| `AI_PROVISIONING_CONFIDENCE_THRESHOLD` | ai-provisioning | constant per env |
| `AI_PROVISIONING_FALLBACK_THRESHOLD` | ai-provisioning | constant per env |

> **Note on OpenRouter vs DeepSeek-direct.** The Infra Doc §2 originally
> named DeepSeek direct as the LLM provider, but we're routing through
> **OpenRouter** instead — same DeepSeek models, OpenAI-compatible API,
> one key covers Claude + DeepSeek + Gemini Flash + every fallback we
> might want without juggling separate provider accounts. The FastAPI
> services use the OpenAI Python SDK pointed at `OPENROUTER_BASE_URL` —
> no SDK code change to swap models, just change the model id string in
> env config. Direct DeepSeek API keys are not required.

---

## 4. Domain & networking inventory

Per Infra Doc §10 (cutover playbook). What each subdomain points at today vs after migration:

| Subdomain | Today | After cutover |
|---|---|---|
| `conddo.io` | Vercel (FE) | sunset (30-day 301 redirect to `getconddo.com`) |
| `app.conddo.io` | DNS not yet pointed | replaced by `getconddo.com` |
| `api.conddo.io` | Render | ALB → ECS conddo-backend on `api.getconddo.com` |
| `studio.conddo.io` | Vercel (FE) | `studio.getconddo.com` |
| `studio-api.conddo.io` | Render | ALB → ECS conddo-studio on `studio-api.getconddo.com` |
| `payments.conddo.io` | Render | ALB → ECS conddo-payments on `payments.getconddo.com` |
| `ai.getconddo.com` *(new)* | — | ALB → ai-provisioning + workflow-engine + website-gen |

DNS / cert checklist:
- [ ] Register `getconddo.com` — confirm auto-renew is on
- [ ] Route 53 hosted zone created
- [ ] ACM certs issued for `*.getconddo.com` and `getconddo.com`
- [ ] Cert validation records in Route 53
- [ ] Test new endpoints resolve before flipping `conddo.io`

---

## 5. Free-tier budget tracker (Infra Doc §7)

Fill the dates / caps as you confirm them.

| Service | Cap | Resets / expires | Currently using | OK? |
|---|---|---|---|---|
| AWS general | 12 mo from `[YYYY-MM-DD signup]` | `[YYYY-MM-DD]` | — | ✓ |
| RDS db.t4g.micro | 750 hrs/mo, 20 GB | same 12-mo | — | ✓ |
| ECS Fargate | no free tier | — | — | budget for $5-15/mo per service |
| ElastiCache t4g.micro | 750 hrs/mo | same 12-mo | — | ✓ |
| ECR | 500 MB private storage | monthly | — | ✓ |
| Route 53 | $0.50/zone/mo (no free) | — | — | $1-2/mo total |
| ACM | free | — | — | ✓ |
| ALB | no free; $16/mo + per LCU | — | — | $16-25/mo |
| CloudWatch | 5 GB ingest free | monthly | — | ✓ |
| OpenRouter | pay-as-you-go credit balance (no free tier) | — | top up as needed | check weekly during AI build-out |
| Sentry | `[plan event cap]` | monthly | — | check monthly |
| Opik | `[plan trace cap]` | monthly | — | check monthly |
| Jina | `[rate limit]` | — | — | as usage grows |
| GitHub Actions | 2,000 min/mo | monthly | usage TBD | check monthly |

---

## 6. OpenTofu skeleton — recommended structure

Per Infra Doc §2 (IaC = OpenTofu, no manual console changes after initial setup). Suggested layout:

```
conddo-infra/                          ← new repo (or subfolder)
├── modules/
│   ├── networking/                    ← VPC, subnets, security groups, ALB
│   ├── database/                      ← RDS instance, parameter groups, backups
│   ├── cache/                         ← ElastiCache Redis
│   ├── compute/                       ← ECS cluster, service definitions, task defs
│   ├── secrets/                       ← Secrets Manager entries
│   └── observability/                 ← CloudWatch log groups, alarms
├── envs/
│   ├── dev/
│   │   ├── main.tf                    ← composes modules with dev sizing
│   │   ├── backend.tf                 ← S3 + DynamoDB state backend
│   │   └── terraform.tfvars
│   ├── staging/
│   └── production/
└── README.md                          ← runbook
```

OpenTofu state lives in S3 with DynamoDB locking. This is the single most important "don't lose this" item — state file says "what AWS resources actually exist". Per Infra Doc §8 checklist item: **state must be in S3, not on your laptop.**

---

## 7. The actual migration order

Recommended sequence so each step has something demonstrable:

1. **Provision Postgres on RDS** — restore a snapshot from Render, verify connectivity via psql from a temporary EC2 jump box, run a Flyway migration as the owner role to confirm.
2. **Provision Redis on ElastiCache** — verify connectivity.
3. **Stand up ECR + build pipeline** — push the existing Docker images to ECR via GitHub Actions on `dev` branch.
4. **Deploy conddo-backend to ECS Fargate dev** — point at the new RDS, verify `/actuator/health`. CORS still allows `*.vercel.app` for now.
5. **Deploy conddo-studio + conddo-payments to ECS Fargate dev** — same pattern.
6. **DNS test** — `api-dev.getconddo.com` → new ALB. Don't touch `api.conddo.io` yet.
7. **Run dev FE against dev BE** — `NEXT_PUBLIC_API_URL=https://api-dev.getconddo.com` on Vercel dev env. Smoke test for a week.
8. **Promote to staging** — same Fargate cluster, second task definition, `api-staging.getconddo.com`.
9. **Promote to production** — third task definition, `api.getconddo.com`. Cut DNS in Route 53. Drain Render at end of month.
10. **Sunset Render** — keep it alive for 30 days as a fallback, then delete.

The two FastAPI services (ai-provisioning, workflow-engine) land alongside step 4-5 once their codebases exist.

---

## 8. Verification checklist (Infra Doc §8)

Run before calling the migration done:

- [ ] Visit `getconddo.com` from incognito with no prior traffic for 10 min — **no cold-start delay**
- [ ] `dev` / `staging` / `production` pointing at separate Postgres instances (test row in dev doesn't appear in staging/prod)
- [ ] No secrets committed to any repo (`gitleaks detect` or `git log -p | grep -Ei "api_key|secret|password"`)
- [ ] Sentry receiving events (deliberate test error)
- [ ] CloudWatch receiving logs from every ECS service
- [ ] OpenTofu state in S3, not on your laptop
- [ ] AWS Billing Alert at $5, $20, $50

---

## 9. Open decisions to nail down before any provisioning

These must be answered before OpenTofu code gets written. Brackets = unfilled:

- [ ] Compute choice (§3): EC2 / Fargate / Lambda — `[Fargate recommended]`
- [ ] Postgres choice (§4): RDS / self-managed — `[RDS recommended for the first year]`
- [ ] Manual-approval policy for production deploys — `[recommended: yes, required reviewer]`
- [ ] Backup retention: `[default 7 days on RDS]`
- [ ] How long to keep Render alive after cutover — `[30 days recommended]`
- [ ] Multi-AZ on RDS? — `[no in dev/staging, yes in production]`
- [ ] Will the AI services share the ECS cluster with Java? — `[yes — saves on minimum-fee charges]`
- [ ] Who has root AWS account access? — `[only you for now; rotate to IAM users immediately]`

---

## 10. What's NOT in this doc (out of scope for prep)

- Actual OpenTofu module code — write that after §9 decisions land
- IAM role policies — write those alongside the modules
- Specific deploy GitHub Actions workflows — those go in each app repo, not here
- Cost estimation — depends on instance sizing decisions still open

---

*Update this doc as decisions land. The bracketed items above are the to-do list. The migration runbook in §7 is the order of operations; revisit it if §9 forces a different sequence.*
