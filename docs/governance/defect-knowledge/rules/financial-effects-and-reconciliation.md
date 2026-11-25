# Financial effects and reconciliation

**Priority:** critical.  
**Triggers:** PSP/provider calls; capture, authorization, refund, void, commission, settlement, seller/provider balances, gift-card plus PSP split tender, retry, webhook, reconciliation or financial migration.

**Canonical rules:** B2, C2. **Patterns:** P4, P11, P12. **Evidence:** E-08, E-33…E-42, A1–A5 in the review index.

## Invariants to prove

1. The provider is authoritative for what happened externally; the platform is authoritative for its own durable ledger and business state. Neither an HTTP 2xx nor an exception alone proves the final financial outcome.
2. Create one stable platform operation reference before calling a provider. Reuse it across retries and event reconciliation. Keep the platform reference, provider object ID, provider-account scope, order/payment identity, and funding-source identity distinct.
3. A timeout or ambiguous response is **unknown**, not failed. Preserve a pending/reconcilable state; do not create a second charge/refund while the original operation may still complete.
4. Verify provider object status, amount, currency, object ID, and relationship to the original transaction before finalizing local state.
5. A reversal mirrors the amount actually posted by the original operation. Do not recompute old commissions or refunds from today's pricing/rate/configuration.
6. Each transaction represents one financial operation against one funding source. Split tender is represented as separate linked operations/legs; never silently combine gift-card value and PSP money into one opaque operation.
7. Event arrival order is not guaranteed. Distinguish a legitimate zero amount from a not-yet-posted prerequisite; retries must be durable and idempotent.
8. Every ledger writer—including native SQL, migrations, backfills and reconciliation jobs—preserves unique keys, optimistic versions, currency grouping, sign semantics and auditability.
9. Reconcile historical rows and writes overlapping a rolling deploy. A migration completing is not proof that financial state converged.

## Review questions

- Is the amount/currency taken from the authoritative action/provider object, not a browser total or recomputed cart?
- Is there a stable idempotency key before external I/O? Is it independent of mutable state?
- What happens if the provider commits but the response is lost?
- Can REFUNDED arrive before COMPLETED/settlement? Can duplicate/late events change a terminal state?
- Does a reversal use the exact posted debit? What about legacy rows with no stored rate, zero rate, and rounded-to-zero commission?
- Can a multi-row backfill hit the same UPSERT conflict key twice? Does each SQL writer increment @Version?
- Do aggregate queries classify every new entry type by accounting sign?
- Is the recurring job bounded to outstanding eligible work and backed by an explainable index?
- Do tests cover the real database behavior, not just mock order or SQL text?

## Required proof

For DB-backed paths, prefer real PostgreSQL/Testcontainers. Prove duplicate delivery, same stable reference, provider timeout/unknown result, reordered events, zero values, legacy historical records, two concurrent writers, native-SQL versus ORM write, retry after rollback, and migration/backfill overlap.

For any claim of fixed, the receipt must contain the repair commit reachable from the relevant PR head and a completed successful GitHub Actions run on that exact head. A merge to main does not mean the commerce integration candidate contains the fix.

## Saleor reference — what transfers, what does not

The Saleor saleor-app payment-app skill states the useful general pattern: stable attempt reference, distinct provider identities, unknown outcomes preserved, provider-object verification, idempotent reconciliation, and one operation per funding source. The platform adopts these **invariants**, not Saleor's App protocol, Next.js SDK, manifest, or payment-app runtime. See the source/pin record in [saleor-ai-adoption-map.md](../saleor-ai-adoption-map.md).

## Anti-patterns

- Treating timeout as failure and starting a fresh charge.
- Trusting checkout/browser totals over the amount on the committed operation.
- Refunding through current channel configuration instead of the original provider account/object.
- Recomputing historic commission from the current rate.
- Treating a missing ledger row as proof of a zero commission without checking the settlement prerequisite.
- Marking a financial action complete because an HTTP response succeeded.
- Declaring a fix from unit tests when the failure is a PostgreSQL race or transaction boundary.
