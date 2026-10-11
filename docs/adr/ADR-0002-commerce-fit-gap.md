# ADR-0002: Commerce fit-gap closure — the single product identity, inventory, and the checkout loop (Plan D-08)

- **Status:** Accepted (owner-ordered execution of the unified plan, stage 6)
- **Decision owners:** Owner + architecture (per plan §3.2 D-08; opened by owner order "open the reserved decisions, governed by the official documentation only")
- **Governing references (official documentation exclusively):**
  - Spring Boot reference — Data › JPA (modifying queries, transactions)
  - Spring Modulith reference — Events (`@ApplicationModuleListener` AFTER_COMMIT, REQUIRES_NEW)
  - Spring Data JPA reference — repository query techniques (derived queries, `@Modifying`)
  - Stripe payment lifecycle via the EXISTING payments engine (webhook inbox, idempotency keys) — no parallel engine
- **Fit-gap ledger (plan §7.2 items vs measured code):**

| Plan §7.2 item | Measured state before this ADR | Resolution |
|---|---|---|
| Store owner & staff access | Provider + `StorefrontController` + `StoreCategory` (V70/V116) | Reused as-is |
| Product identity / price / currency | `Product` root with authoritative `priceMinor`/`currency` (A-17) | Reused as-is |
| SKU/variants | Not present; per-category attributes exist (`CategoryAttribute`) | Variants remain out of scope — recorded as deferred capability, not a gap |
| **Inventory** | **Absent — no stock columns** | **Closed: V172 adds `stock_quantity`/`reserved_quantity` with CHECK ≥ 0** |
| Display state (show/hidden/suspended) | **Absent — no product lifecycle** | **Closed: V172 adds `product_status` (ACTIVE/SUSPENDED/ARCHIVED), storefront reads ACTIVE only** |
| Cart | `Cart`/`CartItem` (V113 partial-unique ACTIVE cart) | Reused; **the caller-supplied amount is demoted to optional-and-ignored** (marked `deprecated` in the schema) — the authoritative product price is the only amount source. The fields stay in the request schema because the OpenAPI compatibility gate's own design is fail-closed on request-body breaks (unrepresentable in its (path, status) allowlist); behavior restores the single source of truth without a wire break |
| Checkout + sustainable snapshot | `place()` freezes lines (order truth) | Reused; placement now also **reserves stock** |
| **Stock reservation / concurrent purchase safety** | **Absent** | **Closed: atomic conditional `@Modifying` UPDATE — the row-lock conditional update is the PostgreSQL/JPA-official mechanism; loser answers 409** |
| Payment-intent cycle | Engine complete (Stripe channel, webhook inbox, idempotency, refunds) — **not linked to orders** | **Closed: `OrderPaymentPort` seam; `payment_intents.order_id` (plain UUID, cross-module no-FK discipline); auto-confirm listener on `PaymentStateChangedEvent`** |
| Ledger / disputes | `marketplace-ledger`, `marketplace-disputes` consume existing events | Reused as-is — no parallel engine (plan's hard rule) |
| Delivery / fulfillment | `FULFILLED` terminal state commits stock | Reused |
| Notifications | `OrderConfirmedEvent`/`OrderFulfilledEvent`/`OrderCancelledEvent` consumed | Reused |
| Verticals (vehicles/realestate/jobs/services) | `marketplace-realestate`, `marketplace-jobs` modules + per-category dynamic attributes | Vertical attributes ride the existing category-attribute dictionary — no parallel tables |

## Decisions

1. **D-08 (owner of product/store/cart/order):** the **catalog module owns product identity and inventory**; the **orders module owns cart/order/checkout**; the **payments module owns the payment-intent cycle** — the plan's "one source of truth" rule applied to the measured modules. No new commerce engine is created anywhere.
2. **Money:** minor-unit `long` columns end-to-end (existing house convention); no float anywhere.
3. **Concurrency:** stock reservation is a single conditional UPDATE per line (`UPDATE … SET reserved = reserved + :q WHERE stock - reserved >= :q AND status = 'ACTIVE'`) executed inside the placement transaction — two competing buyers cannot both reserve the last unit; the failed one answers the house 409. Release on cancel, deduct-on-commit at fulfill.
4. **Payment linkage:** placement reserves stock and freezes lines; the client then requests the order's payment intent (`POST /api/v1/orders/{id}/payment-intent`, deterministic idempotency key `order-{orderId}`); the settlement's COMPLETED state auto-confirms the PLACED order through the existing `PaymentStateChangedEvent` (an event that already had consumers — no new event invented; a state-guarded idempotent listener — the booking auto-confirm pattern verbatim).
5. **Cancellation money:** the `OrderCancelledEvent` (existing) gains a payments-side listener — the `BookingCancelledEventListener` twin — that cancels the unpaid intent or fully refunds the collected one through the ONE refund contract (`autoRefundByOrder`, the `autoRefundByBooking` twin). No refund path is reimplemented anywhere.
6. **Seller attribution:** the order stores its single seller at placement (mixed-seller carts answer 409 — the multi-seller split is the documented deferral); the ledger's ORDER-origin settlement credits the seller through the `OrderSellerPort` seam (the `BookingParticipantProvider` twin) plus the SAME announced commission rate configuration.
5. **Cancellation:** a paid order cancels through the existing refund path (full refund via the payments module's own capability); an unpaid intent is cancelled — both via the payments module, never reimplemented in orders.
6. **Commission:** none introduced — the plan's last-owner-decision note is honored; the ledger records what the payment engine already records.

## Consequences

- Storefront/search/feed surfaces only ever read ACTIVE products (the cancelled/sold-out/suspended/hidden surface rule).
- `PLACED` still publishes no event (the A-03 discipline — placement is the buyer's own write); the machine's cross-boundary information still begins at CONFIRMED.
- Every new column is additive; no applied migration is modified (V172 only).
