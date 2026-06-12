# Staff slice 3+4 ship — three follow-up questions

Owner: FE (David) → BE
Re: questions on top of [HANDOFF_2026-06-12.md](./HANDOFF_2026-06-12.md), after `895e016` shipped.

---

## Q1: Should `/staff/roles` return a `moduleAccess` matrix too, or are the two human-readable lines enough?

**A: Yes — return the matrix alongside the existing `permissions` strings.** Both are useful for different things.

```jsonc
{
  "role": "CASHIER",
  "label": "Cashier",
  "permissions": [
    "POS sales (open shifts, run sales, take payments)",
    "Read-only customers, orders, payments, inventory"
  ],
  "moduleAccess": {
    "pos":           "write",
    "customers":     "read",
    "orders":        "read",
    "payments":      "read",
    "inventory":     "read",
    "analytics":     "none",
    "prescriptions": "none",
    "consultations": "none",
    "emr":           "none",
    "loyalty":       "none",
    "discounts":     "none",
    "reminders":     "none",
    "refill_offers": "none",
    "programs":      "none",
    "marketing":     "none",
    "website":       "none",
    "billing":       "none",
    "staff":         "none",
    "settings":      "none"
  }
}
```

Why:
- **FE uses it to hide nav items proactively** — a Cashier never sees an `/analytics` link that would 403 anyway. Cleaner UX than relying on the server to reject.
- **`permissions[]` stays the source of human copy** — what the invite email / accept-invite preview / role-card shows to the user.
- **`moduleAccess` is the machine version** — what the FE binds nav arrays + page-level guards against.

Values: `"write" | "read" | "none"`. `"write"` implies `"read"` (no need for separate flags). If a module is omitted from the map, treat it as `"none"` so adding a new module doesn't retroactively grant access to old roles.

When you wire the `StaffAccess` bean for `@PreAuthorize`, build the same matrix server-side and reuse it both for `/staff/roles` and the SpEL expressions. Single source of truth.

---

## Q2: Brevo template id for the invite email — yes/no?

**A: Yes, route through a Brevo template.**

Template variables I'd suggest, matching the FE catalogue + handoff §6:

```
{{firstName}}        — recipient's first name (from fullName) or null
{{tenantName}}       — "Wellspring Pharmacy"
{{roleLabel}}        — "Cashier" (display label, not enum key)
{{roleAccessLines}}  — bullet list from `permissions[]` joined by \n
{{invitedByName}}    — "Mrs Adebayo" (the owner who clicked invite)
{{acceptUrl}}        — https://app.conddo.io/accept-invite?token=<token>
{{expiresAt}}        — formatted 72h-from-now timestamp
```

Why template > inline:
- Copy is going to change with feedback once real staffers go through the flow. Inline-string changes need a BE deploy; template edits are a Brevo dashboard save.
- We already use Brevo templates for transactional email everywhere else (password reset, verification). Staying consistent.
- Easier to A/B subject lines later — "{{tenantName}} invited you to join as {{roleLabel}}" vs. "You've been invited to {{tenantName}}" without redeploys.

Subject line I'd recommend baking in: **"You've been invited to {{tenantName}} as {{roleLabel}}"**.

Put the template id behind an env var (`BREVO_TEMPLATE_INVITE_ID` etc.) the same way other templates work. Marketing can edit copy + variables; BE just calls `templates/{id}/send` with the params.

---

## Q3: Module-wiring priority for `@PreAuthorize` — confirm or reorder?

**A: Reorder slightly. EMR → Inventory → Customers → Orders/Payments → Analytics → Pharmacy clinical → POS refinement → Marketing.**

Reasoning module by module:

| Step | Module | Why this slot |
|---|---|---|
| 1 | **EMR** | Highest data-sensitivity per access. Patient medical records — a Cashier reading prescription history is a clinical issue, not just an authorization one. Small endpoint surface so it's a fast wire. |
| 2 | **Inventory** | Most-touched module today (already live, not flag-gated). Mixed read/write across all 5 roles. Stock Manager writes, Cashier + Pharmacist + Bookkeeper read, Manager + Owner full. Biggest practical impact per hour of work. |
| 3 | **Customers** | Read patterns differ — Cashier sees phone + name only, Pharmacist sees full clinical context, Bookkeeper sees order history but not clinical. Gates several downstream patterns. |
| 4 | **Orders + Payments + Analytics** | Bookkeeper's primary surface. Pharmacist + Stock Manager read-only. Cashier read-only on orders for "did this customer pay yesterday?" lookups. |
| 5 | **Pharmacy clinical (Rx, consultations, follow-ups)** | Pharmacist + Manager + Owner write. Everyone else no access. Already flag-gated so practical risk is deferred — but get this right before the flags start getting granted broadly. |
| 6 | **POS refinement** | Already `pos` flag-gated. Just need to confirm Cashier writes, Bookkeeper read-only, others no access. Small follow-up. |
| 7 | **Marketing (Loyalty config, Discounts, Reminders, Refill Offers)** | All Owner + Manager write. Discounts approval already admin-only (§12B). Lower urgency since these are flag-gated or admin-bounded already. |

Why **EMR first** (matches your proposal): clinical data is the surface where wrong access translates to real harm fastest. Worth the early focus even though it's flag-gated today.

Why **Inventory above Discounts/Loyalty**: real production traffic. Pharmacy tenants are touching inventory pages multiple times an hour right now. Discounts has approval gating built in already (Beta 12B). Loyalty config is admin-only by intuition anyway.

The earlier you ship steps 1–4, the more existing pharmacy tenants benefit when they invite their first Cashier or Stock Manager. Steps 5–7 are mostly belt-and-suspenders for tenants who haven't been granted the flags yet.

---

## One thing FE will need when matrix lands

When `moduleAccess` ships in the `/staff/roles` response, I'll align the FE's `StaffRoleDef` type to consume it (BE catalogue becomes the source of truth; my local `STAFF_ROLE_CATALOGUE` falls back when offline / older builds). One commit on conddo-app, fully backwards-compatible.

— David / FE
