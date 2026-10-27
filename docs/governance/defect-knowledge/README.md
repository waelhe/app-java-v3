# Defect-Knowledge System — the permanent defect-prevention layer

> **Status:** canonical, governance-integrated (decision of the historical-engineering mission, 2026-10-27).
> **Authority chain:** Spring Boot / Maven / Spring Modulith / DGS / Flyway official docs (pinned versions in the root POM) → `AGENTS.md` §0 load order → this system for *how history constrains every future change*.
> **Scope:** the backend repository `app-java-v3`, full history 2026-04-16 → 2026-10-27, pinned at the audit heads: `main@7836c157` and `integration/saleor-full-commerce-20261021@d5d65451`.

## What this is

Every defect this repository has already paid for was converted here into **traceable engineering knowledge**: an evidence ledger of reconstructed incidents, a catalog of the recurring causal patterns behind them, a rulebook of permanent engineering rules, a mandatory change protocol for AI agents, an independent adversarial review protocol, and an enforcement map that binds each rule to the mechanism that actually enforces it.

The system exists because the measured history shows the *same causal mechanisms* returning through different files: verification environments that differ from production in exactly the failed dimension, framework defaults adopted without decisions, contract-name parity masking semantic divergence, retry logic coupled to carrier transactions, business rules enforced at one door while alternate doors stay open, hand-maintained numbers drifting from derived reality, interrupted agent sessions leaving partial state, and applied migration history being edited.

## How to use it (binding)

1. **Before any edit** — follow `AGENTS.md` §0 load order; when reaching the risk step, load [`change-protocol.md`](change-protocol.md) and apply the pattern families it triggers ([`pattern-catalog.md`](pattern-catalog.md)).
2. **Before writing a fix** — check [`evidence-ledger.md`](evidence-ledger.md): a case with your symptom already exists, with its root cause and the correction that actually worked.
3. **After implementing** — run the adversarial pass in [`review-protocol.md`](review-protocol.md) and the rule checklist in [`rulebook.md`](rulebook.md).
4. **When a guard fails** — [`enforcement-map.md`](enforcement-map.md) states what each guard protects and which historical outage class it closes; never work around a guard — fix the root cause or register a governed divergence.

## Contents

| Artifact | Role | Feeds |
|---|---|---|
| [`evidence-ledger.md`](evidence-ledger.md) | Historical incidents reconstructed as causal cases with citations (SHA / PR / test / run) | pattern catalog, rulebook |
| [`pattern-catalog.md`](pattern-catalog.md) | The recurring causal mechanisms (P1–P9), verified vs hypothesis | change protocol, review protocol |
| [`rulebook.md`](rulebook.md) | Permanent rules: A universal · B conditional · C domain · D automated | every change |
| [`change-protocol.md`](change-protocol.md) | Mandatory pre-edit / during / post-edit procedure for AI coding tasks | agent sessions |
| [`review-protocol.md`](review-protocol.md) | Independent adversarial review standard, complementing CodeRabbit | reviews |
| [`enforcement-map.md`](enforcement-map.md) | Rule → enforcement binding + audit coverage + open gaps | CI, guards |

## Relationship to existing authority

This system **reuses and extends** — it does not compete with — the established files: `docs/agent-protocol.md` §9 (the compact regression list) remains the quick in-bootstrap view and now points here; `docs/observability/postmortems/` remains the incident-record home (the ledger indexes it); `docs/adr/` remains the decision-record home; `CODING_STANDARDS.md` remains the code-shape authority. Conflicts resolve upward: official framework docs → root governance files → this system.

## Verification status of the system itself

- Derived from: 715 PRs inventoried via API; 14,567 workflow runs (2,364 failures) classified; full git history of `main` (706 commits) and the integration line (785 commits); in-repo postmortems, ADRs, campaign reports, CodeRabbit finding registries (2026-10-25/26); the round-13/14 PR #723 audit records.
- Validation: representative historical cases replayed against the new rules in [`enforcement-map.md`](enforcement-map.md) §4 — each selected case from a different causal family.
- Limitations and unresolved items are stated explicitly in [`enforcement-map.md`](enforcement-map.md) §5 — nothing is claimed complete that was not measured.
