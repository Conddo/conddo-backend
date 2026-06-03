# Conddo.io — Product Requirements Document

**Status:** Living document
**Version:** 1.0 (V1 launch baseline)
**Last updated:** 2026-06-03
**Owner:** Mickercode
**Audience:** Product, Design, Engineering, Leadership, Investors

> **Companion docs:**
> [REQUIREMENTS.md](./REQUIREMENTS.md) is the technical requirements + endpoint contract.
> This PRD answers *what* and *why*. REQUIREMENTS.md answers *how*.

---

## TL;DR

**Conddo.io is a vertical-intelligent SaaS for Nigerian small businesses.** A
fashion brand, a pharmacy, a logistics op, or a restaurant signs up, picks
their industry, and within 72 hours has a working website + a complete
operations dashboard tailored to how their industry actually runs.

Two things make us different from Bumpa, Selar, Wix, and Notion:

1. **We build the website for them.** An internal production team (Conddo
   Studio) takes the brief and ships a real site to a `<brand>.conddo.io`
   subdomain — fast, professional, mobile-first.
2. **The platform adapts to the vertical.** A pharmacy sees NAFDAC numbers
   and expiry dates. A fashion brand sees measurement profiles and fitting
   stages. Same product, different fields.

V1 targets the eight verticals where Nigerian SMEs are most underserved by
existing tools. We launch in Lagos / Abuja / Port Harcourt, English-only,
Naira-native.

---

## 1. The Problem

Nigerian small businesses are running production-scale operations on
consumer-grade tooling.

### 1.1 What the day looks like for a real SME today

**Amaka runs a fashion brand from her phone.** She gets orders in three
WhatsApp threads, two Instagram DM conversations, and the occasional phone
call. Customer measurements live in screenshots. She invoices on her bank
app and tracks payments in a notebook. Stock lives in her head. When a
customer asks "where's my dress?" she scrolls back through six chats. She
hires a freelancer to build her a Wix site for ₦200k; the freelancer ghosts
after the homepage.

**Tunde runs a pharmacy.** He needs to track NAFDAC numbers, batch
expiries, and prescription history per customer. The "pharmacy POS"
software he tried was built for the US — no NAFDAC fields, no Naira
support, no SMS to remind patients to refill. He's running an Excel sheet
his nephew set up.

**Biodun is a solo consultant** with five retainers and three project
clients. He sends invoices via Gmail and chases payment on WhatsApp. His
client list lives in his contacts. He'd run Instagram ads for new leads,
but the platform wants a US dollar card — his Naira debit doesn't work.

**Emeka runs last-mile delivery** with six riders. He tracks trips on a
WhatsApp group chat. Proof of delivery is "the rider sent me a photo." Two
parcels went missing last month — he has no idea which.

### 1.2 What's broken

| Pain | Today's "solution" | What it really costs |
|---|---|---|
| Fragmented tooling | WhatsApp + Excel + bank app + Insta DMs + Notebook | Lost orders, lost customers, hours/day stitching it together |
| No website (or a broken one) | Pay a freelancer ₦200k; get ghosted | A year without a web presence in a country going digital |
| Can't run ads without a dollar card | Don't run ads | Stuck at organic reach |
| Generic SaaS doesn't fit | Force-fit Shopify / Salesforce | Pay for 80% you don't use, miss the 20% you need |
| Mobile + 3G | "Run my business on my laptop" | Most of the day on phone, can't reach desktop SaaS |
| No industry context | "Add a custom field" | Build your own ERP from primitives |

### 1.3 Market signal

- **Nigerian SMEs**: ~41 million MSMEs (SMEDAN 2023), of which ~80% are
  micro (1-9 staff), the segment we serve.
- **Smartphone penetration**: ~50% and rising; data costs falling.
- **E-commerce growth**: 19% YoY (2024); SME share growing faster than
  enterprise.
- **Bumpa**: ~$200k MRR (estimated 2024). Generic SaaS, not vertical-aware.
- **Selar / Paystack Storefront**: payment-first, not operations.
- **Wix / Webflow**: DIY, English-tuned, no operations layer.

**No-one is building "operations + website + payments + marketing"
specifically for the Nigerian micro-SME with verticalisation.** That's our
gap.

---

## 2. Vision

> **The operating system every Nigerian small business runs on.**

Said concretely: in five years, when a Lagos-based fashion designer or an
Abuja-based pharmacy owner thinks "I need a website + a place to manage my
business," they think Conddo.io — the same way a US/UK creator thinks
Shopify, or a SaaS founder thinks Stripe.

That means three things, in order of importance:

1. **We win on vertical fit.** When a pharmacy owner sees the product, the
   first thing they notice is that it *knows* what a pharmacy is. NAFDAC
   numbers. Expiry alerts. Prescription history. The product feels built
   for them because it was. Generic SaaS can't catch up without rebuilding.
2. **We ship websites that actually launch.** Most SME signups elsewhere
   die at "and then you build the website." Ours don't — a real human team
   ships the site within days. The customer's job is to run the business,
   not be a web designer.
