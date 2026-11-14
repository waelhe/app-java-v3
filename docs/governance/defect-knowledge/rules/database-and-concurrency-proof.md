# Database, migrations and concurrency proof

**Priority:** critical for money, deletion, authorization, inventory/booking and shared mutable state.  
**Triggers:** PostgreSQL, PostGIS, transactions, row/advisory locks, @Version, UPSERT, Flyway, derived state, caches, purge, retries and multi-instance jobs.

**Canonical rules:** B1, B2, B5. **Patterns:** P4, P8, P11, P12. **Evidence:** E-01, E-17, E-24, E-28, E-37, E-38, E-50, E-51.

## Invariants to prove

1. Applied Flyway migrations are immutable. Repair with a forward migration and preserve checksums.
2. Every multi-row or cross-module operation has explicit transaction ownership, retry boundary, stable idempotency identity and recovery path.
3. Enumerate shared rows and lock acquisition order across the entire call graph. A locally consistent method can still form an ABBA deadlock with another method.
4. Keep network/provider work outside database transactions and row locks unless an explicit consistency requirement is documented and measured.
5. Every writer of an optimistic-versioned row participates in the same version protocol, including native SQL, scheduled jobs, migrations and backfills.
6. A set-based UPSERT must reduce source rows to no more than one per target conflict key before updating a row.
7. Purge increments the required version/tombstone and prevents stale writes, replayed events, caches, signed URLs and object-storage reads from reviving data.
8. A concurrency claim is not proven by a mock, SQL substring, or one-thread test. Exercise real PostgreSQL semantics.

## Required proof

- Testcontainers PostgreSQL for locking, transaction isolation, constraints, UPSERT, unique-key races, optimistic locking and multi-threaded idempotency.
- Prove tests fail against the unfixed behavior for the intended reason.
- Run the test through the same CI profile/job that is used as release evidence.
- Cover two operations in both orders, same row/target key, rollback after external effect, stale version, duplicate replay, process restart and rolling deploy overlap where applicable.

## Migration review

For each migration/backfill:
1. Identify data volume, lock duration, transaction size and indexes.
2. State whether the migration is forward-compatible with old and new application versions during rollout.
3. Define pre-deploy, overlap and post-stop reconciliation steps.
4. Make reruns safe or make the one-shot nature explicit and guarded.
5. Report created/updated/skipped/error counts and prove post-condition with a reconciliation query.

## Anti-patterns

- Editing an applied migration.
- Claiming a race is fixed with a mocked repository.
- Taking shared row locks in inconsistent order across methods.
- Holding a row lock across HTTP or provider I/O.
- Native SQL writes a versioned row without incrementing @Version.
- Treating Flyway success as proof that financial or derived data converged.
