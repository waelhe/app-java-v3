# Review Protocol — independent adversarial review standard

> The review stage evaluates the change **independently of the implementing agent's self-assessment**. It complements CodeRabbit (which stays the standing automated independent reviewer, D6) — this protocol is what a human or AI reviewer applies on top, using the diff, the governing requirement, the pattern catalog, and independently meaningful tests. The implementing agent saying "done" is never evidence.

## Finding standard (every material finding carries all six)

1. **Triggering scenario** — concrete: input/sequence/concurrency/actor that exhibits it.
2. **Violated invariant or requirement** — named (a rulebook ID or a governing-file clause).
3. **Evidence** — file:line in the diff, contract clause, or test gap; no "might be an issue" without a mechanism.
4. **Impact and severity** — what breaks, for whom, how badly.
5. **Status** — confirmed / suspected / unverified (labels travel with the finding).
6. **Regression test or control** where appropriate — the finding closes with a test, a guard, or a registered accepted-risk.

## The adversarial checklist (search, don't verify)

Ordered by the historical yield of each search (see pattern-catalog for the cases behind each):

1. **Missing failure paths** — what happens when the dependency/DB/provider/refuse? (P1: E-02, E-16)
2. **Wrong state transitions** — is an invalid/terminal transition possible? Are terminal states actually terminal? (P4 family)
3. **Incomplete enforcement across alternate entry points** — enumerate the doors the change did *not* touch (P5 — historically the highest-yield search).
4. **Race conditions and stale-data writes** — concurrent writers on shared rows; read-modify-write windows (E-17); purge-vs-writer races (`version = version + 1` shape).
5. **Identity and authorization scope** — does the check bind every identity dimension (author AND location)? Is ownership derived from stable identity?
6. **Lost or duplicated external effects** — rollback-after-effect windows; state-derived idempotency keys (E-08); event publication timing (E-04/E-07).
7. **Derived-data consequences** — mirrors, indexes, caches, vector stores: does the change's lifecycle reach them? Does withdrawal block late republication?
8. **Domain incompleteness behind valid API operations** — schema-parity or CRUD presence standing in for actual behavior (P3: E-11; C1).
9. **Tests that cannot detect the suspected failure** — mirror-tests (assert the implementation's own assumptions); never-ran tests (E-10); tests that need real PostgreSQL for the race they claim to cover.
10. **Framework-managed behavior** — is the "fix" fighting the pinned framework's documented semantics? (P2: E-06)
11. **Numbers and claims** — does any count/status in the PR bind to a derivation or a SHA? (P6/P7: E-12, E-20)
12. **Migration safety** — touched `db/migration/`? checksum guard regenerated? applied files untouched? (P8: E-01)

## Adapter-family review (for B5-triggered changes)

Complete the family checklist explicitly — table with one row per concern: source table(s) · tombstone filter · mirrors · search/index/vector store · withdrawal event (blocks late republication) · ownership resolution (live vs anonymized) · optimistic-version in statement · seam list (owner/data-management paths that stay live). A missing row is a finding, not a TODO.

## Gate review (for B6-triggered changes)

- New/changed placeholder ⇒ target-env resolution verified?
- New health contributor ⇒ explicit enabled/disabled decision?
- New gate/aggregator ⇒ named automated consumer of its own state (E-13)?
- Deploy failure ⇒ provider-refusal vs code-failure distinguished in the log (E-16)?

## Disposition discipline

Findings close as: **fixed** (with commit + test), **registered** (divergence/ADR/accepted-risk with owner), or **rejected-with-mechanism** (why it cannot happen — a mechanism, not an opinion). "Noted" is not a disposition. Reopened findings keep their fingerprint identity across scans (the repo's coderabbit findings registries already practice this — follow their form).
