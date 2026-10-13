# ADR-0010: the mixed-seller cart splits into one order per seller — the ADR-0002 multi-seller deferral opens (Plan D-08 completion)

- **Status:** Accepted (owner-ordered execution — the ADR-0002 deferral opened per the owner's standing order, governed by the official documentation exclusively)
- **Governing references (official documentation exclusively):**
  - Spring Modulith reference — module boundaries (the split is an intra-orders-module composition; no new cross-module contract exists)
  - Spring Data JPA reference — transactional composition (the split rides the ONE placement transaction; `@Modifying` conditional writes unchanged)
  - The existing contracts — `ProductPricingPort`/`ProductStockPort`/`OrderPaymentPort`/`OrderSellerPort` REUSED verbatim per order, never reimplemented
  - RFC 9110 / the OpenAPI compatibility gate's additive-only rule — the wire change is an ADDITIVE response field, no client break, no gate entry

## The decision

1. **The split is a cart-level fact; the order keeps its single seller.** `orders.seller_id` stays single-valued per order and the ledger's per-seller settlement (`OrderSellerPort.sellerOf`) stands UNCHANGED — the placement GROUPS the cart by its sellers (first-seen line order preserved, a `LinkedHashMap`) and creates ONE ORDER PER SELLER, each with its own derived total, its own single currency, its own frozen lines, and its own payment intent (`order-{orderId}`), cancel/refund/settlement path. No commerce engine, no split engine — the same contracts, composed.
2. **The atomicity is whole-cart.** The stock reservation stays ONE `ProductStockPort.reserve` call carrying EVERY line of every group — a zero row anywhere answers the house 409 and rolls the WHOLE placement back (no order row in any group, no partial hold). The cart tombstones exactly once. PLACED still publishes nothing (the A-03 discipline).
3. **One currency per order.** A seller group whose re-priced lines carry mixed currencies answers the honest 409 (the ADR-0002 stale-currency contract applied per group) — a single order cannot freeze two currencies.
4. **The wire is additive-only.** The placement response body remains the first group's order; the sibling orders ride the NEW `additionalOrders` field (an empty list on the single-seller placement and on every other read). No field is removed, no shape changes, no client breaks — the OpenAPI compatibility gate needs no entry. This is the same "without a wire break" discipline ADR-0002 recorded (the legacy caller-supplied amount fields), applied to a genuinely richer result.
5. **ADR-0002's deferral clause is superseded.** The "mixed-seller carts answer 409" behavior and its literal message are retired; the guard test becomes the split test; ADR-0002 remains the immutable record of the stage it closed, and this ADR is the documented opening of its own named deferral.

## Consequences

- A buyer can fill one cart from many stores and place once; each seller's order confirms, fulfills, cancels, refunds, and settles on the EXISTING per-order machinery — nothing about a single-seller order changed.
- The unit gate proves the split (two sellers → two orders with their own totals/sellers/frozen-line ids, ONE whole-cart reserve, one tombstone, zero events) and the per-group currency 409; the journey gate proves it over the REAL chain (two sellers provisioned over HTTP, the 201 body + `additionalOrders[0]`, the two rows with their sellers set, the whole-cart reservation landed, and two independent payment intents).
- The cart's add/read surfaces are untouched; only the placement's result is richer.