3. **We're priced for the Nigerian SME.** ₦25k–₦80k/month. Not the
   ₦1.2M/year a US-priced platform demands.

---

## 3. Goals & Success Metrics

### 3.1 V1 launch goals (first 6 months)

| Goal | Metric | Target |
|---|---|---|
| **Tenant acquisition** | Signups completed (start → /complete) | 500 |
| **Activation** | % of new tenants who log in 3+ times in first 7 days | 60% |
| **Website delivery** | % of `WEBSITE_BUILD` jobs delivered within 7 days of signup | 85% |
| **Retention (month 1)** | % of new tenants still logging in at day 30 | 50% |
| **First customer interaction** | % of new tenants who record a customer / order / booking within 14 days | 60% |
| **Studio QA quality** | First-pass approval rate | > 70% |

### 3.2 V1 anti-goals

Things we are **explicitly not optimising for** in V1:

- **MRR growth above adoption.** We'd rather 500 active tenants on
  Starter than 100 on Pro. Adoption is the V1 game.
- **NPS.** We'll measure it but not gate launch on it; expect noise at
  small numbers.
- **Conversion rate optimisation.** Landing-page conversion matters less
  than what happens *after* signup. Don't AB-test our way out of fixing
  the activation funnel.
- **Premium plan upsell.** Pro is for tenants who've outgrown Business,
  not for landing-page upsell experiments.

### 3.3 Strategic North-Star metrics (years 1-3)

Beyond V1:

- **Weekly active tenants** (logged in + at least one write action).
  Year 1 target: 2,000. Year 3: 20,000.
- **% of tenants with a live, delivered Conddo.io website**. Year 1: 70%.
- **Average tenant-monthly transaction volume (₦)**. Indicates we own real
  business activity, not just a dashboard tab.

---

## 4. Personas

We design for four primary personas and one secondary. Every feature
decision is tested against "would this make sense to Amaka / Tunde / Biodun
/ Emeka?"

### 4.1 Amaka — Fashion micro-brand owner (Primary)

- **28 years old**, lives in Lagos (Ikeja).
- Runs *Amaka Styles* — custom fashion, Asoebi, occasion wear.
- ~30 active customers per month, ₦1.5–2M monthly revenue.
- Works from her bedroom and a tailor's shop she shares.
- 2,400 Instagram followers; gets ~80% of orders via IG DMs.
- One assistant tailor; otherwise solo.
- **Phone-first.** Laptop is for "complicated things" — emails, Canva.
- Already on WhatsApp Business; Excel sheets for measurements; bank app for
  receipts.
- **Pain:** Customers ask "where's my order?" and she has to scroll back
  through three chats. Loses fittings because measurements live in her
  phone gallery and she can't find them.

**What Amaka wants from Conddo.io:**
- A website that looks professional so she can charge more.
- Measurements stored against each customer, retrievable in 2 seconds.
- Orders moving through stages (Cutting → Stitching → Ready) without her
  thinking about it.
- Send Instagram ads in Naira to grow her audience.

**How she'd find us:** Instagram referral from another fashion designer,
or a TikTok from us showing "what your customer measurement page looks
like inside Conddo."

---

### 4.2 Tunde — Pharmacy owner (Primary)

- **45 years old**, lives in Abuja.
- Runs *Wellspring Pharmacy* — one storefront, 3 staff (1 pharmacist + 2
  assistants).
- ~₦4M monthly revenue, mostly walk-ins + a few regulars who refill weekly.
- Open 7am–9pm. NAFDAC registered. Pharmacist Council compliant.
- **Phone + laptop.** Runs the till on an old laptop; checks inventory on
  his phone after closing.
- Currently uses Excel for stock and a notebook for prescriptions.
- **Pain:** Lost ₦80k last quarter on expired stock he didn't catch.
  Regulars churn when he runs out of their medication because he didn't
  reorder in time.

**What Tunde wants from Conddo.io:**
- NAFDAC + expiry tracking on every SKU.
- "About to expire" alerts 60 days out.
- Prescription history per customer with refill reminders via SMS.
- Booking flow for pharmacist consultations.

**How he'd find us:** Word of mouth from a fellow pharmacist; SMS marketing
to NAFDAC-registered pharmacies in Abuja.

---

### 4.3 Biodun — Solo consultant (Primary)

- **35 years old**, lives in Lagos (Lekki).
- Runs *Beecroft Consulting* — strategy + branding for SMEs.
- 5 retainer clients (₦200k–₦500k/month), 2–3 project clients per quarter.
- Works alone; subcontracts designers + writers per project.
- **Laptop + phone.** Lives in Notion and Gmail; invoices on Wave.
- **Pain:** Loses 4–6 billable hours/week to admin (chasing payments,
  writing scope docs, sending invoices). Can't run client-acquisition ads
  because Instagram won't accept his Naira card.

