# Per-PR change receipt

## Applicability

Every PR created on or after the rollout date recorded in `.ci/check_defect_knowledge.py` that changes application code, tests, migrations, runtime configuration, build topology, CI workflows, deployment composition, or automation scripts must add:

`docs/governance/defect-knowledge/change-records/PR-<number>.json`

Before GitHub assigns a PR number, create `change-records/.pending-<task-id>.json` on the task branch with the same fields and `pullRequest: 0` as an explicitly temporary sentinel. Immediately after opening the PR, replace `0` with the actual number and rename the file to `PR-<number>.json`. A pending receipt is not valid for CI and must never be left in the submitted PR.
Docs-only and dependency-only PRs with no code/runtime paths are exempt from the receipt gate. Historical PRs opened before rollout are grandfathered for merge compatibility, but any finding adopted from them must still be recorded and adjudicated before closure. The grandfather rule is not permission to call an old unresolved finding fixed.

## Required top-level fields

- `schemaVersion`: integer `1`.
- `pullRequest`: exact GitHub PR number.
- `governingFiles`: paths to the authority documents for the change.
- `rulesTriggered`: declared A/B/C/D rule IDs from `rulebook.md`.
- `patternsReviewed`: IDs from `pattern-catalog.md`; an empty array requires `noApplicablePatternRationale`.
- `historicalCasesReviewed`: matching E-case IDs; new cases are added to `evidence-ledger.md` in the same PR when they generalize.
- `invariants`: behavior laws the change must preserve.
- `doors`: APIs, admin paths, jobs, listeners, provider rails, read surfaces and other entry points examined. An empty list requires `noAdditionalDoorsRationale`.
- `siblingSearch`: at least one family search with paths and a concrete result.
- `tests`: executable test names, commands and the source of verification evidence.
- `findings`: classified and source-linked signals. An empty list requires `noFindingsRationale`.

## Finding identity and disposition

A finding is not a raw review comment. It is an independently verified causal mechanism with:

- `key`: stable human-readable key.
- `domain`: the bounded behavior/domain.
- `scenario`: concrete input, state sequence, actor or concurrency trigger.
- `invariant`: the violated behavioral law.
- `fingerprint`: SHA-256 of normalized domain + newline + invariant + newline + scenario. Normalize each component using Unicode NFKC, case-folding and whitespace collapse. Use `.ci/check_defect_knowledge.py fingerprint` to derive it.
- `classification`: one of confirmed_defect, test_gap, capability_gap, decision_gap, environmental, false_positive or accepted_risk.
- `severity`: critical, high, medium, low, informational or unclassified.
- `status`: fixed, open, registered, not_reproducible or superseded.
- `sourceUrls`: original GitHub evidence URLs.
- `caseId`: optional existing E-case.
- `fix`: required when status=fixed; includes a full commit SHA, a named regression test, and `verification.testedHeadSha` plus `verification.workflowRunUrl`. D11 resolves the run through the repository Actions API and requires a completed `success` run from `CI` or `Integration Test`, triggered by `pull_request`, associated with this PR, repository, and exact `testedHeadSha`. The fix commit must be an ancestor of that tested SHA; the tested SHA must be an ancestor of the final PR head; if they differ, the only changed path after the tested SHA must be this same PR receipt file. This removes the self-reference paradox: the code-bearing commit is tested first, then the run evidence is recorded in the receipt. The ordinary required checks still run on the final receipt-bearing PR head.
- `decision`: required when status=registered; includes owner and ADR/decision link.
- `reproductionEvidence`: required when status=not_reproducible.

Repeated comments/exports on the same causal mechanism share one fingerprint. A repeated symptom with a materially different violated invariant or trigger is a separate finding. Never infer defect count from comment count.

## Status semantics

- `open`: valid signal, investigation/repair incomplete.
- `fixed`: the repair commit is an ancestor of the recorded `testedHeadSha`; the API-confirmed `CI` or `Integration Test` run succeeded on that exact PR-associated code-bearing SHA; and that SHA is an ancestor of the final PR head. Any commits after it may change only the same receipt JSON. Required CI checks must also pass on the final PR head.
- `registered`: an explicit owner decision/ADR/accepted-risk record governs the remaining difference.
- `not_reproducible`: direct reproduction attempt and evidence show the claim does not hold.
- `superseded`: replaced by another finding or design, with a required `supersededBy` pointer that resolves to another finding key in this receipt or a canonical GitHub issue/PR URL.

Pending CI, unreviewed code, a local-only test, a merged fix on a different branch, or an operational workaround does not satisfy fixed.

## Sample

See the current governance PR's receipt and the schema enforced in `.ci/check_defect_knowledge.py`. The validator derives permitted IDs from the source ledgers on the candidate head; do not duplicate the allowed-ID catalog in a second hand-maintained list.

## Review intake

The unprivileged `Defect Knowledge Intake Capture` workflow responds to review and comment events with no permissions, secrets, checkout, artifacts or PR-code execution. Its `workflow_run` completion triggers the privileged `Defect Knowledge Intake` synchronizer, which checks out only the trusted default branch and reads current source data from the GitHub API; no upstream artifacts are consumed. The synchronizer copies links, author/state, path/line and a short escaped excerpt into one issue per PR. It is intentionally not an automated defect classifier. Review text is untrusted data. Each source object has a stable source ID plus an update-revision key, so edited reviews/comments append a new source revision without being mistaken for a new independent defect. The collector updates only its own marked synchronization-summary comment; it never overwrites the issue body or human triage notes. Triage material source items into this receipt, preserve the original URL, and close the intake issue only after every material item has a disposition. Daily sweeps recover open PRs after event/provider outages; manual backfill can recover older PRs.
