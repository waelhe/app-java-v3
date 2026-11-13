# Review-shard lifecycle v2 — tracking shards (adopted 2026-11-01)

## Problem

CodeRabbit reviews at most 100 files per review (measured on #787: "Review skipped: 425 files exceed the limit of 100"). The commerce candidate #787 changes 439 paths, so its CodeRabbit Gate verdict is deferred by the trusted gate workflow (`.github/workflows/coderabbit-gate.yml`, oversized-candidate path) to the fail-closed coverage verifier `.ci/check_coderabbit_review_coverage.py`, which requires every changed path to be covered by a CodeRabbit review on an open shard PR whose recorded base SHA equals the candidate's and whose per-path base/head blobs are identical to the candidate's.

## The v1 defect (removed 2026-11-01)

v1 cut a NEW set of six shard PRs for every remediation wave: each candidate fix moved the candidate head, which invalidated every existing shard cut, so waves 1–3 opened 18 shard PRs (#788–#805) for three review rounds. Unbounded PR churn — rejected by the owner. All superseded waves are closed with per-PR dispositions; their branches are retained for audit until the candidate merges.

## v2 contract — fixed tracking shards

- Exactly **six shard PRs** exist for this campaign: #806, #807, #808, #809, #810, #811. **No further shard PRs are ever opened.**
- A shard branch is a review-only artifact: never merged, never the target of fixes, and **may be force-updated (re-cut)** — the verifier compares per-path blobs, not commit history.
- When remediation moves the candidate head `H_old → H_new`, compute affected paths (`git diff --name-only --no-renames H_old H_new` — rename detection disabled on purpose, the 2026-11-08 #811 round-7 review: a file MOVED between shard partitions must surface BOTH its old and new path, or the old shard keeps a stale blob the verifier then rejects as coverage outside the candidate) and re-cut **only the shards whose partitions intersect those paths**. Unaffected shards keep their heads and their reviews: the verifier's per-path blob equality still holds for them because their paths' blobs did not change.
- Manifest commits (`coderabbit-review-coverage.json` in this directory) touch only the manifest path, which the verifier explicitly excludes from the coverage obligation — writing the manifest never invalidates shard reviews.
- An **emptied shard** (remediation removed ALL of its changes, so its diff went to zero files) is omitted from the manifest's `reviewShards` and excluded from the 6/6 requirement — the verifier only accepts listed shards with 1–100 changed files and an exact-coverage union, so an emptied shard simply stops being listed (the 2026-11-08 #811 round-7 review). The emptied shard PR itself **stays open** with a disposition comment pointing at the remediation commit that emptied it, and full path coverage stays carried by the shards that still hold the paths. No new shard PR is ever opened to replace it.
- On 6/6 shard reviews in `{APPROVED, COMMENTED}` at heads whose blobs match the candidate head (an emptied shard being omitted per the rule above): write the manifest with the real review IDs, the gate re-runs on the synchronize event, and the candidate merges squash-only once the CodeRabbit Gate plus the required checks are green on that head **and the owner's explicit merge word is on record** — the green checks authorize the merge, they never trigger it: merging IS the production deploy, and AGENTS.md's law («لا دمج إلى main إلا بكلمة صريحة من المالك عبر بوابات الدمج الثلاث المقيسة») governs the final step (the 2026-11-08 #811 round-7 review). The shard PRs close with dispositions immediately after the candidate merges.

## Base-alignment invariant

The verifier requires every shard PR's recorded `base.sha` to equal the candidate's `base.sha`. Both freeze at their last base(-change) event.

**Latest measured re-alignment — 2026-11-13:**

- Candidate PR #787 base: `main` at `f92873c947e94c76ed3e7ea211db40a985ea3d63`; candidate head: `88a84aee4d9055215ca346d22be68a33cd393272`.
- The six permanent review PRs (#806–#811) were re-targeted to that exact base and re-cut from the candidate's current diff. Every shard PR remains open and review-only; no shard PR is merged.
- The resulting shard path counts are **97 / 98 / 87 / 89 / 98 / 99** (all within the hard 100-file ceiling). The union is **568 distinct paths**: exact one-to-one coverage of the candidate's 568 changed paths, with zero missing paths, zero extra paths and zero overlap.
- The ten migration paths in the refreshed partitions now follow the candidate's current names, V299–V308. Three paths retained only by the older cuts were removed from the partitions: `docs/METHODOLOGY.md`, `docs/agent-skill.md`, and `.github/workflows/openrewrite-catalog.yml`.
- Fresh CodeRabbit reviews are required for the new shard heads. The manifest must be written only after the six actual review IDs and states are available; do not reuse review IDs from the superseded heads.

The earlier 2026-11-01 alignment to `1fcb6b87` (the then-current main tip after #785) is historical. Before final manifest verification, the candidate and all listed shards must share the same frozen base SHA. If `main` advances before verification, repeat the alignment and re-cut only affected shards under the v2 rules.

 
**Post-repair measurement — 2026-11-13T16:52:20Z (GitHub live):**

- Candidate #787 is open at `44cf56ab144b9d6abc1afc422026d3d70cf65702`, base `main@f92873c947e94c76ed3e7ea211db40a985ea3d63`, 568 changed paths, 432 ahead / 0 behind. The last integration commit is the squash merge of repair PR #836.
- Repair #836 removed the duplicate governance-test declaration. Its JDK 25 build, full CI, integration suite, and CodeRabbit review passed before it was squash-merged into the integration branch.
- PR #811 alone was re-cut after that code change at `a443a601c673239d30767334487b813d4e950ca2`; the target test file now has the exact candidate blob.
- A fresh recursive-tree comparison against the common base confirms exact six-shard coverage: **97 / 98 / 87 / 89 / 98 / 99 = 568** paths, with zero missing, extra, overlapping, or mismatched paths.
- No current-head CodeRabbit review is available for any of #806–#811, and the coverage manifest has not been written. Do not reuse review IDs or processed-file lists from earlier heads.
- **This documentation refresh itself changes `PROJECT_MAP.md` and this lifecycle file, both in shard #811's partition. After this documentation PR merges into the candidate branch, re-cut #811 once more from the resulting candidate head and repeat the exact-tree comparison. Do not re-cut the other five shards.**

The check states above are a dated snapshot. Always re-fetch the candidate and shard refs, workflow runs, reviews, and base SHA before the next state-changing action.

## Review quota model (measured)

One `@coderabbitai` trigger per driver run. After a completed review or a rate-limit reply, the next trigger waits: measured on #788, rejections at +47/+53 min, acceptance at +56/+104 min; the driver uses ≥45 min after a review and ≥75 min after a rate-limit reply. This repository gets no automatic reviews (fewer than 10 stars), so every review is an explicit trigger.
