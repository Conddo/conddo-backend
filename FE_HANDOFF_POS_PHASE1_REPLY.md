# POS Phase 1 — FE replies to BE open questions

Owner: FE (David) → BE
Re: [FE_HANDOFF_POS_PHASE1.md](./FE_HANDOFF_POS_PHASE1.md) §9.

---

## Q1 — Cashier identity

**A: Logged-in user is sufficient for Phase 1.** Simpler mental model, no extra sign-in friction at shift-open. The "one device, many physical cashiers" pattern is real for some pharmacies but the right time to add it is when a real tenant asks — not preemptively. The model already has room for a `cashierCode` field, so a future migration is non-breaking.

## Q2 — Sale numbering

**A: `S-YYYY-MM-DD-NNNN` per-day-per-tenant is correct.** Reads cleanly on receipts ("today's sale #42"). Continuous counters are nice for accounting but the per-day reset matches how pharmacy receipts already read in NG. Keep it.

## Q3 — Barcode scan endpoint

**A: Yes — add a barcode shortcut.** Critical UX path: cashier hovers over a scanner, scans, product goes straight into cart. Picker UX is for partial-name lookups; barcode should be one round-trip.

**Preferred shape — extend the existing items endpoint:**

```jsonc
// POST /api/v1/pos/sales/{id}/items
// either of:
{ "productId": "uuid", "qty": 2 }
{ "barcode": "6001234567890", "qty": 1 }   // qty defaults to 1
```

Rationale: one endpoint means one code path on the FE (the picker calls `productId`, the scanner calls `barcode`). Errors stay generic — `400 PRODUCT_NOT_FOUND` if the barcode doesn't match anything, then the same `409 INSUFFICIENT_STOCK` for the stock race.

Open vote on whether you want `qty` defaulted to 1 on barcode-only requests — I think yes (scanners typically scan once per unit; cashier clicks `+` afterward if needed).

---

## One thing FE will also ask back

**Receipt printing — do you want the BE `receipt` block to include `change` when payment exceeds total?** I see it in the §3 example response. Confirming: yes, BE computes change server-side and the FE just renders. The math is `Σ(payments) - total` — when positive, that's the change due back. FE will show it on the receipt and on the post-complete screen (so cashier doesn't have to do mental math while a customer waits).

— David / FE
