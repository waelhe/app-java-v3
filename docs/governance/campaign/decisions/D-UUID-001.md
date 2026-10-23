# D-UUID-001 — Central UUID scalar and deprecated checkout arguments

**Status:** DECIDED by A0 — Option B for the current checkout slice  
**Decision date:** 2026-10-23  
**Authority:** A0 / Integration Lead / Contract Steward  
**Candidate branch:** `integration/saleor-full-commerce-20261021`

## Decision

Do **not** add `scalar UUID`, `token: UUID`, or deprecated `checkoutId: ID` aliases to the shared runtime SDL for the checkout-family roots in this campaign step. Record the signature gap as an approved, explicit divergence. Continue to expose only the implemented modern `id` contract until the legacy argument semantics are backed by an actual owner and security-tested behavior.

This is Option B from the agent handoff. Option A is **not** approved for the current checkout slice because adding the arguments without real semantics would either create unused arguments (forbidden by frozen-contract §3.4) or tempt a fabricated mapping from a raw UUID token to the platform's cart primary key. The platform checkout SPI currently resolves a typed `Checkout` global ID through `SaleorGlobalIdCodec` and enforces the existing owner/admin boundary; it has no Saleor legacy checkout-token lookup/store. A raw `token` must not be reinterpreted as a cart ID or treated as an authorization credential.

The deprecated `checkoutId` alias is held with the same checkout argument family in this decision so the shared contract is changed only as one A0-owned revision, not piecemeal by domain agents. This is a deliberate wire-signature divergence, not a claim of full Saleor signature parity.

## Scope

The checkout-family roots whose pinned signatures carry `token: UUID` and/or deprecated `checkoutId: ID` are recorded as affected in the central inventory under `D-UUID-001`. This includes roots already present in Agent 3's SDL fragment and roots not yet implemented. `checkoutDelete(id: ID!)` has no such gap and is out of scope.

The Saleor inventory also contains non-checkout UUID arguments (notably transaction operations) and `orderByToken`. This decision does **not** authorize agents to define UUID scalars or transaction-token behavior locally. No shared UUID scalar is added merely for the currently omitted checkout aliases. Before any supported non-checkout root needing `UUID` is exposed, A0 must make the single central scalar/coercing decision together with its real owner, wire semantics, authorization/error behavior, and tests. `orderByToken` remains governed by `D-OTKN-001`.

## Alternatives considered

- **A — Declare UUID centrally and expose all deprecated checkout aliases now. Rejected for this slice.** The schema signature could be made to look closer to the pin, but the platform cannot honestly execute the checkout-token path. Adding unused arguments or equating token and cart ID would violate the frozen contract and could create an unreviewed access path.
- **B — Keep checkout aliases undeclared and record the exact divergence. Accepted.** This preserves the real ID-only behavior without invented token lookup. The central steward records the divergence; modern-ID work can proceed.
- **C — Let each agent substitute `ID` for `UUID`. Rejected.** It is not the pinned contract and fragments scalar ownership across workstreams.

## Required controls

1. Domain agents must not edit `marketplace-app/src/main/resources/graphql/schema.graphqls`, create a local UUID scalar, or claim parity for the deprecated checkout arguments.
2. No argument may be added to the runtime SDL unless its runtime behavior is implemented or it is explicitly rejected with a stable, tested error; silent no-ops are prohibited.
3. Reopen this decision only when a real checkout-token owner/contract exists or an audit of the exact pinned Dashboard operations demonstrates a requirement. Any reintroduction must be one ordered A0 SDL change, with owner/admin and anonymous/IDOR tests, conflicting-ID cases, runtime SDL/codegen validation, and exact root-inventory evidence.
4. This decision approves the divergence record; it does **not** certify the affected roots as integrated or tested, and it does **not** waive the release gate. Full-signature parity for this argument family remains release-blocking unless the release owner explicitly excludes it from the release scope in writing.

## What can continue

Modern `id`-based checkout behavior and unrelated workstreams can continue against their existing owner/security boundaries. No UUID scalar or deprecated checkout arguments are needed to validate the currently implemented ID-based cart operations.

## Ledger actions in this decision commit

- Add `D-UUID-001` to the central divergence registry as `APPROVED` with this record as the decision evidence.
- Add the `D-UUID-001` reference to every affected checkout-family row in the central 458-root inventory.
- Do not change implementation statuses or claim candidate integration: PR #698 remains a draft on its agent branch, separate from the candidate assembly and release review.