**What Biodun wants from Conddo.io:**
- Track billable hours per retainer; alert when over-budget.
- One-click invoice generation that the client can pay via Paystack.
- A polished website that converts the inbound enquiries he gets.
- Naira-native ad budget top-ups for IG + Facebook lead generation.

**How he'd find us:** LinkedIn ads targeting Lagos consultants; founder-led
content showing "how Biodun closes the loop between proposal → retainer →
invoice in Conddo."

---

### 4.4 Emeka — Last-mile logistics operator (Primary)

- **38 years old**, lives in Lagos (Surulere).
- Runs *EM Logistics* — last-mile delivery, 6 riders.
- ~120 trips/day, ₦3–5M monthly revenue.
- Most clients are local: pharmacies, fashion brands, online sellers.
- **Phone-first**, all day. Riders on phones too.
- Currently tracks everything on a WhatsApp group + a printed ledger.
- **Pain:** Two parcels lost in March he can't reconstruct who lost. Clients
  ask for "delivery proof" and he sends a blurry screenshot of the WhatsApp.

**What Emeka wants from Conddo.io:**
- Live trip status per rider per parcel.
- POD (proof of delivery) — photo + signature, attached to the trip record.
- On-time rate per rider visible to him; per-month visible to his clients.
- Auto-bill clients monthly from logged trips.

**How he'd find us:** B2B sales to e-commerce SMEs ("upgrade your last-mile
partner — give them Conddo logistics"); fashion-brand referrals
("Conddo recommends Emeka for delivery").

---

### 4.5 The Conddo Studio team (Secondary)

Internal Handel Cores staff who deliver the websites. Six roles:
Developer, Designer, Writer, QA Reviewer, Team Lead, Admin.

Not the customer — but the design has to make sense for them too because
**they're the throughput bottleneck**. If a developer can't claim and
deliver 8 jobs per week, V1's "ship a site within 7 days" promise fails.

**What the Studio team needs:**
- A job board they can scan in 30 seconds — what's mine, what's available,
  what's late.
- Brief + assets + AI suggestions + design standards bundled per job.
- An export of the bundle so they can work offline / in their preferred
  external tools.
- A QA queue that's clear about what's required vs nice-to-have.
- SLA visibility so leads can re-balance before something turns red.

---

## 5. Use Cases & User Stories

The headline journeys. Each is a "would the product feel right to do this?"
sanity check.

### 5.1 Tenant journey

**Day 0 — Sign up**

> *As Amaka, on my phone at 6am after a midnight DM order, I want to sign
> up for Conddo.io in under 5 minutes, including verifying my phone, so
> I can stop losing orders to chat history.*

- Continue with Google **or** enter email/password + phone
- Phone OTP (4-digit, expires in 10 min)
- Choose business name → workspace slug (`amaka-styles.conddo.io`)
- Pick business type (Fashion) from 8 verticals
- Pick plan (Starter / Business / Pro; monthly/annual toggle visible)
- Land on the dashboard, already authenticated
- Within minutes: she's been emailed the OTP via Brevo, she's logged in,
  her workspace is ready, and a `WEBSITE_BUILD` job has fired to the
  Conddo Studio team — no action required from her.

**Day 1-3 — Website in progress**

> *As Amaka, while my website is being built, I want to start using the
> dashboard to log my real orders, so I'm not wasting the wait.*

- Dashboard greets her by name + business name
- "Your website is being built" status card with an ETA
- "While you wait" checklist: Add your first customer · Create your first
  order · Connect Instagram
- She adds 4 customers from her WhatsApp + 2 in-progress orders

**Day 3-7 — Website lands**

> *As Amaka, when my website is ready, I want to share it instantly so
> my next IG-DM customer just gets the link.*

- Studio team submits the job, QA approves, status flips to DELIVERED
- Amaka gets a notification (in-app + email + SMS): "Your website is live
  at amaka-styles.conddo.io"
- She opens it on her phone, screenshots it, posts to IG

**Day 7-30 — Daily operations**

> *As Amaka, when a customer DMs me wanting a 4-piece Asoebi, I want to
> create the order in under a minute, capture her measurements, and have
> the order move through Cutting → Stitching → Ready without me thinking
> about it.*

- New Order modal: pick customer (auto-suggests existing), add items, add
  measurements (vertical-specific fields), set stage
- Stage transitions move the card across the board
- When stage = Ready, the customer auto-gets an SMS

**Month 2 — Marketing**

> *As Amaka, I want to run an Instagram ad targeting Lagos women aged
> 22-40 interested in Asoebi, with a ₦25,000 budget, without needing a
> dollar card.*

- Marketing → Ads → New campaign
- Choose Instagram / Facebook channel; target audience; budget in Naira
- Conddo charges her ₦25,000 to her plan + an ad-platform fee
- Campaign goes live; reach + clicks + new IG followers tracked in her
  dashboard

### 5.2 Conddo Studio team journey

**Developer's morning**

> *As a Developer on the Studio team, I want to see what's mine, what's
> available, and what's coming up against SLA — without reading 50
> notifications.*

