# Change Protocol — mandatory procedure for AI coding tasks

> This is the operational core of the defect-knowledge system: the pre-edit, during-edit, and post-edit procedure every AI coding task follows. It is deliberately concise — universal steps live here; conditional rules load **selectively by trigger** from `rulebook.md` §B/§C. It integrates with (does not replace) `AGENTS.md` §0 and `docs/agent-protocol.md`.

## Phase 0 — Before editing (declare, then act)

1. **Anchor** (A1): declare the governing file and, for framework-touched behavior, the official-doc section for the pinned version. No anchor, no edit.
2. **Baseline** (A2): state the current behavior in one sentence; locate its tests. If the behavior has no test and the change is behavioral, the change's PR will add one.
3. **Blast radius** (A3): enumerate affected callers, alternate entry points (both API surfaces, admin rails, mirrors/indexes/caches, listeners, external effects), and permissions. Produce the enumeration table.
4. **Risk triggers** — from the table, load the conditional rules:
   | If the change touches… | Load |
   |---|---|
   | `db/migration/` anything | B1 |
   | retries, external effects, listeners, invalidation, idempotency | B2 |
   | permission/ownership checks or shared rows | B3 |
   | API/GraphQL surface, wrappers, pagination, SDL | B4 |
   | mirrors, indexes, caches, vector stores, purge/withdraw | B5 |
   | profiles, env placeholders, health, deploys, new gates | B6 |
   | decimal endpoints | B7 |
   | feature flags | B8 |
   | Saleor contracts / commerce money / content visibility / cross-module | C1–C4 |
5. **Invariants and failure scenarios**: write down (in the PR draft) the invariants that must hold and the failure scenarios to verify — including the historical shapes from `pattern-catalog.md` that the triggers selected (e.g. B2 ⇒ "rollback-after-external-effect" scenario).
6. **Acceptance evidence defined up front**: what test/gate/measurement will prove the change, named before implementation.

## Phase 1 — During implementation

1. Implement the **required behavior** (the governing file's), not a plausible local structure (A1/A4).
2. Preserve module boundaries, contracts, ownership, and transaction semantics (C4, B2); deviations are registered, not silent.
3. **No placeholder success, no silent no-ops, no incomplete lifecycle** (P5/P8): every enumerated door gets its disposition in the same change.
4. Tests verify **domain requirements independently** — they must be able to fail against the real defect (a test that mirrors the implementation's assumptions protects nothing; E-10's never-ran test is the standing example).
5. Apply the loaded conditional rules; a justified deviation from house patterns is documented inline with its reason.

## Phase 2 — After implementation (verify as an adversary, then bind)

1. **Re-read the full diff** hunk-by-hunk against intent (A4).
2. **Run the required evidence**: module verify (`mvn clean verify -pl <module>`), the guards touched by the change (enforcement-map), integration tests for the replayed failure scenarios.
3. **Adversarial pass**: run `review-protocol.md` §Checklist against the diff — the historical defect patterns are the search list, not trivia.
4. **Sibling search**: if the change fixes a recurring mechanism (the pattern catalog says which mechanisms are recurring — P5 above all), search for the same defect in sibling paths in the same session (E-09's lesson: doors are found by searching, not by waiting).
5. **Bind the evidence**: status statements cite the exact SHA; numbers cite the derivation or the dated command (A5/A6).
6. **Record new knowledge**: a newly-discovered generalizable lesson gets a ledger entry (or a pattern revision) in the same PR — the system learns at the moment of discovery, not later.
7. **Push atomically**: implementation + tests + docs in one coherent commit (or a stacked series where each link compiles and passes alone) — interrupted sessions must not leave partial state on a shared branch (A5, E-10).

## Session-interruption discipline (P7)

- Prefer pushing work-in-progress to a **task branch** (never a shared integration branch) when interruption threatens; the branch may be red only if named for that purpose and stated so.
- On resume: re-measure state from the remote (`git fetch` + status), never from memory.
- The owner's standing "execute on GitHub immediately" protocol is the correct default: the remote is the recovery point.
