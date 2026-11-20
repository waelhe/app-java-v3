# Async events and reconciliation

**Priority:** high; critical when events can cause money, access, deletion or irreversible state changes.  
**Triggers:** Spring Modulith events, message handlers, webhooks, listeners, outbox/inbox, scheduled jobs, asynchronous workflows, retries and provider callbacks.

**Canonical rules:** B2, B3, B5. **Patterns:** P4, P11, P12, P13. **Evidence:** E-04, E-06, E-26, E-34, E-36, E-50.

## Invariants to prove

1. Assume at-least-once delivery unless the actual transport contract proves otherwise. Design consumers to be idempotent; do not promise exactly-once side effects from a queue or webhook.
2. Persist a stable event/operation identity and a durable receipt/inbox before acknowledging work that cannot safely be retried from the source.
3. Events can be early, late, duplicated and out of order. A stale event must not move a completed/terminal state backwards.
4. Do not infer a prerequisite's completion merely from a missing row or from a sibling event. Model “not happened yet”, “happened with zero value”, “failed”, and “unknown” as distinct states where the domain requires them.
5. Keep listener transaction ownership explicit. An AFTER_COMMIT publication, retryable listener or nested REQUIRED transaction changes the failure model; decide per path and test it. Apply rulebook B2: retryable boundaries must not join the carrier `REQUIRED` transaction, and Modulith events must publish AFTER_COMMIT through `@ApplicationModuleListener`.
6. Do not perform slow provider/network I/O while holding a database transaction or row lock unless a measured consistency requirement justifies it.
7. A 2xx response means the event was processed or durably accepted—not silently discarded. Return retryable failure when no durable acceptance exists.
8. Bound synchronous webhook work by the provider's documented contract. If heavy work is queued, acknowledge only after durable enqueue. Do not copy Saleor's timeout or retry numbers to other providers.

## Review questions

- What is the stable idempotency key and where is it stored?
- Can two events arrive in either order? What does each order leave in the ledger/state?
- Can an event arrive before its aggregate or payment intent exists?
- What happens after the DB transaction rolls back but an external effect already happened?
- Is there a durable outbox/inbox or Event Publication Registry entry to recover from failure?
- Does the handler acquire locks in a global order? Does it call a provider while those locks/transactions are held?
- Does event version/timestamp ordering prevent stale writes and resurrection after erasure?
- Does every subscribed event actually fire in each supported mode/configuration? Subscription acceptance and event delivery are separate claims.

## Required proof

Use integration tests for duplicate delivery, out-of-order delivery, retry after rollback, missing prerequisite, terminal-state monotonicity, durable acknowledgement, and restart/replay. For lock order, use concurrent PostgreSQL transactions and assert the observable state and bounded completion. For event-mode conditions, explicitly create fixtures for each supported mode rather than relying on database defaults.

## Saleor reference — what transfers, what does not

The Saleor webhook-async skill documents raw-body signature verification, payload typing, retry semantics, event ordering and acknowledgment. Transfer the need to verify signatures, understand retry behavior and preserve durable acceptance. **Do not transfer Saleor's specific 20-second timeout, five attempts, or backoff constants** unless the actual provider/transport contract for this platform says the same. See [saleor-ai-adoption-map.md](../saleor-ai-adoption-map.md).

## Anti-patterns

- Assuming webhooks arrive once or in business order.
- Returning success before processing or durable storage.
- Doing slow external work inside a transaction that holds shared-row locks.
- Letting an old callback overwrite a newer terminal state.
- Testing only the expected event order.
- Treating a subscription accepted by an API as proof that the event will be emitted.