- Login → Dashboard shows: My active jobs (3), Available jobs matching
  my skills (5)
- Click into one available job → read brief + see assets + AI-suggested
  copy + design standards for the vertical
- Hit Claim → job moves to my list
- Hit Open Builder Kit → downloads the export bundle (brief.md +
  assets/ + ai-suggestions.json + standards.json)
- Build the site in VS Code / external tools
- When done: import the bundle back with the live `studioUrl`
- Hit Submit for QA

**QA reviewer's afternoon**

> *As a QA Reviewer, I want to review a submission against the standard
> checklist in 5-10 minutes, with clear pass/fail per item.*

- QA Queue → click oldest item
- Hit "Start review" → checklist appears (section-grouped: Content / Design
  / Technical), each item pass/fail with optional note
- Studio URL opens in side panel
- Hit Approve (all required items pass) → job moves to DELIVERED, customer
  is notified
- Or hit Return for Revision → write feedback, items the developer needs
  to fix

**Lead's mid-shift**

> *As a Team Lead, I want to see the SLA health of the whole board at a
> glance and intervene on anything turning amber.*

- Operations Dashboard: GREEN / AMBER / RED counts, status breakdown
- Live updates via SSE — no refresh needed
- Click an amber job → see why (assignee on leave; original SLA was tight)
- Three lead actions: Reassign (to someone with capacity), Extend SLA
  (with reason logged), Escalate (broadcasts to all leads + admins via
  email + SSE)

### 5.3 Platform admin journey (pending §23)

**A real-user incident**

> *As a Platform Admin, when a tenant tells me they can't log in or got
> hacked, I want to find them, see their session state, and act —
> without writing SQL.*

- Studio → Platform → Search "amaka-styles" → tenant profile
- See: status (ACTIVE), 1 admin user, 3 staff, last login 2 days ago
- See per-user: active sessions, recent activity, lockout state
- Actions: Force password reset (Amaka gets an email), Suspend tenant
  (kills all sessions), Change a user's role
- Every action audited; SSE broadcast to all Studio admins for transparency

---

## 6. Features & Functional Requirements

What V1 ships, prioritised by P0 / P1 / P2 within each surface.

### 6.1 Tenant Platform (conddo-app)

#### Authentication & Signup (P0)

- Email + password signup with phone-OTP verification
- Continue with Google (frontend wired; backend Phase 1a in flight)
- Forgot / reset password via email
- Refresh tokens with rotation + family-reuse detection
- 5-strike lockout with exponential backoff

#### Dashboard (P0)

- KPI cards: Revenue today, Pending orders, New customers, Low stock items
- Recent orders list
- Today's bookings list
- Setup checklist (Add customer, Create order, Connect IG, etc.)
- Personalised greeting + business name

#### Customers (CRM) (P0)

- List view (search, filter, sort)
- Detail view: profile + measurements (Fashion) / NAFDAC history
  (Pharmacy) / project history (Consulting) / etc.
- Add Customer modal
- Customer notes, contact details, purchase history

#### Orders (P0)

- Kanban-style board with stages per vertical
- Detail view with line items, customer link, payment status, stage
  history
- Stage transitions (one click per stage)
- New Order modal with customer picker (autosuggest existing)

#### Bookings (P1)

- Today's bookings on dashboard
- Availability management (when am I open, how long is each slot)
- Public self-book at `<slug>.conddo.io/book` — customer-facing
- New Booking modal (internal)

#### Inventory (P1)

- SKU list with stock levels
- Adjust stock modal
- Low stock alerts (configurable threshold)
- Per-vertical fields: NAFDAC + expiry (Pharmacy), fabric type (Fashion),
  size/colour (Retail)

#### Payments (P0)

- Paystack integration (in-app + via website)
- RoutePay rail (V1.5 — separate service workstream)
- Payment list (received, outstanding, overdue)
- Send reminder via SMS / email
- Record manual payment (cash / bank transfer)

#### Marketing (P1)

- Social: schedule IG / Facebook / Twitter posts from one composer
- Email campaigns (Brevo)
- SMS campaigns (Brevo SMS / Termii)
- Ads: Naira-native top-up for Instagram + Facebook ad spend
- Leads inbox (forms on the website pipe into here)

#### Website (P0)

- "Your website is being built" status (Day 0 → Day 7)
- Change request flow: edit a section → posts to Studio as a `WEBSITE_REVISION` job
- Preview link to the live site
- Subdomain claim (Amaka picks `amaka-styles.conddo.io`)

#### Analytics (P1)

- Revenue chart (daily, weekly, monthly)
- Best sellers (products / services)
- Customer activity (new, returning, churned)
- Marketing campaign performance (reach, clicks, conversions)

#### Staff (P1)

- Invite via email
- Roles: TENANT_ADMIN, STAFF, CUSTOMER
- Per-staff activity log

#### Settings (P1)

