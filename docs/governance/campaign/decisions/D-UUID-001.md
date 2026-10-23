# D-UUID-001 — Central UUID scalar and deprecated checkout arguments

**Status:** DECIDED by A0 — Option A approved; implementation required  
**Decision date:** 2026-10-23  
**Authority:** A0 / Integration Lead / Contract Steward  
**Candidate branch:** `integration/saleor-full-commerce-20261021`

## Decision

Approve **Option A**: introduce exactly one centrally registered `UUID` scalar and expose the pinned Saleor 3.23.43 deprecated checkout argument aliases in the shared runtime SDL, with real argument plumbing through the existing checkout use case. A0 owns scalar coercion and the single ordered shared-SDL aggregation; domain agents must not define a local scalar or edit `schema.graphqls`.

The authoritative pinned implementation resolves the semantics: in Saleor 3.23.43, `Checkout.token` is a `models.UUIDField(primary_key=True, default=uuid4, editable=False)` ([pinned model source, line 133](https://github.com/saleor/saleor/blob/3.23.43/saleor/checkout/models.py#L133)). The pinned checkout mutation helper resolves `token` via `get_checkout_by_token`, queries the checkout by that same primary-key field, and validates the modern `id` and deprecated identifier arguments together ([pinned helper source, lines 236–298](https://github.com/saleor/saleor/blob/3.23.43/saleor/graphql/checkout/mutations/utils.py#L236-L298)). Therefore `token: UUID` is not an invented second identifier store: it maps to the platform checkout/cart's native UUID identity. The modern `id` form still uses the type-tagged global ID codec.

The earlier Option B assessment was based on an incorrect assumption that the pinned token required a separate lookup/store. The upstream source above disproves that assumption. **This record supersedes the earlier Option B wording in A0's PR comment; the approved decision is Option A.**

## Contract and implementation requirements

1. Define `scalar UUID` exactly once in the shared SDL and register one central GraphQL-Java coercer. It must accept and serialize canonical UUID values and reject malformed values through GraphQL's classified coercion errors. No local scalar aliases and no hand-edited generated classes.
2. In one ordered A0-owned SDL aggregation, add the exact pinned `token: UUID @deprecated(reason: "Use `id` instead.")` arguments and, where the pinned signature includes them, `checkoutId: ID @deprecated(reason: "Use `id` instead.")` to affected checkout-family roots. Keep the exact argument types and deprecation reason.
3. For the currently implemented checkout roots, the domain resolver plumbing must map `id` through `SaleorGlobalIdCodec`, map `checkoutId` through the same typed-global-ID path, and map `token` from the coerced UUID to the native checkout/cart UUID. Every path must call the existing `CheckoutSpi` use cases; do not add a second repository, token store, or state machine.
4. Preserve Saleor's mutually exclusive identifier semantics: reject requests that provide more than one of `id`, `checkoutId`, or `token` for a single operation, even if the values happen to identify the same cart. Do not silently ignore a supplied argument.
5. Authorization remains at the owning use-case boundary. The token path must not bypass anonymous/authentication controls, checkout ownership, ADMIN scope, rate limits, classified errors, or the existing global-ID validation for modern `id`.
6. Apply the same central contract to future checkout roots when their real implementations are assembled. `checkoutDelete(id: ID!)` has no legacy-argument gap and needs no extra aliases. Non-checkout UUID consumers reuse the same central scalar but do not gain invented token behavior; `orderByToken` remains governed by `D-OTKN-001`.

## Alternatives considered

- **A — Central UUID scalar plus exact deprecated checkout aliases, with real resolver plumbing. Accepted.** The pinned source proves that the token UUID is the checkout's primary key, so it can be mapped to the existing native cart UUID without a new store. Existing owner/admin walls remain mandatory.
- **B — Keep the aliases undeclared. Superseded/rejected.** This would preserve an unnecessary signature divergence despite a direct, testable mapping supported by the pinned contract.
- **C — Substitute `ID` for `UUID` independently per agent. Rejected.** It would deviate from the pinned contract and violate the one-SDL-steward rule.

## Required acceptance evidence

- Canonical UUID scalar coercion tests: valid UUID input/output, malformed and non-canonical values rejected.
- Resolver/DGS tests for modern `id`, deprecated `checkoutId`, and deprecated `token` on each implemented affected root; rejection when multiple identifiers are supplied.
- Anonymous, wrong-role, cross-owner/IDOR, guessed-ID and malformed typed-global-ID denial tests. A raw token must never bypass the existing checkout owner/admin policy.
- Real SDL validation and DGS code generation on the exact candidate SHA; inventory signatures must match the pinned signatures for roots claimed as signature-complete.
- A0 updates the central inventory from actual assembled candidate evidence. This decision alone does not mark any root integrated, tested, or release-ready.

## Assembly state and release gate

The decision is now approved, but implementation is **not yet assembled**: PR #698 remains a draft on its agent branch, separate from candidate assembly and formal release review. The central `UuidScalar` coercer and focused unit tests have been added to the candidate branch; the SDL declaration and exact checkout argument signatures will be integrated together in one ordered A0-owned SDL change after the corresponding resolver/fragment update is ready to assemble. No CI run or local Maven test has been observed for the new A0 scalar commit. Until the exact signatures, resolver behavior, tests, and inventory evidence land, the affected rows remain linked to `D-UUID-001` and this gap blocks any full-signature parity or `RELEASE_READY` claim for the affected roots.

## Ledger actions

- The central divergence registry records `D-UUID-001` as `IMPLEMENTATION_REQUIRED`, with this decision as the approved design.
- All 17 currently identified checkout-family inventory rows retain the `D-UUID-001` reference pending signature-complete assembly.
- Implementation statuses are unchanged in this decision commit; no integration or CI result is inferred from the decision.
