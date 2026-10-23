# HANDOFF → A0 — D-UUID-001: the central UUID scalar / deprecated-aliases decision (PR #698, Agent 3)

**Status: RESOLVED — A0 decided D-UUID-001 on 2026-10-23 as Option A
(IMPLEMENTATION_REQUIRED). Decision record:
docs/governance/campaign/decisions/D-UUID-001.md on the integration branch
(commit 0e2e8a4 + the divergence-row follow-up edeb08c and the security-gate
follow-ups). The pinned Saleor 3.23.43 source proves Checkout.token is the
checkout's UUID primary key, so A0's earlier Option B wording was corrected:
the approved decision is Option A — one central UUID scalar plus the exact
deprecated checkout-family aliases, landed by A0's single SDL stewardship.
Agent 3's resolver/fragment plumbing for the five implemented roots landed in
round 9 (commit 369dd78); the token: UUID argument declarations and the
central scalar UUID land in A0's one ordered shared-SDL change at assembly.
This request is retained below as the filed input to the decision.**

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
CONTRACT / SECURITY / DATA CONSEQUENCE (updated 2026-10-23 post-decision —
  supersedes the filed wording): Option A restores wire-contract parity
  with the pinned Saleor 3.23.43 signatures and keeps deprecation metadata
  honest (deprecated aliases stay selectable for old clients). The filed
  claim that "security is unchanged in every option" and that a token path
  "is NOT exposed here" described the PRE-decision state and no longer
  holds: Option A is ADOPTED and the resolver plumbing DOES accept the
  deprecated token alias. As implemented (369dd78 + a33d248 + c5048c19f),
  token resolves to the checkout's native UUID and converges on the SAME
  CheckoutSpi use case behind the SAME authentication wall and the SAME
  ownership/ADMIN checks as the modern id path — proven by the three-layer
  evidence (the CheckoutDataFetcherTest acceptance matrix including the
  blank-identifier regression matrix, the two anonymous token legs in
  GraphQlSecurityIntegrationTest, and the CheckoutGraphQlIntegrationTest
  ownership-wall legs over real HTTP and schema); supplying more than one
  identifier is rejected even when the values denote the same cart, and a
  supplied-but-blank identifier is rejected outright. No data consequence:
  these are request-argument aliases only; only the central SDL declaration
  (the shared scalar UUID plus the exact pinned signatures) still pends in
  A0's one ordered shared-SDL change.
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

The authoritative per-root statuses and their carrying-head evidence are
maintained in `docs/agent-handoffs/agent-3-roots.csv` on the branch head —
read them from the CSV at aggregation time (round 10, per the A0 07:14Z
review, temporarily returned the four line-mutation roots to
`IMPLEMENTED_UNVERIFIED` and restored them to `IMPLEMENTED_VERIFIED` once the
blank-identifier fix head `c5048c19f`'s own gates were read green — CI
39917430799, Integration Test 39917433599, Inventory 39917406804;
`checkout` stays `IMPLEMENTED_UNVERIFIED` with the two output-contract gaps of
that review recorded on its row).