- Account: name, business name, vertical, plan
- Billing: card on file, invoices, change plan
- Connections: IG, Facebook, Google, Paystack — connect via OAuth
- API keys (for Pro+ tier)
- Notification preferences
- Danger zone: deactivate / delete

#### Notifications (P0)

- In-app feed (unread count badge on bell icon)
- Email mirror for critical events
- SMS for select events (configurable)

### 6.2 Conddo Studio (Internal ops platform)

#### Worker loop (P0)

- Available jobs list (filtered by my skills)
- Claim job (atomic — no two devs claim same job)
- Start work (ASSIGNED → IN_PROGRESS)
- Submit for QA (with studioUrl + notes)

#### Job detail (P0)

- Brief view (vertical-specific JSON rendered as labeled sections)
- Files / Assets (Cloudinary upload + list + delete)
- AI suggestions (copy per section, palette, image ranking)
- Activity log (every state transition)
- Lead actions (Reassign, Extend SLA, Escalate — visible to TEAM_LEAD+ only)

#### QA queue (P0)

- All submitted jobs awaiting review
- Per-job review screen with section-grouped checklist
- Approve (advances to DELIVERED) or Return with feedback (back to assignee)
- AI QA scan (Claude, optional — `available:false` when off)

#### Admin (P0 for ADMIN role)

- Operations dashboard (SLA tone counts, status breakdown, all jobs)
- All Jobs view (filterable)
- Staff CRUD (invite, role, deactivate)
- Job Types CRUD (add new types, tune SLA hours per type, QA checklists)
- Design Standards Library (palettes / layouts / copy patterns / typography by vertical)

#### Realtime (P0)

- One SSE stream per browser tab (`/api/jobs/events`)
- Live updates to all boards on job state changes
- Live SLA tick (every 5 min while AMBER/RED jobs exist)
- Live notification bell

#### Performance (P1)

- Per-staff dashboard: jobs completed, target progress, first-pass QA
  rate, revisions received
- Monthly snapshots, daily recalc at 02:00 UTC
- (Pending) per-staff history view + per-staff drill-down for leads

#### Builder workflow (P1 — pending §22)

- Export: download a ZIP of the job's brief + assets + AI suggestions +
  standards + QA history + activity
- Build the site externally in tools of choice
- Import: re-upload the bundle to attach the live URL + any new files
- Atomic with optimistic-lock check

#### Platform Admin (P2 — pending §23)

- Cross-tenant management (tenants list, search, suspend, reactivate)
- Cross-tenant user management (find user, force password reset, suspend)
- Audit log of every cross-tenant admin action

### 6.3 Cross-cutting

- **Vertical intelligence** — every customer-facing screen adapts its
  fields to the tenant's selected vertical (see [VERTICALS.md](./VERTICALS.md))
- **Tenant subdomain routing** — `<slug>.conddo.io` resolves to the
  tenant's website on first request
- **Service-to-service** — signup auto-creates a Studio job (async, with
  bounded timeout so a sleeping Studio never blocks signup)
- **Audit log** — every state change writes to `audit_log` with
  before/after JSONB

---

## 7. UX Requirements

The non-negotiables of how Conddo.io feels.

### 7.1 Mobile-first

- **All FE designed for 360px first**, scaled up.
- The hero, the dashboard, the dashboard for every vertical, the
  checkout, and onboarding all tested on a real mid-tier Android.
- No critical action takes more than two taps from the home tab.
- 3G-friendly: route transitions show progress, images lazy-load, JSON
  payloads are slim.

### 7.2 Dark mode (Studio)

- Conddo Studio is dark by default. The team works long shifts; daylight
  glare matters.
- All Studio tokens designed for AA contrast on the dark palette.
- conddo-app is light mode only (customer-facing convention).

### 7.3 Speed feel

- **First meaningful paint < 2s on mid-tier Android over 3G.**
- 401 → silent refresh → retry. The user never sees an unexpected logout.
- Cold-start backend: surfaces as a clean "server didn't respond in time"
  message at 45s, not an infinite spinner.
- Pricing toggle, vertical-tab switch, and SSE-driven board updates are
  instant (sub-100ms client-side state).

### 7.4 Accessibility

- Keyboard-navigable end to end.
- Visible focus rings on every interactive element.
- WCAG AA contrast (≥ 4.5:1) on body text; AAA where feasible on
  headings.
- `prefers-reduced-motion` honoured (hero rotation freezes, route
  transitions skip).
- All icon-only buttons have `aria-label`.
- Forms have explicit labels, not placeholders-as-labels.

### 7.5 Brand voice

- **Direct. Concrete. Naira-native.** "Get paid faster. Track every
  naira." not "Empower your business with our seamless solutions."
- Banned words on all customer-facing copy: *seamless, leverage, robust,
  innovative, scalable, cutting-edge, empower, transform,
  state-of-the-art, best-in-class*.
- Use real Nigerian names + businesses in mocks (Amaka Styles, Wellspring
  Pharmacy, EM Logistics). Never Acme / Foo Bar / Lorem Ipsum.

