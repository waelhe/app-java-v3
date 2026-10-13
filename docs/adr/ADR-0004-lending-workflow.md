# ADR-0004: the lending workflow — the offer projection, the loan machine, and the money through the existing engine (Plan D-09)

- **Status:** Accepted (owner-ordered execution of the unified plan, stage 8 — the reserved financial decision opened per the owner's standing order, designed strictly on the official mechanisms)
- **Decision owners:** Owner (the plan's D-09 row — the financial/legal gate) + backend execution
- **Governing references (official documentation exclusively):**
  - PostgreSQL documentation — `CREATE TABLE … EXCLUDE` (the GiST exclusion constraint over `tstzrange`, the official serialization point for «two competing borrowers cannot hold the same period»), `btree_gist` contrib (the V41 house precedent)
  - Spring Data JPA reference — pessimistic `@Lock(PESSIMISTIC_WRITE)` (the transactional serialization read) and conditional derived queries
  - Spring Modulith reference — Events (`@ApplicationModuleListener`, AFTER_COMMIT, REQUIRES_NEW)
  - The existing payments engine (Stripe channel, webhook inbox, idempotency keys, refunds) and ledger — REUSED, never reimplemented

## Decisions

1. **D-09 (the financial shape):** lending is a **fee-for-period rental, never escrow** — the fee's intent is an ordinary payment intent on the EXISTING engine under a new `LOAN` origin (the additive widening the `ORDER` origin received in ADR-0002: the origin CHECK and its pairing invariant rewritten in place, `NOT VALID`). No funds are held in trust by the platform; the words «escrow»/«ضمان» appear nowhere in the model. The deposit is recorded as the owner's declared terms (`deposit_minor`) — its collection is a follow-up payment leg, documented, not silently modeled.
2. **The item is the storefront's Product** (catalog owns product identity — no parallel item record). The lending module owns ITS OWN offer projection (`lending_offers`: one per product, the owner's daily fee + optional deposit — the plan's «إسقاط الإعلان/التوفر منفصل عن معاملة الإعارة المؤكدة») and its own confirmed transactions (`loans`).
3. **The period's exclusivity is layered:** the request/approval transaction locks the offer row FOR UPDATE (the friendly 409 with the message), the overlap query re-checks, and the partial `EXCLUDE USING gist` constraint over the LIVE statuses is the race's backstop (a closed/cancelled loan releases its period for rebooking).
4. **The fee is never caller-supplied** (the ADR-0002 amount-source lesson applied): the owner's daily rate × the period's days (ceiling — a partial day rents the whole day), computed at request time, frozen on the loan.
5. **The machine:** `REQUESTED → APPROVED → ACTIVE → RETURN_REQUESTED → RETURNED → CLOSED`, with `DECLINED`/`CANCELLED` terminal escapes and `DISPUTED` freezing an ACTIVE loan's edges. Every transition is guarded in the service (the single writer), stamped, audited by Envers, and — where a cross-boundary consumer exists — event-carrying (`LoanRequestedEvent` → the owner's decision gate; `LoanApprovedEvent` → the borrower's payment gate; `LoanCancelledEvent` → the money + the receipt; `LoanClosedEvent` → the lifecycle's terminal fact). No event without a listener (the A-03 discipline).
6. **The money legs reuse the ONE contracts:** payment COMPLETED marks the loan paid (`LendingPaymentListener`, the order auto-confirm twin, idempotent by the state guard); cancellation settles through the payments module's own listener (`LoanCancelledEventListener`, the `OrderCancelledEventListener` twin); the ledger credits the owner with the SAME announced commission rate through `LoanOwnerPort` (the `OrderSellerPort` twin).
7. **Damage/dispute:** the loan-side `DISPUTED` state and its gate exist; the DISPUTE OPENING against loans is the documented deferral — the existing disputes module is booking-keyed (`disputes.booking_id NOT NULL`), and generalizing its subject is a dedicated contract change (a `DisputeOpenPort` seam), owned and sequenced, not silently skipped.

## Consequences

- The identity gates ride the existing roles: the borrower is a CONSUMER, the owner is the product's PROVIDER; the owner cannot borrow their own item; the loan's reads answer the party gate (borrower/owner/ADMIN) with the honest 404.
- Late returns are recorded at settlement time (the return confirmation stamps `returned_at` against the period's end) — the fee adjustment rules are the owner's documented policy, deferred with the dispute seam.
- The lending module rides the house dependency set only (`shared-api`, `shared-security`, `shared-jpa`) — Modulith-verified.
