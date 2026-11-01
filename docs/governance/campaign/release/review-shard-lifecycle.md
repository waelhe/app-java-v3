# Review-shard lifecycle v2 — tracking shards (adopted 2026-11-01)

## Problem

CodeRabbit reviews at most 100 files per review (measured on #787: "Review skipped: 425 files exceed the limit of 100"). The commerce candidate #787 changes 439 paths, so its CodeRabbit Gate verdict is deferred by the trusted gate workflow (`.github/workflows/coderabbit-gate.yml`, oversized-candidate path) to the fail-closed coverage verifier `.ci/check_coderabbit_review_coverage.py`, which requires every changed path to be covered by a CodeRabbit review on an open shard PR whose recorded base SHA equals the candidate's and whose per-path base/head blobs are identical to the candidate's.

## The v1 defect (removed 2026-11-01)

v1 cut a NEW set of six shard PRs for every remediation wave: each candidate fix moved the candidate head, which invalidated every existing shard cut, so waves 1–3 opened 18 shard PRs (#788–#805) for three review rounds. Unbounded PR churn — rejected by the owner. All superseded waves are closed with per-PR dispositions; their branches are retained for audit until the candidate merges.

## v2 contract — fixed tracking shards

- Exactly **six shard PRs** exist for this campaign: #806, #807, #808, #809, #810, #811. **No further shard PRs are ever opened.**
- A shard branch is a review-only artifact: never merged, never the target of fixes, and **may be force-updated (re-cut)** — the verifier compares per-path blobs, not commit history.
- When remediation moves the candidate head `H_old → H_new`, compute affected paths (`git diff --name-only H_old H_new`) and re-cut **only the shards whose partitions intersect those paths**. Unaffected shards keep their heads and their reviews: the verifier's per-path blob equality still holds for them because their paths' blobs did not change.
- Manifest commits (`coderabbit-review-coverage.json` in this directory) touch only the manifest path, which the verifier explicitly excludes from the coverage obligation — writing the manifest never invalidates shard reviews.
- On 6/6 shard reviews in `{APPROVED, COMMENTED}` at heads whose blobs match the candidate head: write the manifest with the real review IDs, the gate re-runs on the synchronize event, and the candidate merges squash-only once the CodeRabbit Gate plus the required checks are green on that head. The shard PRs close with dispositions immediately after the candidate merges.

## Base-alignment invariant

The verifier requires every shard PR's recorded `base.sha` to equal the candidate's `base.sha`. Both freeze at their last base(-change) event; they were realigned on 2026-11-01 to `1fcb6b87` (the main tip after #785) by re-targeting #787's base. `main` must not move between the shard cuts and verification (nothing else merges to main during the campaign).

## Review quota model (measured)

One `@coderabbitai` trigger per driver run. After a completed review or a rate-limit reply, the next trigger waits: measured on #788, rejections at +47/+53 min, acceptance at +56/+104 min; the driver uses ≥45 min after a review and ≥75 min after a rate-limit reply. This repository gets no automatic reviews (fewer than 10 stars), so every review is an explicit trigger.