### 7.6 Trust signals

- Real photos of Nigerian business owners on the landing page (proof bar
  + testimonials).
- Specific numbers, not vague claims ("Lagos · Abuja · Port Harcourt"
  beats "thousands of businesses").
- Verifiable cities, verifiable launch year. Replace with real metrics
  (signups / revenue processed) as we hit them.

---

## 8. Technical Considerations

High level — full detail in [REQUIREMENTS.md](./REQUIREMENTS.md) and the
architecture / infrastructure docs.

### 8.1 Two-plane architecture

- **Control plane** (tenant-facing): conddo-api + conddo-app, RLS-scoped.
- **Production plane** (internal ops): conddo-studio + conddo-studio FE,
  owner-role, no RLS.
- Separate services, separate JWTs, separate auth flows. Shared Postgres
  with schema isolation.

### 8.2 Tenant isolation via RLS

- Every tenant-scoped table carries `tenant_id` and an RLS policy.
- App connects as a non-owner role; cannot bypass RLS.
- Per-request: `app.tenant_id` GUC is set from the JWT's `tenant_id` claim.
- Per-transaction: GUC is set inside `TenantSession.bind()` — never leaks
  across the connection pool.

### 8.3 Naira-native payments

- Paystack (V1) for online + in-person.
- RoutePay (V1.5) as the second rail, designed as its own service.
- Brevo SMS / Termii for transactional SMS.

### 8.4 Vertical intelligence

- A tenant picks a `verticalId` at signup.
- Backend's `VerticalToolMatrix` resolves which feature modules are
  enabled.
- Frontend's `manifest` (driven by backend) decides which nav items,
  fields, and screens to render.
- Per-vertical specifics: see [VERTICALS.md](./VERTICALS.md).

### 8.5 Hosted by

- Render (backend services + Postgres)
- Vercel (both frontends)
- Cloudinary (media)
- Brevo (transactional email + SMS)
- Resend (email fallback)
- Anthropic Claude (AI assistant inside Studio — copy / palette / image
  ranking / QA scan)

---

## 9. Out of Scope (V1 Non-Goals)

What we are explicitly **not** building in V1. Every item here was
considered and consciously deferred.

### 9.1 In-app website builder

A drag-and-drop or Sites-of-pages CMS where the tenant builds their own
website inside Conddo.

**Why not:** Two reasons:

1. The Conddo Studio team builds it for them — that's our differentiator.
2. Customers told us in user research that they don't want to be web
   designers. "Just build me a good site."

Replaced by: Studio's external builder workflow + the export/import seam
(§22).

### 9.2 Multi-language

V1 is **English only**. Even though Yoruba / Hausa / Igbo are widely
spoken, English is the lingua franca of Nigerian business communication.

**Revisit when:** A vertical or city demands it (e.g. a pharmacy chain in
the Kano market).

### 9.3 Multi-currency

V1 is **Naira only**. No USD, no GHS, no XOF.

**Revisit when:** We expand to a second country (Ghana most likely).

### 9.4 Customer-facing native mobile app

V1 is **mobile-web only** (the customer-facing dashboard runs in a phone
browser, PWA-grade).

**Why not:** Native apps add 6+ months of build + app-store review
overhead, with marginal benefit when ~95% of our target audience already
uses mobile browsers. We'll re-evaluate when web limitations bite (e.g.
push notifications, offline-first).

### 9.5 Customer DIY templates / page editing

Customers cannot edit their website pages themselves — they request
changes via the dashboard and Studio re-edits and re-deploys.

**Why not:** Quality + brand control + Studio is the differentiator.

### 9.6 Workflow automation / "Zapier inside Conddo"

V1 has no rule-builder for "when X happens, do Y."

**Revisit when:** We have ~5,000 active tenants and a third are asking
for it.

### 9.7 Marketplace / cross-tenant commerce

V1 is not a marketplace. Tenants don't sell to each other's customers,
and we don't aggregate listings.

**Revisit when:** Verticals like Logistics and Retail organically demand
cross-tenant integration.

---

## 10. Launch Plan

### 10.1 V1 Launch criteria (must-pass gates)

A signup goes through these gates before we declare V1 live. All gates
green = ship.

| Gate | What passes | Status |
|---|---|---|
| **Signup end-to-end** | A new tenant from a real phone completes start → verify → complete in < 5 min, lands on dashboard, has a Studio job auto-created | ✅ once `STUDIO_BASE_URL` + `STUDIO_SERVICE_TOKEN` on Render |
| **Email delivery** | OTP and welcome email land in inbox (Gmail, Outlook, Yahoo) within 30s | ✅ Brevo branded templates |
| **Google Sign-in** | "Continue with Google" works on both /login and /onboarding/create-account | ⏳ backend in flight |
| **Build → delivery** | A real Studio team member can claim, build, submit, and have QA approve a job | ✅ Studio worker loop + QA loop shipped |
| **Realtime board** | Two browser tabs see live SSE updates without refresh | ✅ shipped |
| **Performance recalc** | Monthly snapshots fill the perf page within 24h of first signup | ✅ daily recalc cron live |
| **Mobile** | The landing + signup + dashboard all work on a 360px Android Chrome | ✅ post-audit b06a700 |
| **Render env health** | `STUDIO_BASE_URL` + `STUDIO_SERVICE_TOKEN` set on conddo-backend so signup → Studio job is automatic | ⏳ user action pending |
| **Studio admin bootstrap** | Fresh Studio deploys can create their first admin via env vars, no manual SQL | ✅ shipped |

