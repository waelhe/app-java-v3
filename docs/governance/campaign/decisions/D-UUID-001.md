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

## Security evidence gate — required before enabling token access

A0 reviewed the upstream Saleor issue [#18878: Additional verification process for anonymous retrieval of orders and checkouts](https://github.com/saleor/saleor/issues/18878), opened 2026-02-27 and still marked open/triage at the time of review. It raises a material concern: a UUID alone is not an authentication control, and an account-owned checkout may be retrievable anonymously when its UUID is known. The issue is a maintainer discussion, **not an adopted upstream policy**, so it does not change the pinned wire signature; however, it is a credible security signal that must be addressed in this platform.

Accordingly, distinguish the two parts of Option A:

- **Schema compatibility:** approve one central `UUID` scalar and the exact pinned deprecated argument signatures.
- **Token execution / security approval:** do not enable `token` resolution for anonymous or non-owning principals until the platform's pre-login GraphQL security contract explicitly defines whether guest checkout tokens are allowed, how account-owned checkouts are protected, and how guessed/leaked UUIDs are prevented from disclosing or mutating another user's cart.

The checkout security tests must cover at least: anonymous guest checkout allowed by policy, anonymous access to account-owned checkout denied, authenticated wrong-owner access denied, ADMIN behavior, malformed/guessed UUID, token/id conflicting identifiers, and no leakage in error payloads. If the current application use case cannot enforce these distinctions, the alias may be declared for contract compatibility but must not be wired to a resolver that weakens the established security boundary. Record the chosen policy and test evidence before closing `D-UUID-001` or claiming release readiness.

## Implementation progress — 2026-10-23

A0's central-SDL implementation branch now declares `scalar UUID` exactly once in
`marketplace-app/src/main/resources/graphql/schema.graphqls`. The matching
runtime coercer and its canonical parsing/serialization tests already exist as
`UuidScalar.java` and `UuidScalarTest.java`; this change connects the
approved scalar contract to the shared SDL. The implementation is tracked in
[A0's central-SDL PR](https://github.com/waelhe/app-java-v3/pull/707).

**This is a partial implementation, not closure of D-UUID-001.** The affected
checkout argument signatures are defined in Agent 3's additive SDL on PR #698,
which has not yet been integrated into the candidate branch. The five shipped
checkout roots and the remaining pinned checkout-family rows must not be marked
signature-complete until the central scalar and that SDL are present together
on the same integration SHA and runtime schema / resolver tests pass.

The token authorization gate is also still distinct from schema compatibility:
the approved scalar signature does not authorize anonymous or non-owning
access. Before claiming release readiness, preserve the explicit pre-login
policy and guest-vs-account, wrong-owner, ADMIN, guessed/malformed UUID and
conflicting-identifier evidence required below. The divergence row therefore
remains `IMPLEMENTATION_REQUIRED`, and the checkout root inventory is not
promoted to complete by this SDL-only change.

## Required acceptance evidence

- Canonical UUID scalar coercion tests: valid UUID input/output, malformed and non-canonical values rejected.
- Resolver/DGS tests for modern `id`, deprecated `checkoutId`, and deprecated `token` on each implemented affected root; rejection when multiple identifiers are supplied.
- Anonymous, wrong-role, cross-owner/IDOR, guessed-ID and malformed typed-global-ID denial tests. A raw token must never bypass the existing checkout owner/admin policy.
- Real SDL validation and DGS code generation on the exact candidate SHA; inventory signatures must match the pinned signatures for roots claimed as signature-complete.
- A0 updates the central inventory from actual assembled candidate evidence. This decision alone does not mark any root integrated, tested, or release-ready.

## Assembly state and release gate

The A0 assembly branch is stacked above Agent 3's implementation and now contains the single central `scalar UUID` declaration, the `UuidScalar` coercer plus registration/SDL unit assertions, and the exact deprecated `token: UUID` input alias on the five implemented checkout roots. It also exposes the pinned non-null `Checkout.token: UUID!` output and maps it to the cart's native UUID. The authenticated-owner token lookup and anonymous pre-login denial now have explicit real-HTTP integration tests on this combined branch. The `checkoutId: ID` aliases carry the pinned deprecation directive where required.

**Execution status:** these code and tests are committed on the stacked A0 branch, but Maven CI/Integration Test have not been run against the combined head because the available GitHub actions connector does not expose workflow dispatch and the container cannot reach GitHub to clone/run the checkout locally. The latest independent scalar-only PR's canonical governance and Snyk checks passed, but those results must not be attributed to this different combined SHA. The central root inventory has not been promoted to verified status. The divergence stays `IMPLEMENTATION_REQUIRED` until codegen/runtime SDL, resolver behavior, security matrix, and inventory are validated together on one candidate SHA.

**Independent output blocker:** Saleor's pinned contract defines `CheckoutLine.variant: ProductVariant!`, whereas the current implementation intentionally emits `CheckoutVariantReference!` to avoid fabricating catalog fields when a cart line outlives visibility. This branch preserves that honest adaptation and does not claim full Saleor output parity or `RELEASE_READY` while that projection lacks an approved historical variant model.

## Ledger actions

- The central divergence registry records `D-UUID-001` as `IMPLEMENTATION_REQUIRED`, with this decision as the approved design.
- All 17 currently identified checkout-family inventory rows retain the `D-UUID-001` reference. The five currently exposed roots on the stacked assembly branch are `IMPLEMENTED_UNVERIFIED`; no root is promoted to verified or release-ready without same-SHA CI/integration evidence.
- The divergence remains `IMPLEMENTATION_REQUIRED`; this branch records the assembled implementation and pending verification rather than inferring integration or CI success.
