# GitHub recovery and delivery log

**Branch:** rebuild/product-experience-foundation  
**Base:** main at `d0a5800c527520e833c18c0abb532e1819e07ef3` (checked 2026-10-11).  
**Purpose:** record cleanup and preserve recoverability; this is not a claim that the platform implementation is finished.

## Findings at the time of review

### Duplicate Android attempts

- #548 — native Android onboarding/community/discovery, 21 files, 4,160 additions.
- #549 — native Android client, 22 files, 3,805 additions, draft.
- #550 — native community vertical slice, 23 files, 2,204 additions.

All three were open and unmerged at inspection. They separately scaffolded Android clients and used differing package/redirect URI configurations; their own scopes explicitly leave major backend product gaps untouched. They are treated as repeated, superseded attempts, not three product choices.

### Documentation plan chain replaced here

- #532 — unified execution plan.
- #534 — JT-01..JT-17 user journey specification.
- #536 — broader suite adding detailed UX design, product management, Nextdoor evidence, and JT-18..JT-20.

The new foundation retains and updates the later JT-01..JT-20 journey contract, UX design system, public-evidence benchmark, product management method, product decision register, and a product-led blueprint. The earlier chain can be closed after those files are verified on this branch.

### Other open backend PRs

The review found active implementation PRs #540/#541 (discovery), #543/#546 (multi-role/trust), #544/#547 (unified search), and #545 (notification routing). They are not closed solely on filename overlap: their domain and migration impacts must be reviewed against main and each other before a close, retarget, or merge decision. #541 and #545 share many changed file paths, so they are a conflict-risk requiring explicit dependency review.

## Actions and limits

- Close the duplicate Android PRs and stale documentation-plan chain only after verifying the replacement content is preserved.
- Never merge into main without explicit owner instruction.
- The connected GitHub action set in this session supports closing PRs and reading/updating files, but does not expose a branch-delete operation. Closing a PR does **not** delete its remote branch; do not claim the branch was deleted. Branch deletion can be done later through GitHub's branch UI or an authorized delete-ref integration if available.
- Keep older commits/branches recoverable until useful backend changes are selectively extracted and tested.