### 10.2 Phased rollout

| Phase | Window | Target |
|---|---|---|
| **Closed beta** | Weeks 1–2 | 20 tenants, hand-selected, supported in person/WhatsApp |
| **Open beta** | Weeks 3–8 | 200 tenants, signups from referral + landing-page |
| **Public launch** | Week 9 onwards | Open signups, paid advertising, partnership PR |
| **V1.5** | Months 4–6 | RoutePay live, Platform Admin shipped, mobile audit polish, ~500 active tenants |

### 10.3 What "launched" means concretely

A tenant signs up today and:

1. Sees a professional, fast landing page
2. Completes signup in < 5 min with phone OTP and lands on a working
   dashboard
3. Receives a confirmation email within 30s
4. Has a Studio job auto-created for their website
5. Gets their website delivered within 7 days
6. Can add customers, orders, bookings, inventory, accept payments, run
   marketing campaigns, see analytics — all in Naira, all tuned to their
   vertical
7. Sees the platform stay live (no Render-cold-start spinners > 60s)

If all 7 hold for the first 100 paid tenants without escalation tickets
above P2, V1 is launched.

---

## 11. Risks & Mitigations

What could go wrong, in order of severity.

### 11.1 Studio team can't keep up with website demand

**Risk:** We promise "website in 7 days." If signup volume outpaces Studio
team throughput, SLAs slip, tenants churn pre-activation.

**Likelihood:** Medium (signup is the easy part; build throughput is the
bottleneck).

**Mitigation:**
- Performance dashboard surfaces individual + team throughput daily.
- SLA monitor + escalation surfaces stuck jobs to leads in real time.
- Hiring plan tied to weekly signups (1 developer per 50 weekly signups).
- AI assistant (copy + palette + image ranking) shortens build time per
  job by 30-50% target.
- If we still slip: temporarily turn off paid acquisition until we catch
  up. Better than churning new tenants.

### 11.2 Render free tier latency at scale

**Risk:** Cold starts of 30-60s on first request after idle look broken
to a new user.

**Likelihood:** High while we're on free tier; resolves by upgrade.

**Mitigation:**
- FE timeouts surface as clean errors instead of infinite spinners.
- Critical paths (signup `/complete`) are async-decoupled from secondary
  work (`@Async` listener for Studio job intake).
- Upgrade to Render Starter ($7/mo) before paid-launch ad-spend ramp —
  removes the sleep entirely.

### 11.3 Tenants don't activate

**Risk:** Tenant signs up, never uses the product. Especially before the
website is delivered (Day 0–7 window).

**Likelihood:** Medium-High — the activation gap from signup → first real
use is where most SaaS dies.

**Mitigation:**
- Onboarding "while you wait" checklist drives early actions (add a
  customer, create an order) even before the website lands.
- Email nudges at Day 1, Day 3, Day 7 with concrete next steps.
- The dashboard greets them by name + business — feels active, not
  abandoned.
- Studio job delivery itself is a milestone — Day 7 should always trigger
  a "Your website is live" celebration moment.

### 11.4 Free tier ceiling (email + Brevo)

**Risk:** Brevo free tier limits at 300 emails/day. At ~50 signups/day
plus password resets + notification mirrors, we exceed by week 4.

**Likelihood:** High — happens deterministically as we scale.

**Mitigation:**
- Monitor daily send count; alert at 80%.
- Upgrade to Brevo Lite ($25/mo, 5,000/day) before launch ramp.

### 11.5 Nigerian regulatory shifts

**Risk:** New NDPR / CBN rules around personal data, e-commerce, or
payment processing could hit us mid-V1.

