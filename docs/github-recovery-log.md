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

The review found active implementation PRs #540/#541 (discovery), #543/#546 (multi-role/trust), #544/#547 (unified search), and #545 (notification routing). A patch-level diff showed that 93 of 97 paths shared between #541 and each of #543/#544/#545 carried identical patches. #543 and #546 also propose incompatible role tables at V182, while #544 and #547 overlap in UnifiedSearchService and its test. These findings motivated explicit dependency retargeting and closure of competing PRs rather than merge-as-is.

## Actions completed

- **Closed without merge:** #548, #549, #550 — repeated Android client attempts. Each received a cleanup explanation and a link to the replacement foundation.
- **Closed without merge:** #532, #534, #536 — superseded planning-document chain. The newer JT-01..JT-20 journey specification, UX design system, product-management guidance, and public-evidence benchmark were copied into the replacement branch before closure.
- **Closed without merge:** #543 — competing V182 model `user_role_assignments` conflicts with the retained #546 `user_roles` model; its unique trust/verification work is preserved on the branch for selective future review.
- **Closed without merge:** #544 — competing search implementation. Its unique Arabic reference corpus and D-07 gate are explicitly retained as work to port into #547 after remeasurement; the remote branch remains recoverable.
- **Retargeted dependency bases:** #545 and #546 now target `discovery/wave-part1` (the #541 head); #547 remains stacked on #546; #540 remains stacked on #541. #545's changed-file count dropped from 183 to 93. #544 did not collapse its diff after a base change (still 149 files), so it was closed rather than falsely described as cleaned.
- **No main merge, no code PR merge, and no new PR were created.** The replacement remains a reviewable branch, not a published implementation.
- The latest measured comparison before these last cleanup updates was 19 commits ahead of main and 0 behind; re-run comparison before any review/PR decision.

## Outstanding limits and preservation

- The connected GitHub action set in this session supports closing PRs and reading/updating files, but does not expose a branch-delete operation. Closing a PR does **not** delete its remote branch; the six closed PR branches remain recoverable. Do not report branch deletion as complete.
- PRs #540, #541, #543, #544, #545, #546, and #547 remain open for engineering review; they were not closed merely because they have overlapping filenames or related themes. In particular, #541 and #545 shared 97 changed file paths at the time of inspection; their branch/migration dependency must be reconciled before either is merged.
- Preserve existing backend work until its semantic and migration effects are evaluated. Never merge into main without explicit owner instruction.
