# BE → FE: replies for HANDOFF_2026-06-12 + 2026-06-12b

Owner: BE (Claude) → FE (David)
Re: [HANDOFF_2026-06-12.md](./HANDOFF_2026-06-12.md) (staff sub-roles + invites) and
[HANDOFF_2026-06-12b.md](./HANDOFF_2026-06-12b.md) (Studio feature-flag review queue).

---

## Status — what's shipped

All four slices of the staff-roles handoff plus the Studio feature-flag queue
are merged to `main` and Render is redeploying.

| Slice | Commit | What's live |
|---|---|---|
| 1 — Migration + `/me` + JWT claim | `380dbb0` | `users.staff_role`, `staffRole` JWT claim, `/me.user.staffRole`. Backfill STAFF→MANAGER. |
| 2 — `StaffAccess` bean + module gates | `c1c106d` | The 5×14 matrix is the BE source of truth. Inventory + POS wired. |
| 3 + 4 — Invite acceptance + plan cap | `895e016` | `/auth/invite/preview`, `/auth/accept-invite`, updated invite/PATCH bodies, all error codes. |
| Studio queue (12b) | `0b49e07` | `/api/jobs/admin/platform/feature-flags` (list + grant + revoke). |

---

## Answers

### Q (12 §8) — keep `/staff/roles` as canonical, or let the FE catalogue stay authoritative?

**A: BE is now canonical. Keep your FE catalogue as a UI mirror.**

`StaffService.roles()` now returns the full 5-row catalogue with the same shape your FE-side catalogue uses (`{role, label, permissions[]}`), so your auto-prefer flip should "just work." Why BE-canonical:

- The 403 is what users actually feel. Mirror divergence becomes a Heisenbug — UI says one thing, server says another. Owning both in BE eliminates that.
- The `StaffAccess` bean already encodes the same matrix for the gates. Now `roles()` reads from the same module/permission spec internally, so adding a module (e.g., when we ship the Marketing surface) is a one-line BE change that lights up everywhere.
- A/B copy + localization come for free later — neither was the reason BE became canonical, but they're free now.

Action on your end: when you flip `/staff/roles` preference on, no shape change is needed. The `permissions` array I'm returning is two human-readable lines per role (matches what the invite email uses), not the full module access matrix — let me know if you want the matrix exposed instead. Easy addition.

### Naming nit — `tenant_users` in the handoff

Production table is `users`, not `tenant_users`. V48 + V49 use `users`. Existing tables aren't renamed; just confirming so any FE docs that reference the table by name can stay consistent.

### Plan-gated staff cap (Slice 4 — folded into Slice 3)

Already wired through `BillingService.featureLimit('staff_accounts')`. The error is `409 PLAN_LIMIT_REACHED` with `details:[{field:'limit', message:'<N>'}]` so your existing upgrade-prompt copy fires correctly. Launcher/Growth/Scaler limits live in the existing billing config — no new env vars on Render.

### Email templates (Brevo)

For now I'm sending plain-text bodies inline (matches the existing patterns for password reset + booking notify). The body includes:

```
Hi <name?>,

You've been invited to join <Tenant> on Conddo as a <Role Label>.

Accept the invite and set your password here:
<APP_URL>/accept-invite?token=<token>

This link expires in 72 hours.
```

`APP_URL` is `${conddo.app-url:https://app.conddo.io}`. If you want me to switch to Brevo templated emails with `{{firstName}}` / `{{tenantName}}` / `{{roleAccessLines}}` / `{{acceptUrl}}` so design owns the copy, give me a Brevo template id and I'll wire it. Until then plain-text holds.

---

## Slice 2 — what's wired vs. what's NOT (read this carefully)

`StaffAccess` gates are live on **Inventory** and **POS** only. Every other tenant-side module is still on the old `hasAnyRole(...)` gates, so STAFF still gets blanket write across them. That means:

- A **CASHIER** can still hit prescriptions, EMR, loyalty, programs, etc. until I wire those controllers.
- A **BOOKKEEPER** can still write to those same modules — the matrix says `NONE`, but the gate is the old role check.
- **STOCK_MANAGER + PHARMACIST** are the only sub-roles whose access pattern is currently *correct* for Inventory + POS.

This is FE-graceful (the nav doesn't surface the modules to begin with for the wrong sub-role), but it's not server-enforced yet for the other modules. I'll do the rest module-by-module — each is a 2-line `@PreAuthorize` swap; no contract change for FE. Priority order I'm planning, holler if you want it different:

1. EMR (PHARMACIST + MANAGER + owner write per Beta 4 handoff)
2. Discounts (owner-only approval is already gated; need to re-affirm read access)
3. Loyalty, Reminders, Refills, Followups, Programs
4. (Skip: Staff, Billing — already owner-only)

---

## Studio feature-flag queue (12b) — confirmations

Shipped exactly the contract in §2. A few things to flag:

- **Auth roles:** `hasAnyRole('ADMIN','TEAM_LEAD')` on the list, `hasRole('ADMIN')` on grant/revoke. Matches your §2 spec.
- **Direct grant for tenants with no prior interest row** — I seed a row inline with `status='beta', enabled=true` so ops can pre-grant without bouncing through the tenant. If you'd rather refuse with a "tenant has not requested" error, easy flip.
- **`grantedByName` resolution** — tries Studio staff first, then platform users, returns `null` if neither matches. Matches the qaApprovedByName pattern.
- **No DDL changes** — V39's columns are sufficient. The Studio test schema has `tenant_feature_flags` mirrored in `test-platform-tables.sql` so the Studio test container boots clean.

---

## Open BE questions for FE

1. **Should `/staff/roles` carry the full module access matrix** (so FE can derive per-page nav decisions from a single API call instead of hard-coding STAFF_ROLE_CATALOGUE)? Two human-readable lines per role today; happy to add a `moduleAccess: { inventory: 'write', pos: 'read', … }` block alongside.
2. **Brevo template** for the invite email — yes/no, and template id if yes.
3. **Module wiring priority** above — confirm or reorder.

Reply when convenient and I'll roll the next batch.
