# Defect-Knowledge System — the permanent defect-prevention layer

> **Status:** canonical, governance-integrated (initial decision 2026-10-27; closed-loop refresh 2026-11-13 on a review branch, pending CI and merge).
> **Authority chain:** Spring Boot / Maven / Spring Modulith / DGS / Flyway official docs (pinned versions in the root POM) → `AGENTS.md` §0 load order → this system for *how history constrains every future change*.
> **Scope:** the backend repository `app-java-v3`; original historical audit through 2026-10-27 plus post-October reconciliation through `main@f92873c9` (2026-11-13, after #829 merged); the initial reconciliation measurement was `main@445f421d`. The commerce candidate is separately pinned at `b97747f0` until re-measured.

## What this is

Every defect this repository has already paid for was converted here into **traceable engineering knowledge**: an evidence ledger of reconstructed incidents, a catalog of the recurring causal patterns behind them, a rulebook of permanent engineering rules, a mandatory change protocol for AI agents, an independent adversarial review protocol, and an enforcement map that binds each rule to the mechanism that actually enforces it, a source-linked index of later findings and repairs, and a closed loop that ingests review signals, requires per-PR evidence receipts, and keeps unverified claims open. A small agent skill routes each task to focused risk-specific rule packs (progressive disclosure); those packs explain how to apply the canonical rules without creating a second rulebook.

The system exists because the measured history shows the *same causal mechanisms* returning through different files: verification environments that differ from production in exactly the failed dimension, framework defaults adopted without decisions, contract-name parity masking semantic divergence, retry logic coupled to carrier transactions, business rules enforced at one door while alternate doors stay open, hand-maintained numbers drifting from derived reality, interrupted agent sessions leaving partial state, and applied migration history being edited.

## How to use it (binding)

1. **Before any edit** — follow `AGENTS.md` §0 load order; read [`SKILL.md`](SKILL.md) as the routing index, then load [`change-protocol.md`](change-protocol.md) and only the risk-specific rule packs triggered by the change. Apply their pattern families from [`pattern-catalog.md`](pattern-catalog.md). Do not load every pack for an unrelated change.
2. **Before writing a fix** — check [`evidence-ledger.md`](evidence-ledger.md): a case with your symptom already exists, with its root cause and the correction that actually worked.
3. **For every new code/runtime PR** — create `change-records/PR-<number>.json`. Before the PR number exists, use `change-records/.pending-<task-id>.json` with the same required fields and `pullRequest: 0`; replace the sentinel and rename it immediately after opening the PR. D11 validates the receipt even when a docs-only change edits an existing receipt. A `fixed` disposition records `testedHeadSha` plus an API-verified successful `CI` or `Integration Test` run associated with that exact code-bearing head; the fix commit must be its ancestor, and the final receipt-bearing PR head may differ only in that same receipt file. The normal required checks still run on the final PR head. D12 also ensures that only bot-authored control markers can drive intake deduplication or managed-comment updates.

**Fork/Dependabot intake security:** the review-event capture workflow has `permissions: {}`, no checkout, no secrets, no artifacts and no PR-code execution. The privileged synchronizer is triggered by that capture workflow's `workflow_run` completion, checks for success, checks out only the trusted default branch, and re-reads the current PR/review/comment records through the GitHub API. It does not consume upstream artifacts or trust event-supplied review text. This split is required because GitHub restricts tokens for fork and Dependabot review events; the `workflow_run` receiver must remain isolated from untrusted code/artifacts.

Official GitHub references: [Events that trigger workflows — fork restrictions and workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows), [Dependabot restrictions](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-on-actions), and [workflow permissions](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions).
4. **After implementing** — run the adversarial pass in [`review-protocol.md`](review-protocol.md) and the rule checklist in [`rulebook.md`](rulebook.md).
5. **When a guard fails** — [`enforcement-map.md`](enforcement-map.md) states what each guard protects and which historical outage class it closes. Never work around a guard: fix the root cause, or use a divergence route that actually exists today — the Saleor root-contract gate's registered-divergence CSV (`saleor-contract-divergences.csv`, rules B4/C1): since `0edf0f6` (PR #723) the root-signatures guard itself reads that registry, honoring an APPROVED row that names the failing surface and reporting it in its step summary (case E-21 closed). No general divergence register exists for the other guards (the residual of gap **G3**, enforcement-map §5); a guard without a registered route failing means the fix, not the bypass.

## Contents

| Artifact | Role | Feeds |
|---|---|---|
| [`evidence-ledger.md`](evidence-ledger.md) | Historical incidents reconstructed as causal cases with citations (SHA / PR / test / run) | pattern catalog, rulebook |
| [`pattern-catalog.md`](pattern-catalog.md) | The recurring causal mechanisms (P1–P15), verified vs hypothesis | change protocol, review protocol |
| [`rulebook.md`](rulebook.md) | Permanent rules: A universal · B conditional · C domain · D automated | every change |
| [`change-protocol.md`](change-protocol.md) | Mandatory pre-edit / during / post-edit procedure for AI coding tasks | agent sessions |
| [`review-protocol.md`](review-protocol.md) | Independent adversarial review standard, complementing CodeRabbit | reviews |
| [`enforcement-map.md`](enforcement-map.md) | Rule → enforcement binding + audit coverage + open gaps | CI, guards |
| [`review-findings-index-2026-11-13.json`](review-findings-index-2026-11-13.json) | Machine-readable findings/repair/backlog reconciliation | intake, reporting |
| [`historical-reconciliation-2026-11-13.md`](historical-reconciliation-2026-11-13.md) | PR/review/repair history and explicit limits | pattern updates |
| [`SKILL.md`](SKILL.md) | Small agent router: task triggers → focused rule packs; authority/evidence ladder | agent bootstrap |
| [`rules/`](rules/) | Eight focused packs for money, async events, authorization, contracts, DB/concurrency, config/release, modes/diagnostics and signal triage | triggered by task, never loaded all by default |
| [`saleor-ai-adoption-map.md`](saleor-ai-adoption-map.md) | What is adopted/adapted/not adopted from official Saleor AI resources | skill/source governance |
| [`saleor-ai-sources-2026-11-13.json`](saleor-ai-sources-2026-11-13.json) | Pinned docs/skills/tool provenance, hashes, capabilities and boundaries | source drift/review |
| [`change-records/`](change-records/) | Structured evidence receipt per new code/runtime PR | CI/D11 |
| [`.ci/defect_knowledge_intake.py`](../../../.ci/defect_knowledge_intake.py) + workflow | Idempotent, revision-aware GitHub review-signal intake; preserves human triage and supports recovery sweeps | triage queue |
| [`.ci/check_defect_knowledge.py`](../../../.ci/check_defect_knowledge.py) + trusted-base step in [`coderabbit-gate.yml`](../../../.github/workflows/coderabbit-gate.yml) | Receipt validation under the existing required gate; dedicated workflow is supplemental | D11 |

## Relationship to existing authority

This system **reuses and extends** — it does not compete with — the established files: `docs/agent-protocol.md` §9 (the compact regression list) remains the quick in-bootstrap view and now points here; `docs/observability/postmortems/` remains the incident-record home (the ledger indexes it); `docs/adr/` remains the decision-record home; `CODING_STANDARDS.md` remains the code-shape authority. Conflicts resolve upward: official framework docs → root governance files → this system.

## Verification status of the system itself

- Derived from: Original audit: 715 PRs inventoried and 14,575 workflow runs classified (2,569 failures); follow-up reconciliation: 75 PR records created after #746 through #833, #816's 34 findings plus #826's W7/W8, 653 review threads / 682 inline CodeRabbit comments across #806–#811, and 19 distinct repair-review fingerprints across #819/#821/#822/#823/#829 measured 2026-11-13. These are source signals, not defect counts. The ledger now carries E-01…E-52, including three defects found while adversarially reviewing this implementation. GitHub Copilot Code Review was requested through the official API but denied with `422 ACTOR_INELIGIBLE`; no independent review was created. See `historical-reconciliation-2026-11-13.md` and `review-findings-index-2026-11-13.json`.
- Validation: representative historical cases replayed against the new rules in [`enforcement-map.md`](enforcement-map.md) §3 — each selected case from a different causal family.
- Self-consistency: this system's own enumerations (rule counts, case tags, pattern range, phase/section references) are derived and enforced by `DefectKnowledgeDocsGuardTest` — the system applies rule A6/D2 to itself.
- Limitations and unresolved items are stated explicitly in [`enforcement-map.md`](enforcement-map.md) §5 — nothing is claimed complete that was not measured.
