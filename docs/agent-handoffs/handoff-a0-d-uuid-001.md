# HANDOFF → A0 — D-UUID-001: the central UUID scalar / deprecated-aliases decision (PR #698, Agent 3)

**Status: PROPOSED — awaiting A0's decision. Filed as ONE request per the
owner's 2026-10-23 03:53Z review: the central `UUID` scalar decision and the
deprecated `token` / `checkoutId` aliases are decided together, centrally, for
every affected root — never per-agent. This agent did not invent a local
scalar, did not claim parity, and did not touch the central
`saleor-458-root-inventory.csv` (A0-owned; A0 updates it on aggregation).**

## 1) The decision A0 is asked to make

Whether, when and how the platform declares the Saleor-pinned `UUID` scalar
and the deprecated argument aliases (`token: UUID @deprecated(...)`,
`checkoutId: ID @deprecated(...)`) centrally in the shared SDL — applying to
EVERY root whose pinned reference signature carries them, in one ordered
SDL-steward change, instead of per-agent invention.

## 2) The measured evidence — the five roots this PR currently exposes

Agent 3's additive SDL
(`marketplace-app/src/main/resources/graphql/agent-3-orders-events.graphqls`)
measured against the pinned reference signatures
(`docs/governance/campaign/saleor-458-root-inventory.csv`, `root_signature`
column, Saleor 3.23.43):

| Root | Pinned reference signature (verbatim) | This PR's SDL today | Gap |
|---|---|---|---|
| `checkout` (Query) | `checkout(id: ID token: UUID @deprecated(reason: "Use `id` instead.")): Checkout` | `checkout(id: ID): Checkout` | `token` alias absent |
| `checkoutLinesAdd` | `checkoutLinesAdd(checkoutId: ID @deprecated(reason: "Use `id` instead.") id: ID lines: [CheckoutLineInput!]! token: UUID @deprecated(reason: "Use `id` instead.")): CheckoutLinesAdd` | `checkoutLinesAdd(checkoutId: ID, id: ID, lines: [CheckoutLineInput!]!): CheckoutLinesAdd` | `token` alias absent; `checkoutId` deprecation tag absent |
| `checkoutLinesUpdate` | `checkoutLinesUpdate(checkoutId: ID @deprecated(reason: "Use `id` instead.") id: ID lines: [CheckoutLineUpdateInput!]! token: UUID @deprecated(reason: "Use `id` instead.")): CheckoutLinesUpdate` | `checkoutLinesUpdate(checkoutId: ID, id: ID, lines: [CheckoutLineUpdateInput!]!): CheckoutLinesUpdate` | `token` alias absent; `checkoutId` deprecation tag absent |
| `checkoutLinesDelete` | `checkoutLinesDelete(id: ID linesIds: [ID!]! token: UUID @deprecated(reason: "Use `id` instead.")): CheckoutLinesDelete` | `checkoutLinesDelete(id: ID, linesIds: [ID!]!): CheckoutLinesDelete` | `token` alias absent |
| `checkoutLineDelete` | `checkoutLineDelete(checkoutId: ID @deprecated(reason: "Use `id` instead.") id: ID lineId: ID token: UUID @deprecated(reason: "Use `id` instead.")): CheckoutLineDelete` | `checkoutLineDelete(checkoutId: ID, id: ID, lineId: ID): CheckoutLineDelete` (root-level `@deprecated("Use checkoutLinesDelete instead.")` already matches) | `token` alias absent; `checkoutId` deprecation tag absent |

`checkoutDelete` is NOT part of this gap: its pinned signature
`checkoutDelete(id: ID!): CheckoutDelete` is an exact match with this PR's SDL.

The same pattern is carried by every not-yet-exposed checkout-family root in
the 458 inventory (`checkoutEmailUpdate`, `checkoutShippingAddressUpdate`,
`checkoutShippingMethodUpdate`, `checkoutPaymentCreate`, `checkoutComplete`,
`checkoutCustomerAttach`, `checkoutCustomerDetach`, `checkoutAddPromoCode`,
`checkoutRemovePromoCode`, `checkoutLanguageCodeUpdate`,
`checkoutDeliveryMethodUpdate`, `checkoutBillingAddressUpdate`, ...) — 20+
rows — so the decision must be central, not repeated per root or per agent.