**Likelihood:** Low (we're already RLS-isolated and audited), but
non-zero.

**Mitigation:**
- Audit log on every state change makes any future "right to be
  forgotten" / data-subject-request work tractable.
- Naira-native payments + Nigerian SMS regulation already in scope, so
  we're aligned with the current regime by design.

### 11.6 Competitor moves

**Risk:** Bumpa or Selar add vertical-specific features or website-built-
for-you and erode our differentiation.

**Likelihood:** Medium — Bumpa especially has the funding to move.

**Mitigation:**
- Speed to market: ship V1 in 2026 H1, win first-mover narrative.
- Depth of vertical fit: 8 verticals at launch, not 2. Hard to copy at
  scale.
- Conddo Studio (production team) is operational moat — software they
  could copy, an in-country production org is harder.

---

## 12. Open Questions

These are tracked but unresolved. Each has an "owner" — the person who
needs to resolve it before V1.5.

| # | Question | Owner | Why it matters |
|---|---|---|---|
| 1 | Custom domain support (`tenant.com` not `tenant.conddo.io`)? | Eng + Product | Some tenants will want this; implies cert provisioning + DNS automation. |
| 2 | GDPR/NDPR data export ("give me all my data")? | Legal + Eng | May become regulatory before V1.5 ships. |
| 3 | Tenant suspension UX — what does the tenant *see*? | Design + Product | §23 covers backend; FE not designed yet. |
| 4 | Per-seat or flat-rate billing for tenant staff? | Product + Finance | Today: flat-rate per plan; revisit at 100 multi-staff tenants. |
| 5 | RoutePay vs Paystack — pick one, or use both? | Product | Affects V1.5 architecture. |
| 6 | Customer-facing native mobile app — when? | Product | Implicates a year of build; gate on user demand. |
| 7 | Marketplace ambitions — yes/no/when? | Founder | Strategic direction, year 2+. |

---

## 13. Appendix

### 13.1 Competitor landscape (snapshot, 2026)

| | Conddo.io | Bumpa | Selar | Shopify | Wix |
|---|---|---|---|---|---|
| Vertical-specific UI | ✅ 8 verticals | ❌ generic | ❌ generic | ❌ generic | ❌ generic |
| Website built for you | ✅ Conddo Studio team | ❌ DIY | ❌ DIY | ❌ DIY | ❌ DIY |
| Naira-native payments | ✅ Paystack + RoutePay | ✅ | ✅ | ⚠️ requires dollar card for some flows | ⚠️ same |
| Mobile-first | ✅ | ✅ | ✅ | ⚠️ desktop-tuned | ⚠️ desktop-tuned |
| Operations dashboard | ✅ customers + orders + inventory + bookings + analytics | ⚠️ basic | ❌ payment-focused only | ✅ but English/US-tuned | ❌ marketing-only |
| Marketing in-platform | ✅ Naira ad top-up | ⚠️ basic | ❌ | ✅ but USD-priced | ⚠️ basic |
| Pricing tier (₦/month) | 25k / 45k / 80k | 15k / 30k / 60k (est.) | Free + take rate | ~16k+ | ~5k+ |

**Our position:** Most expensive of the local SaaS, but with the most
operations depth + vertical fit + done-for-you website. Strategy is "value
density," not "lowest price."

### 13.2 Pricing tiers (V1)

| Plan | Price (monthly) | Annual saving | Target tenant |
|---|---|---|---|
| **Starter** — ₦25k | Website, CRM, Payments, Analytics | −2 months (₦50k saved) | Solo / very early |
| **Business** — ₦45k | Above + Bookings, Order management, Social scheduler, Email & SMS campaigns, Marketing dashboard | −2 months (₦90k saved) | Growing SME (most popular) |
| **Pro** — ₦80k | Above + Ad management, API access, Advanced analytics, Priority support | −2 months (₦160k saved) | Established SME scaling with ads |

All plans include a 14-day free trial, no credit card required.

### 13.3 Tech stack at a glance

- **Frontend** — Next.js 14, TypeScript, Tailwind CSS, lucide-react
- **Backend** — Spring Boot 3, Java 21, PostgreSQL 16 with RLS
- **Realtime** — Server-Sent Events (Studio side, no WebSocket)
- **Auth** — RSA-256 JWT (platform), HMAC-SHA256 JWT (Studio)
- **Email** — Brevo (primary), Resend (fallback)
- **SMS** — Brevo SMS / Termii
- **Media** — Cloudinary
- **AI** — Anthropic Claude (Sonnet by default, Opus for higher-quality
  work) — inside Studio only
- **Hosting** — Vercel (frontends), Render (backends + Postgres)

### 13.4 Doc map (where to go for what)

- **What is this product?** This PRD.
- **What are we building, technically?** [REQUIREMENTS.md](./REQUIREMENTS.md)
- **How does the API work?** [ACTION_LIST.md](./ACTION_LIST.md) for the
  platform, [conddo_studio_combined.md](./conddo_studio_combined.md) for
  Studio.
- **How do the services talk?** [SERVICE_TOPOLOGY.md](./SERVICE_TOPOLOGY.md)
- **What's the architecture?** [ARCHITECTURE.md](./ARCHITECTURE.md)
- **How do we deploy?** [DEPLOY.md](./DEPLOY.md), [conddo_infrastructure.md](./conddo_infrastructure.md)
- **What's a "vertical" exactly?** [VERTICALS.md](./VERTICALS.md)
- **What's the current FE / BE status?** [FRONTEND_STATUS.md](./FRONTEND_STATUS.md), [BACKEND_STATUS.md](./BACKEND_STATUS.md)

---

*Conddo.io — Built by Handel Cores · For Nigerian SMEs · 2026 · Confidential*
