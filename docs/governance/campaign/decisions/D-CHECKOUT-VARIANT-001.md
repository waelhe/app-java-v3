# D-CHECKOUT-VARIANT-001 — CheckoutLine.variant wire shape and visibility

**Status:** A0 decision recorded 2026-10-23; implementation awaiting same-SHA validation.

## Decision

The pinned Saleor 3.23.43 GraphQL contract is the wire authority for checkout: `CheckoutLine.variant` is a **direct, non-null `ProductVariant!`**. The platform must not introduce a `CheckoutVariantReference` wrapper, `availableInCatalog` flag, or nested `productVariant` property into this Saleor-facing field.

The checkout mapper uses a dedicated catalog SPI method, `findVariantForCheckoutLine(UUID)`, only after the orders owner has resolved the checkout through `CheckoutSpi`. That use case is the authorization boundary: only an authenticated cart owner or ADMIN may reach the mapper. The dedicated projection may read a live variant and its live parent regardless of lifecycle state so a purchaser can inspect a variant already present on their own cart. This does **not** change the two-row ACTIVE visibility gate on the public `productVariant` / `productVariants` roots.

## Integrity and non-fabrication rules

- A missing or soft-deleted variant, a missing or soft-deleted parent, or a legacy product-level cart line with no variant UUID cannot be represented truthfully as `ProductVariant!`. The resolver fails closed with a GraphQL error; it must not synthesize a UUID, SKU, name, parent, or a successful null payload.
- A cart line that references a suspended/archived but still-live variant is resolvable through the checkout-context projection; lifecycle state is returned as the existing platform extension field.
- Public catalog reads remain permission-aware and continue to hide non-ACTIVE variants or variants whose parent is not ACTIVE for non-ADMIN callers.
- This is a current live catalog projection, not a historical snapshot. A separate immutable line snapshot / retention policy is required before the system can guarantee that every legacy or stale cart line remains renderable indefinitely.

## Required evidence before clearing the checkout output divergence

1. SDL/codegen proves `CheckoutLine.variant: ProductVariant!` with no wrapper type.
2. Unit tests prove suspended variant/parent projections are available in checkout context while public catalog reads remain hidden.
3. Mapper tests prove the direct generated `ProductVariant` object is emitted and missing/null references fail closed.
4. Real GraphQL tests prove an authenticated owner and ADMIN can read valid checkout variant data, while anonymous and wrong-owner requests are rejected before the catalog projection is invoked.
5. CI, Integration Test, CodeQL, container/security checks, and Saleor inventory/gates pass on the same assembled head.

Until the edge states in the first integrity bullet are eliminated by data migration/backfill or a durable snapshot contract, the checkout family must not be claimed as universally `RELEASE_READY` for every legacy cart row.
