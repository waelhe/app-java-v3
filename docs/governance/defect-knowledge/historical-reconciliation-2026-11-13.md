# Historical reconciliation — defect knowledge refresh (2026-11-13)

## Scope and measurement basis

This refresh starts from the knowledge-system baseline merged by PR #746 on 2026-10-28 and initially measured the repository through main@445f421d67ad58bd533d42be9d5bbf6d268d9161; the live main tip later advanced to f92873c947e94c76ed3e7ea211db40a985ea3d63 when #829 merged on 2026-11-13.

- GitHub returned **75 PR records created after #746 and through #833** in the repository's paginated PR listing. The set contains product/code changes, review-only shards, documentation/governance PRs, Dependabot updates and OpenRewrite-generated changes. It is a PR inventory, not 75 defects.
- The six current permanent commerce review shards (#806–#811) contained **653 review threads**: 228 resolved and 425 unresolved at this measurement. The six PRs contained **682 CodeRabbit inline review comments with distinct GitHub review-comment fingerprints**. These are review signals, not confirmed defects or a count of unique root causes.
- Five CodeRabbit agent scan exports on 2026-10-25/26 reported **205 raw result appearances** (35 + 46 + 41 + 54 + 29). The 2026-10-26 registry demonstrates why raw exports cannot be summed as defects: 31 fingerprints were shared with the preceding export, four disappeared from the scan after earlier fixes, and 15 were new fingerprints, of which 14 received fixes and one was a re-fingerprint of a previously closed result.
- The historical engineering mission recorded in #746 inventoried 715 PRs and classified 14,575 workflow runs, including 2,569 failed runs. A failed workflow is not automatically a software defect.

The machine-readable index is review-findings-index-2026-11-13.json. It records the 34 findings in #816, the two new findings in #826, their repair PRs, the source links, the main-branch state where verified, and separately classified capability/decision gaps.

## Review and repair chronology

| Period / PR | Verified outcome | Knowledge consequence |
|---|---|---|
| #741–#744 | Four-part security carry with 51 listed commits and a 219-test security battery; the carry included money, identity, fulfillment, content-purge, numeric-bound and migration-safety fixes. | Reuse the recurring causes rather than count commits as defects. P4/P5/P8 and E-08/E-09/E-17/E-18 are relevant. |
| #746 | Historical engineering investigation: 715 PRs, 14,575 workflow runs, 2,569 failed runs; evidence ledger E-01..E-22 and patterns P1..P9 at creation. | Good historical reconstruction, but it did not establish automatic review intake or per-PR evidence receipts. |
| #750 | Thirteen single-line Java sources caused the import-only OpenRewrite publication guard to reject generated changes; a source-format guard was added. | E-23 / P10 / D9 already cover this. |
| #751 | Placeholder-vs-deployment-target matrix guard added; three target-record gaps were found and closed. | D10 / B6 and the remaining staging-record limitation are retained. |
| #757–#758 | The main→commerce conflict-resolution review had 16 observations. The repair PR documents five merge-resolution corrections, four verified main-line defects (BCrypt UTF-8 byte limit, JPEG fill-byte handling, a false-positive CONNECT test, and ledger wording), and one additional non-reproducible observation. | Keep review observations, verified defects and non-reproducible findings as different dispositions. |
| #761–#764 | A purge version increment was present but inadequately pinned by its test; #764 added a real-PostgreSQL/Testcontainers proof of version advancement, stale-write rejection and idempotent replay. | E-28 / P12: structural SQL assertions are insufficient when the claimed invariant is database concurrency behavior. |
| #768–#771 | OpenRewrite PR creation needed a scoped token path; the catalog audit was made safe for Dependabot; the vendored skills surface was brought under reviewed pins. | E-29/E-30: automation and agent-supply-chain configuration are part of the defect surface. |
| #785 | A fail-closed CodeRabbit coverage gate was added for large PRs (100-file ceiling, explicit processed-file list, exact path/blob coverage and negative tests). | A review-coverage gate is not the same as a defect-intake/triage process. |
| #806–#811 | Six permanent review-only shards accumulated 653 threads / 682 CodeRabbit inline comments at the snapshot. | Open threads and raw comments must be ingested and adjudicated; do not treat them as a defect count. |
| #813 | Fixed OpenRewrite's use of unpinned system Maven by using the repository Maven Wrapper. The merge commit is an ancestor of main@445f421d. | E-27 / P1 / P14. |
| #816 | Adversarial review registered 34 findings: 32 confirmed and two suspected, across money, identity, purge, API contracts, test quality and CI/configuration. | Canonical finding source for A1–W6. |
| #817–#818 | #817 fixed optimistic-version advancement across five purge adapters; #818 added the last-active-admin guard. Both merge commits are ancestors of main@445f421d. | G1/G2/G4/G5/G6 and B1 are fixed on main, even though the commerce candidate must be checked/synchronized separately. |
| #819–#824 and #827–#833 | Separate repair PRs were opened for findings from #816/#826. At the first inventory, they were open. A live refresh after #829's merge found #829 merged to main; #819–#824 (except #825, a closed unmerged W7 carrier), and #827/#828/#830–#833 remained open. | The PR's base matters: a fix on main is not automatically present on the commerce candidate. Status is branch-specific and must be remeasured. |
| #826 | Follow-up review found W7 (fixed by #813) and W8 (CodeRabbit poll/coverage-verifier disagreement). A full-review operational workaround was used, but a structural W8 repair is still outstanding. | E-26/E-27 / P13/P14; installing a gate does not prove the gate is coherent or healthy. |

## Findings vs. capability gaps

The 34 entries A1–W6 in #816 and W7–W8 in #826 are defects or suspected defects, each with a review status. They are not all introduced by the commerce branch: the November 10 audit compared the main baseline and the commerce candidate, and most heavy findings were present in both.

Issues #774–#782 are not all defects. They include **capability gaps** (for example, incomplete order-discount allocation), **NOT_STARTED roots**, and **owner-decision gaps** (gift-card liability, tax ownership, channels, shop/refund settings). They must not be recast as bugs merely to increase a defect count. The index records them separately.

## Branch-state truth

- main at the time of this reconciliation is 445f421d.
- GitHub commit comparisons confirm #813 (445f421d), #817 (5de7564f) and #818 (55f1a196) are ancestors of current main.
- Commerce PR #787 remains open at b97747f0, last updated 2026-11-10. Do not infer that a fix on main is automatically present on that candidate. The campaign's review-shard lifecycle requires fixes and the corresponding review evidence on the candidate itself.
- The first snapshot was followed by #829 merging at f92873c9 on 2026-11-13. #829 fixes A2 on main; its 12 distinct CodeRabbit review fingerprints were all addressed before merge. #831/#832/#833 remain open in the refreshed state. #819/#821/#822/#823 have resolved review threads but their PRs remain open, so their fixes are still in-flight, not fixed on main.
- PR #749 remains an open issue describing an ABBA lock-order deadlock window and outbound OSRM HTTP while a database transaction/order lock is held. It is retained as two distinct causal cases (E-24 and E-25), not silently counted as fixed.

## Reconciliation limits

All 75 post-#746 PR records were inventoried from GitHub's PR listing and their purpose/status was classified. Detailed review-content verification was concentrated on PRs that carried findings, repairs, review shards, or governance mechanisms, and on the source ledgers linked above. This document does **not** claim that every changed line in every automation, dependency, documentation and product PR has undergone a fresh line-by-line independent review. The index names the findings for which evidence and disposition were actually retrieved; missing evidence remains open rather than being inferred as fixed.

## Operational consequence

This is a historical backfill, not the closed loop by itself. The companion implementation adds:

1. A review-event intake workflow that records links to every review submission and inline review comment on an open PR, deduplicates by GitHub source ID, and creates/updates one intake issue per PR.
2. A scheduled sweep and manual PR backfill route to recover events missed during provider outages.
3. A structured per-PR change receipt with stable fingerprints, historical-case/pattern references, blast-radius enumeration, tests and explicit dispositions.
4. A trusted-base CI validator for the receipt; it checks code-changing PRs after the rollout cutoff, verifies case/pattern/rule references, rejects duplicate fingerprints with conflicting case identities, and requires test/repair evidence for a fixed disposition.
5. This reconciliation index and new causal cases/patterns, so the next PR does not have to rediscover the same mechanism from raw review comments.

The intake collector deliberately does **not** decide that a CodeRabbit sentence is a confirmed bug. Classification remains a reviewed act: confirmed defect, test gap, capability gap, decision gap, accepted risk, or non-reproducible/false positive. The system closes only when the finding has a traceable disposition and the relevant regression control is enforced.


## Live refresh after the first snapshot — 2026-11-13

The initial PR inventory was written while main was at 445f421d. A subsequent live read showed **PR #829 merged at f92873c947e94c76ed3e7ea211db40a985ea3d63**, advancing main and closing A2 on that branch. The structured index and evidence ledger were updated to reflect this. This is the reason the main baseline fields now carry both the initial snapshot and the latest verified tip.

The repair PRs themselves generated additional review findings. I fetched the actual review threads rather than inferring from PR descriptions:

| Repair PR | CodeRabbit threads | Distinct fingerprints | Measured disposition |
|---|---:|---:|---|
| #819 D1 GraphQL exception mapping | 1 | 1 | Thread resolved; PR remains open |
| #821 D4 type-mismatch contract | 3 | 3 | Threads resolved; PR remains open |
| #822 W2 mail origin configuration | 2 | 2 | Threads resolved; PR remains open |
| #823 E1/E4 test evidence | 1 | 1 | Thread resolved; PR remains open |
| #829 A2 commission mirror reversal | 12 | 12 | All threads resolved; PR merged to main |

That is **19 distinct CodeRabbit review fingerprints across 19 threads**. They are not blindly added to the 34+2 root-finding count: they are implementation/refinement findings on the repair PRs, and the ledger registers them as E-33…E-49. #829's 12 are fixed on main; E-43…E-49 are linked to open PR heads and are not marked fixed-main.

### New evidence cases from the repair reviews

- **E-33–E-35:** reverse the posted commission amount, handle asynchronous refund-before-settlement ordering, and distinguish a legitimate zero commission from an unposted settlement.
- **E-36–E-38:** reconcile historical fully refunded intents (including deploy overlap), aggregate one UPSERT row per provider/currency, and advance the optimistic version for native SQL updates.
- **E-39–E-42:** include reversal credits in net-ledger signs and API metadata, remove test-fixture call-order coupling, and bound scheduled reconciliation work.
- **E-43:** null GraphQL exception detail must not break GraphQL error rendering.
- **E-44–E-46:** localize field errors, preserve type-mismatch diagnostics, and prevent MessageFormat from interpreting untrusted fallback data.
- **E-47–E-48:** validate the actual Spring Boot-bound mail origin at startup and reject query/fragment URI components.
- **E-49:** derive the repair-plan summary and counts from the individual gate records.

The original 75-PR count remains the inventory through #833. PR #834 is the new governance implementation PR created after that inventory, and is tracked separately.