## 3) The decision request (campaign playbook §10 template)

```text
DECISION ID: D-UUID-001
AFFECTED ROOTS / WORKSTREAMS: the five exposed roots above (Agent 3 / A4, P4
  checkout slice) and every not-yet-exposed root whose pinned signature carries
  `token: UUID @deprecated` or `checkoutId: ID @deprecated` (20+ additional
  checkout-family rows in the 458 inventory). Shared SDL aggregation,
  DGS codegen and the scalar registration site are A0's sole stewardship
  (playbook §4 rules 2/5).
OPTIONS:
  A) Declare the central `UUID` scalar once in the shared SDL
     (marketplace-app/src/main/resources/graphql/schema.graphqls + the
     GraphQL-Java Coercing registration through the platform's existing
     runtime-wiring configuration, in A0's single ordered SDL change), then
     add `token: UUID @deprecated(reason: "Use `id` instead.")` and the
     `checkoutId: ID @deprecated(...)` tags to the affected roots' SDL;
     owning agents follow with argument plumbing only.
  B) Keep the aliases unexposed and record them as a standing divergence in
     the divergence registry until the pinned Dashboard actually sends
     `token`/`checkoutId` on the wire (no SDL change now).
  C) Type `token` as `ID` locally per agent — REJECTED up front: it deviates
     from the pinned Saleor wire contract (UUID is not ID) and multiplies
     per-agent scalars; listed for completeness only.
CONTRACT / SECURITY / DATA CONSEQUENCE: Option A restores wire-contract parity
  with the pinned Saleor 3.23.43 signatures and keeps deprecation metadata
  honest (deprecated aliases stay selectable for old clients). Security is
  unchanged in every option: resolution is by `id` behind the existing
  ownership/ADMIN walls; a `token` path, if ever exposed, would need its own
  authorization review — it is deprecated in Saleor and is NOT exposed here.
  No data consequence: these are request-argument aliases only.
RECOMMENDATION: Option A, landed by A0 in one ordered SDL change per the
  playbook's one-SDL-steward rule; agents never invent the scalar locally.
WHAT CAN CONTINUE WITHOUT THE DECISION: all seven IMPLEMENTED_VERIFIED roots
  and the four line-mutation roots' verified id-based behavior; the PR stays
  open, draft, unmerged; no fake fallback and no silent no-op.
RELEASE GATE AFFECTED: any full-parity claim for the checkout family; P4 wave
  completion; RELEASE_READY for this slice stays blocked until this decision
  lands AND the central inventory is updated (owner decision 2026-10-23).
```

## 4) What this agent did NOT do (the owner's explicit directives)

- No local `UUID` scalar invention and no `ID`-typed stand-in.
- No parity claim for any of the five roots before the decision lands:
  `checkout` stays `IMPLEMENTED_UNVERIFIED`; the four line-mutation roots
  keep their behavior-verified status with this contract-argument gap
  recorded explicitly on their rows in `docs/agent-handoffs/agent-3-roots.csv`.
- No edit to `docs/governance/campaign/saleor-458-root-inventory.csv` — it is
  A0-owned; this request is the proposal channel, not a parallel edit.

## 5) What A0 is asked to update on aggregation (A0-owned, not this agent)

Per the owner's 2026-10-23 03:53Z review, when assembling this PR A0 should
update the central inventory from the current head's evidence:
seven roots `IMPLEMENTED_VERIFIED` (`orderCancel`, `orderBulkCancel`,
`checkoutDelete`, `checkoutLineDelete`, `checkoutLinesDelete`,
`checkoutLinesAdd`, `checkoutLinesUpdate`), `checkout` stays
`IMPLEMENTED_UNVERIFIED`, `orderConfirm` stays `BLOCKED_BY_DECISION` — with
the candidate commit recorded, and this five-root token/deprecation divergence
carried in the divergence registry until D-UUID-001 lands.
