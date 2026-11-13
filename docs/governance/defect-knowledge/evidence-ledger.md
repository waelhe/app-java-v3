# Evidence Ledger — historical incidents reconstructed as causal cases

> Pinned audit heads: `main@7836c157` (2026-10-27) · `integration/saleor-full-commerce-20261021@d5d65451`.
> Each case records: observed failure → original assumption → triggering conditions → causal chain → violated invariant → root cause → correction → verification evidence → recurrence risk → prevention. Status labels: **[VERIFIED]** measured facts; **[CAUSAL]** evidence-supported conclusion; **[HYPOTHESIS]** plausible, unverified; **[OPEN]** unresolved.

## Scale of the inspected history (measured)

| Dimension | Measured value | Source |
|---|---|---|
| PRs inventoried | 715 total = 529 merged + 182 closed-unmerged + 4 open at measurement time · first 2026-04-16 | GitHub API pulls inventory (2026-10-27; re-derive: `gh api repos/waelhe/app-java-v3/pulls?state=all&per_page=100` paginated) |
| Workflow runs classified | 14,575 runs fetched (all workflows, full pagination, cut 2026-10-27T10:54Z) · **2,569 failures total** · seven guard workflows: CI 985 · Integration Test 815 · Container Scan 201 · CodeQL 152 · Production Watchdog 112 · CodeRabbit Gate 102 · Saleor inventory gate 71 (= 2,438) · remaining 131 failures in non-guard workflows (Android surfaces 75 · OpenRewrite family 41 · retired/deploy/experimental 15) · non-failure conclusions: 2,783 cancelled · 729 skipped · 10 action-required | GitHub API actions runs (2026-10-27; re-derive: `gh api repos/waelhe/app-java-v3/actions/runs --paginate`) |
| Failure escalation | Apr 8 → May 132 → Jun 158 → Jul 8 → Aug 6 → Sep 482 → Oct 1,775 failures/month — sums to the 2,569 total | same |
| Commit history | main 706 commits (Apr 89, May 74, Jun 82, Jul 2, Aug 24, Sep 248, Oct 187 — monthly sum = 706) · integration line 785 | git log @ `7836c157` |
| Authorship | 706 = 679 squash-merge commits authored `waelhe` + 22 direct `Z User` (agent) + 4 `dependabot[bot]` + 1 `github-actions[bot]` | git log @ `7836c157` |
| Migrations | 209 files under `db/migration/` on main tip — `marketplace-app` 208 (205 versioned in range V1–V291 with 86 numbers absent in-range + 3 `R__` repeatables) and `marketplace-edge` 1 (its own `V1`, a separate database/history), **zero migration files ever deleted in main history** | git ls-tree + diff-filter=D @ `7836c157` |
| Test/production files | 663 test vs 1,151 production Java files on main tip | git ls-tree |
| Max-churned files | SYSTEM.md ×232, PROJECT_MAP.md ×187, application.yml ×78, pom.xml ×63, migration-checksums.properties ×60, SecurityConfig.java ×59, ObservationCoverageFilesTest.java ×55 | git log --name-only |

---

## E-01 · Applied-migration edit → production checksum outage **[VERIFIED]**

- **Observed failure:** deployment from `d8f8c9e` failed (`55d9b0a4` FAILED, production 502) after PR #307 merged.
- **Original assumption:** "Flyway checksums cover statements only" — editing a *comment* inside an applied migration is safe.
- **Triggering conditions:** PR #307 edited a comment inside applied `V51`; the next deploy validated `flyway_schema_history` against the edited file.
- **Causal chain:** edit → checksum change (applied `-38705425` vs edited `591793313`, measured with Flyway's own `ChecksumCalculator` 12.4.0) → boot-time validation failure → Railway deployment 502.
- **Violated invariant:** applied migration history is immutable.
- **Root cause:** verification gap — CI runs on a **fresh database** that applies the edited file from scratch, so CI stayed green while production failed. Environment-blindness, not carelessness.
- **Correction (commit `0d39e818`, PR #310, 2026-09-14):** V51 restored **byte-identical** (Flyway's first documented remedy; `repair` would rewrite history to match the violation — a patch), plus `MigrationChecksumGuardTest` freezing every `V__*.sql` checksum in `migration-checksums.properties` (generated with Flyway's calculator; `R__` repeatables excluded by design).
- **Verification evidence:** the guard test runs in CI; no migration-edit outage class has recurred since (revert-commit scan post-2026-09-14 shows none).
- **Recurrence risk:** any workflow that touches `db/migration/*.sql` without the guard mindset; new modules adding their own migration trees.
- **Prevention:** rule B1 + enforcement D1 (see enforcement-map).

## E-02 · Env placeholder without deployment-target value → boot collapse **[VERIFIED]**

- **Observed failure:** production `/actuator/health` degraded 503 OUT_OF_SERVICE → full 502 ("Application failed to respond") on all paths after merge #415 (`a1107a99`) reached auto-deploy; staging 502 since arrival; liveness/readiness initially 200 then fell — circular boot collapse.
- **Original assumption:** the new `post-logout-redirect-uri: ${OAUTH_POST_LOGOUT_REDIRECT_URI}` in `application-prod.yml` would resolve because the variable exists somewhere (CI passed with it).
- **Triggering conditions:** the variable was not set on Railway; unresolved placeholder → "Could not resolve placeholder" → boot failure → crash loop.
- **Causal chain:** config coupling — CI environment carried the variable, deployment target did not; green merge → red production.
- **Violated invariant:** every production config placeholder must resolve on the deployment target (or fail fast before traffic).
- **Root cause:** verification-environment blindness (same class as E-01, different dimension: env vars instead of DB state).
- **Correction:** emergency revert `809f6a94` (PR #419, 2026-09-27) restoring production/staging boot; the value became env-driven with explicit default in a later controlled pass.
- **Verification evidence:** the revert commit message carries the measured timeline (geo/tree 200 at `dc10839b` 02:47Z pre-#415; 502 after).
- **Recurrence risk:** every new `${...}` placeholder in prod profiles; every new deployment target.
- **Prevention:** rule B6 (placeholder resolution matrix + fail-fast discipline) — the matrix is now deterministic: `PlaceholderTargetMatrixGuardTest` (D10) requires every no-default env placeholder in the deployed composition to be declared in the versioned target record `.railway/railway.ts` (gap G2 closed; enforcement-map §5). The guard's first matrix run (2026-10-28) measured three names the record lacked — ADMIN_SEED_PASSWORD, OAUTH_POST_LOGOUT_REDIRECT_URI, MAIL_FROM — each live on the target per its carrying PR's evidence (#389, #420, #685); all three declared in the closing commit.

## E-03 · S10 — aggregate health verdict blind in production **[VERIFIED]**

- **Observed failure:** `/actuator/health` aggregate answered **503 DOWN** in production while readiness/liveness were both 200 UP — the platform served traffic while declaring itself down; ~5.9 s per aggregate call.
- **Original assumption:** framework default health aggregation is a sane platform-health verdict; the watchdog's deliberate exclusion of the aggregate is safe.
- **Triggering conditions:** `MailHealthIndicator` on an SMTP host unreachable from the Railway node entered the aggregate by Boot default (`management.health.mail` enabled without an explicit decision); `show-details/show-components: never` hid the failing component.
- **Causal chain:** auxiliary channel inside core verdict by framework default → nobody watched the one point where all contributors meet (watchdog excluded it to avoid a permanently screaming probe) → state older than the first measurement, discovered only by a manual re-verification session; diagnosis required a time fingerprint (5.9 s ≈ SMTP connect timeout 5000 ms) instead of reading the component name.
- **Violated invariant:** every production verdict/aggregation point has an automated consumer; every health contributor is an explicit decision.
- **Root cause:** framework default ≠ architecture decision + blind aggregation point. Full report: `docs/observability/postmortems/2026-09-26-s10-aggregate-health-blind.md`.
- **Correction:** PR #387 (mail out of platform health via the official key `management.health.mail.enabled: false` + `when-authorized` component visibility) then PR #406 (`AggregateHealthGauge` metric + `MarketplaceAggregateHealthDown` alert rule + sixth watchdog probe).
- **Verification evidence:** postmortem §3 timeline with measurements; watchdog probes since run in production.
- **Recurrence risk:** any new health contributor added by a dependency default; any aggregation point without a consumer.
- **Prevention:** rules B6/P9; watchdog probe exists (enforcement-map §3).

## E-04 · Rollback-only poisoning — retryable boundary inside carrier transaction **[VERIFIED]**

- **Observed failure:** dedup records persisted without settlements (referenced as PR #458 class).
- **Original assumption:** a retryable boundary can join the surrounding `REQUIRED` carrier transaction safely.
- **Triggering conditions:** inner failure marked the shared transaction rollback-only; the carrier committed its own writes anyway from the caller's perspective → partial state (dedup without settlement).
- **Causal chain:** retry semantics ⊗ carrier transaction semantics → poisoned commit window.
- **Violated invariant:** a unit of work either fully commits or fully rolls back, *including* the records that guard idempotency.
- **Root cause:** transaction-boundary design — the fix class is `REQUIRES_NEW`/post-commit publication for retryable legs (this is the case behind the regression-list entry and the house pattern "retryable boundary never joins the carrier").
- **Correction & evidence:** corrected in the #458 line; the pattern is now a standing rule in `docs/agent-protocol.md` §9 and CODING_STANDARDS §3; ledger keeps it because the class is subtle enough to return.
- **Recurrence risk:** any new listener/adapter that both writes and retries inside a service transaction.
- **Prevention:** rule B2; Modulith `@ApplicationModuleListener` (AFTER_COMMIT) is the house publication rail (see E-06 for its counter-case).

## E-05 · Response-shape change behind a "compatible" contract diff **[VERIFIED]**

- **Observed failure:** frontend `/inbox` broke when `GET /notifications` moved from a bare array to the `PagedResponseNotification` wrapper — while `openapi-diff` reported compatibility.
- **Original assumption:** OpenAPI contract-level compatibility implies consumer safety.
- **Triggering conditions:** the change altered the response **shape** (wrapper/pagination), a dimension the diff level tolerates but clients do not.
- **Causal chain:** syntactic compatibility ⊕ semantic consumer break.
- **Violated invariant:** consumers depend on shape, not only on schema-level compatibility.
- **Root cause:** contract verification scope too narrow for the change type.
- **Correction:** standing rule in `CONTRIBUTING.md` §4 (measured lesson 2026-09-27): any response-shape change (shape/wrapper/pagination) = frontend notice **before** merge + frontend battery replay (battery 33).
- **Verification evidence:** CONTRIBUTING.md carries the re-verification command for the 128-operation count; the battery exists.
- **Recurrence risk:** every pagination/wrapper refactor; every "additive" field that moves a container.
- **Prevention:** rule B4; enforcement: OpenAPI gate + the procedural notice+battery step.

## E-06 · Audit "fix" that violated framework semantics **[VERIFIED]**

- **Observed failure:** adding `@Transactional` to `BookingExpirationService` (a Modulith `@ApplicationModuleListener`) conflicted with the listener's internal transaction semantics.
- **Original assumption:** "service methods must be `@Transactional`" (a generic audit rule) applies universally.
- **Triggering conditions:** `@ApplicationModuleListener` in Modulith 2.x is internally a `@TransactionalEventListener` — plain `@Transactional` on it is illegal.
- **Causal chain:** checklist-driven change over the pinned framework's actual semantics.
- **Violated invariant:** framework-managed lifecycle semantics are not overridable by generic rules.
- **Root cause:** trusting a generic audit over the official docs of the pinned version.
- **Correction:** revert commit `8715cb2b` (2026-06-11), explicitly labeled "false-positive violation in the audit".
- **Verification evidence:** the revert commit message documents the Modulith 2.x mechanism.
- **Recurrence risk:** every automated/static audit flag on framework-managed code.
- **Prevention:** rule A1/P2 — resolve audit flags against the pinned version's official docs before "fixing".

## E-07 · Cache invalidation deferral reverted **[VERIFIED]**

- **Observed failure:** the AFTER_COMMIT cache-invalidation "improvement" (#174) was fully reverted (`cb11714dd`, 2026-08-29) — BookingService/UserService changes plus the new `CacheInvalidationRelay`/`CacheInvalidationMetrics` classes removed.
- **Original assumption:** deferring invalidation to AFTER_COMMIT is strictly safer (read-your-writes vs consistency trade-off underestimated).
- **Triggering conditions:** [CAUSAL] the deferral opened a stale-read window / interaction cost that outweighed the benefit in the affected paths; the revert restored prior behavior wholesale.
- **Violated invariant:** cache invalidation strategy must be decided per read-path consistency requirement, not globally.
- **Root cause:** [CAUSAL] global application of a conditional pattern (B2 family) without per-path analysis.
- **Correction:** full revert; the lesson stands as a conditional rule, not a universal one.
- **Recurrence risk:** medium — the "obviously safer" pattern remains attractive.
- **Prevention:** rule B2 applies-with-analysis; recorded precisely because a reverted fix is knowledge.

## E-08 · Refund idempotency key derived from mutable state **[VERIFIED]**

- **Observed failure (CodeRabbit agent scan 2026-10-26, row 35):** a refund executed at the provider inside a resolving transaction that rolled back; the webhook then synced cumulative state; the state-derived dispute-refund key *slipped*, so a retry would re-issue the provider's already-executed refund instead of returning the same result — a double-payment window.
- **Original assumption:** deriving the idempotency key from current dispute state identifies the operation uniquely.
- **Triggering conditions:** refund executed at PSP → local transaction rollback → external effect already fired → state changes → key changes → retry treated as a *new* refund.
- **Causal chain:** mutable-state-derived identity for an operation whose external effect is not transactional.
- **Violated invariant:** one business operation = one stable idempotency identity, frozen at first execution.
- **Root cause:** idempotency design (the recurring #458 family in a new costume).
- **Correction (commit `ffbdcbd7`, 2026-10-26):** `StableRefundScope` + `disputeScopedRefundIdempotencyKey` — key = dispute identity with no amount/state; amount frozen at first execution; retries answer with the same key. The V230 return-path key form preserved byte-for-byte.
- **Verification evidence:** fix commit cited in `docs/governance/coderabbit-agent-findings-2026-10-26.md` with the literal finding scenario.
- **Recurrence risk:** every external-effect operation keyed by state (captures, payouts, exports).
- **Prevention:** rule B2 (stable-key clause).

## E-09 · Content-purge adapter family — same rule, many doors **[VERIFIED]**

- **Observed failure:** the CodeRabbit deep scans (2026-10-25: 35 findings; 2026-10-26: 46 findings) returned finding after finding in one family: GDPR-style content purge/withdrawal adapters (knowledge, provider-profile, community, disputes, booking, search mirrors) each missing parts of the same pattern — tombstone columns (`is_deleted` filter absent → purge of dead text as if live), ownership-derived scoping, mirror-table coverage, event publication for index withdrawal, optimistic `version = version + 1` inside purge statements.
- **Original assumption:** each adapter written per-module is complete when it handles its own table.
- **Triggering conditions:** the business rule ("an author's withdrawal must remove/withdraw their content everywhere, atomically enough, across mirrors and search indexes") spans modules; per-adapter implementations drift.
- **Causal chain:** rule spans N entry points → each implementer closes the nearest subset → scanners (correctly) keep finding the residual doors.
- **Violated invariant:** one business rule = one enforcement choke point (or an exhaustive enumerated coverage).
- **Root cause:** incomplete enforcement across alternate entry points + derived-data consistency.
- **Correction:** 14 fixes in the 2026-10-26 session (`ec29597c`, `38e8bf8d`, `ffbdcbd7`, `8b9fcdaa`, `91094558`+follow-up, `70a0764b`, `d1d047f0`, `ae0f04ba`, `82d610d2`, `4028764c`…) — each closing its door: shared purge SQL shapes, ownership-derived author IDs (live users by `users.subject = created_by`; anonymized ones via the Envers mirror), `KnowledgeEntryWithdrawnEvent` blocking late republication, `version = version + 1` with integration proof on real PostgreSQL (`DisputesModuleIntegrationTest`).
- **Verification evidence:** the findings registry documents each closure with its commit and test.
- **Recurrence risk:** high — the family grows with every new content-bearing module.
- **Prevention:** rules B3/B5 + the adapter-family checklist in review-protocol; CodeRabbit repository scans (D6) as the detector.

## E-10 · Session interruption → partial commit → broken module **[VERIFIED]**

- **Observed failure:** commit `91094558` carried **only the test files** of a change; its implementation (migration `V286`, the `NeighborhoodPoll.authorId` field, service, purge adapter, seeds) was lost in a session interruption — `marketplace-community` test compilation broke entirely (measured: `testCompile` failure on head `44ba1e75`).
- **Original assumption:** incremental commits of a change-in-progress are harmless; the remainder lands soon.
- **Triggering conditions:** long-running agent session interrupted mid-batch; tests pushed without their production counterpart.
- **Causal chain:** non-atomic commit → red build on the branch → repair batch required (the implementation landed later as `fix(community)`; additionally the original adapter test "was written without alias and never ran at all").
- **Violated invariant:** a pushed commit compiles and passes its own tests (atomicity of concern).
- **Root cause:** interrupted/partial agent operation + a test that was never executed guarding nothing.
- **Recurrence risk:** structural — sessions *will* be interrupted (the owner's standing "execute on GitHub immediately" protocol exists precisely for this).
- **Prevention:** rule A5 (commit atomicity + verify the exact tree) + rule P7 procedures; the never-executed-test lesson feeds review-protocol (test must be proven to run/fail first).

## E-11 · Saleor root-contract deviations — name parity ≠ semantic parity **[VERIFIED]**

- **Observed failure:** the Saleor Root Contract Gate failed on the commerce wave with two real deviations from the pinned Saleor 3.23.43 contract: `menuItemCreate` (two arguments `menu:ID!`/`parent:ID` outside the input instead of fields inside `MenuItemCreateInput`) and `pageTypeUpdate` (`id:ID!` instead of `id:ID`) — both undocumented in `saleor-contract-divergences.csv`.
- **Original assumption:** root/field name parity with the reference schema is contract fidelity.
- **Triggering conditions:** SDL hand-alignment focused on names; argument shapes and nullability semantics diverged silently.
- **Causal chain:** syntactic parity masked semantic divergence — exactly the class the canonical plan warns about ("name-level match is not semantic completeness").
- **Violated invariant:** every GraphQL root contract matches the pinned reference in *shape*, not only in names; every divergence is registered with owner/tests/approval.
- **Root cause:** incomplete contract verification scope (names only) for hand-aligned SDL.
- **Correction (commits `fdf55b2` → `7ba90a1` → `fb3f831` → `afcf8e8` → `a9483b44`/`1c8b848`, 2026-10-24/25):** SDL aligned to source, DataFetchers read from the input object, contract tests, an input-object guard, nullable-behavior fix + its test; verified live in the head SDL.
- **Verification evidence:** gate green on subsequent heads (round-13/14 audit of PR #723); the two roots now match the upstream schema.
- **Recurrence risk:** every new hand-aligned SDL fragment.
- **Prevention:** rule C1 + enforcement D3 (Root Contract Gate).

## E-12 · Documentation numbers drift from derived reality **[VERIFIED]**

- **Observed failures (three independent instances + four caught by the guard's own construction, round 21):**
  1. `SYSTEM.md` stated 183 integration-selected files while the derivation said 184 (new CMS contract tests raised the count) — `DocumentationNumbersGuardTest` failed the build (closed by commit `2a13d192`, 2026-10-25/26).
  2. PR #723 body claimed inventory 107/294/57 while every head since `2e997f3` measures **163 INTEGRATED / 238 NOT_STARTED / 57 BLOCKED** (round-13 audit).
  3. Campaign reports: `campaign-b6-b` counts 98 ≠ 90; completion-map 57 ≠ 56 (CodeRabbit 2026-10-26 Major findings).
  4. Round 21 (G1 closure, guard construction at `c1ef867b`): four more campaign-report drifts proven against the ledger — completion-map P3 wave 55 vs the ledger's 59 (the row's own family cells sum to 59); the fb23ad4c family cell "type assignments 4" vs the ledger's 3 (5+8+5+3+5+6+15+3+2=52); `campaign-b6-a` packet line "28 Query roots" vs its own table's 27 and its ledger-line delta 27; `campaign-b6-b` split sentence 37/50/11 (sums to 98) vs the provable 22/57/11 (sums to 90; its own INTEGRATED list names 22 roots — verified against the historical ledger fetched at the b6-a close `edb4a725`: 22 INTEGRATED all Query, 42 blocked all Query).
- **Original assumption:** hand-typed numbers beside evolving truth stay in sync.
- **Triggering conditions:** every merge that changes a derived count without re-deriving the documented one.
- **Causal chain:** derived truth moves; prose doesn't; readers act on the prose.
- **Violated invariant:** load-bearing numbers are derived, never hand-maintained.
- **Root cause:** missing guard coverage per document (guard exists for SYSTEM/ARCHITECTURE/README, not for campaign reports/PR bodies).
- **Correction:** guard test (D2) covers the canonical files; rulebook extends the discipline; PR-body numbers carry measured date + re-derivation command. Round 21 closed the campaign-reports residual: `CampaignReportsGuardTest` (7 tests, `c1ef867b`) derives every campaign-report count from the 458-root ledger CSV at test time and verifies the four drifts' fixes.
- **Verification evidence:** guard failure → fix measured in round-14 audit; round 21 — the same guard logic run against the pre-fix files fails on exactly the four drifts and passes on the fixed files (harness-first, before the commit).
- **Recurrence risk:** every new document with counts.
- **Prevention:** rules A6/D2; enforcement map records which documents remain unguarded (gap).

## E-13 · CodeRabbit Gate structurally failing while looking "deployed" **[VERIFIED]**

- **Observed failure:** CodeRabbit Gate: 264 runs, only 29 successes, 102 failures, 133 other — four manual activations answered "Review rate limited"; no complete CodeRabbit review existed on any wave head for days.
- **Original assumption:** installing the gate workflow = having independent review.
- **Triggering conditions:** PR #723 was a **draft** while the configuration had `drafts:false`; the service itself had a documented outage/rate-limit window that day.
- **Causal chain:** config mismatch (drafts) ⊕ external service limits → the gate existed on paper, produced nothing.
- **Violated invariant:** a gate that structurally cannot run for the repo's actual PR workflow is not a gate.
- **Correction:** `d5b89acd` enabled `reviews.auto_review.drafts:true` (the round-13 recommendation); the first complete review then arrived — 20 actionable documentation-scope findings, zero on production Java code.
- **Verification evidence:** round-14 audit (CodeRabbit CHANGES_REQUESTED with the 20 findings; CI green on subsequent heads).
- **Recurrence risk:** medium — external-service gates depend on their provider.
- **Prevention:** rule D6-adjacent: gate health itself is monitored. **Implemented and proven in CI (round 21, G5 closed)**: `gate-health.yml` on the integration branch — per-gate health over a 7-day window, every recent failing CodeRabbit Gate run classified provider-refusal vs content, RED at ≥3-of-5 provider refusals, watchdog treated as an alarm, independent-review freshness per open PR. First real run (`40599532660` on `6e111689`): RED on the live provider outage with the explicit "NOT a code failure" remedy — E-13's exact form, now visible instead of assumed away. The monitor's own embedding was hardened the same way this case teaches: its activation health was measured, not assumed (dispatch 404 without default-branch presence; pull_request runs impossible on the conflicted PR; the Python source rides a quoted heredoc after the first embedding made bash execute the docstring).

## E-14 · The wave revert — code rollback with forward-only migrations **[VERIFIED]**

- **Observed failure:** PR #712 (the full-commerce wave, 409 files) merged to main (`29d543c5`, 2026-10-23 15:03Z) then was reverted by PR #715 (400 files, +333/−76,966) — while all Flyway migrations **stayed forward-only**; PR #723 later reinstalled the wave on the integration branch for review.
- **Original assumption (by the original merge):** the wave was ready for main (later judged otherwise by the owner).
- **Triggering conditions:** production-applied migrations cannot be rolled back; the only safe revert is code-only.
- **Causal chain:** merge decision → owner revert decision → forward-only constraint respected by construction (revert commit message documents it explicitly).
- **Violated invariant (preserved, not violated):** applied migration history immutable even under rollback pressure.
- **Root cause:** n/a — this case is recorded as a *successful* execution of the rollback discipline; the reusable knowledge is the revert playbook (code rollback, migrations stay, reinstall via PR for review).
- **Verification evidence:** revert commit `7bc2f5e5` message; round-13 diff measurement: zero `.sql` files in the PR #723 delta; 225 migrations present.
- **Recurrence risk:** any future wave-level rollback.
- **Prevention:** rule B1 (forward-only clause) + this recorded playbook.

## E-15 · Early agent PRs reverted at repository birth **[VERIFIED]**

- **Observed failures:** April 2026: "Revert 'Codex-generated pull request'" (#32), revert of a docs PR (#18), and July-era revert of `--batch-mode` CI fixes — the first agent-quality incidents, predating all governance.
- **Original assumption:** generated PRs are reviewable by label alone.
- **Triggering conditions:** early-era agent output merged/reverted without structured review.
- **Causal chain:** unreviewed generation → revert cycles.
- **Root cause:** no review protocol existed; the answer became the governance stack (three merge gates, CodeRabbit activation, this knowledge system).
- **Recurrence risk:** low for the same form (gates exist) — but the *class* (plausible-local generation without downstream understanding) is the umbrella pattern P8.
- **Prevention:** the whole change+review protocol; history shows the class pre-dates everything else.

## E-16 · External dependency limits hitting production deploys (Neon quota wall) **[VERIFIED]**

- **Observed failures:** PROJECT_MAP documents the Neon external quota wall struck **three documented times** during deploy waves (2026-10-17 era) with "zombie production" states; the production channel ultimately moved to the second Neon project (`ep-wandering-union-b4rfa3cq…`, PostgreSQL 18.6) recorded in SYSTEM.md §15.
- **Original assumption:** the managed-database quota headroom matches deployment cadence.
- **Triggering conditions:** high-frequency deploys × per-deploy connection/schema operations.
- **Causal chain:** external limit → deploy fails mid-chain → platform state diverges from repo state.
- **Violated invariant:** deployment automation must distinguish "code failed" from "provider said no".
- **Root cause:** external-provider capacity semantics invisible to the build.
- **Correction:** channel migration + watchdog/deploy diagnostics; runbook §11 (option ج) recorded.
- **Verification evidence:** SYSTEM.md §15 production log with dates and SHAs.
- **Recurrence risk:** medium — every new provider limit class (Railway, Neon, PSPs).
- **Prevention:** rule B6-external (deployment failure triage distinguishes provider refusals) — procedural.

## E-17 · Password-column lost-update window **[VERIFIED]**

- **Observed failure (CodeRabbit scan 2026-10-26, row 46):** the password-reset path re-wrote the *whole* user snapshot via `updateUser`, so an admin role elevation landing between load and encode could be silently reverted.
- **Original assumption:** full-row update is the conservative choice.
- **Triggering conditions:** concurrent admin role change during a password reset transaction.
- **Causal chain:** read-modify-write of a shared row over a column that only one flow owns.
- **Violated invariant:** each writer owns its column set; concurrent writers must not overwrite each other's columns.
- **Root cause:** ownership boundary at the persistence seam.
- **Correction (commit `ec29597c`):** explicit write of the password column alone (the surgical form; the framework's `UserDetailsPasswordService.updatePassword` delegates to the same `updateUser` so no official narrower path exists), flags/authorities untouched by this surface, rotations preserved (`AccountCredentialsRotated`).
- **Verification evidence:** findings registry entry with commit.
- **Recurrence risk:** every read-modify-write of wide shared rows.
- **Prevention:** rule B3-ownership clause; review checklist item (lost-update scan).

## E-18 · Numeric representation limits (BigDecimal exponent attacks) **[VERIFIED]**

- **Observed failures (CodeRabbit 2026-10-26, rows 1 & 39):** values like `1e-100000000` passed magnitude comparisons and then forced construction of enormous divisors/scale arithmetic — resource exhaustion behind valid-typed inputs.
- **Original assumption:** type validity (`BigDecimal`) bounds representation cost.
- **Triggering conditions:** exponent-heavy input at price/coordinate/multiplier endpoints.
- **Causal chain:** valid type → unbounded internal representation → CPU/memory exhaustion.
- **Violated invariant:** input representation is bounded before computation.
- **Correction (commit `4028764c`):** bounded-scale gates before any arithmetic (`MAX_COORDINATE_SCALE = 32`, `MAX_MULTIPLIER_SCALE = 16`); graceful zeros remain legal.
- **Verification evidence:** findings registry entries with the gate commits.
- **Recurrence risk:** every decimal endpoint.
- **Prevention:** rule B7; review checklist numeric-bound item.

## E-19 · Offset pagination at storefront scale → keyset migration **[VERIFIED]**

- **Observed failure (CodeRabbit root-level review notes on the product-list PR):** offset pagination on the storefront product list degraded under scale/deep pages.
- **Original assumption:** page-number pagination suffices.
- **Correction:** P0 keyset pagination foundation merged (#677) after adopting the review's root notes; the generalized `KeysetCursorGate` now guards cursor contracts.
- **Verification evidence:** PROJECT_MAP wave entry; gate in the tree (`marketplace-app/.../graphql/KeysetCursorGate.java`).
- **Prevention:** rule B4-cursor clause (cursor shape changes ride the gate).

## E-20 · False claims and dead config removed pre-merge **[VERIFIED]**

- **Observed failure (2026-09-25, #383 line):** a CVE-gate round exposed a **false claim** in a PR description and a **dead configuration** (config that could never execute) — both removed before main.
- **Original assumption:** described behavior ≡ configured behavior.
- **Root cause:** narrative-code divergence — the exact class this ledger exists to close.
- **Prevention:** rule A5 (status reported only with bound evidence at the exact SHA); CodeRabbit as independent detector.
- **Status:** closed at #383; kept as the earliest governance success case.

## E-21 · Guard message promising a mechanism that does not exist **[VERIFIED]**

- **Observed failure (round-13 audit, 2026-10-25):** a failing guard's message instructs "register an approved decision" — but the guard script reads no decisions registry; no exception mechanism exists at all.
- **Original assumption:** the message describes a real escape path.
- **Violated invariant:** a guard's failure message must describe an implementable remedy (or the guard must implement it).
- **Root cause:** message/behavior drift inside the enforcement layer itself.
- **Status:** closed at `0edf0f6` on the integration branch (PR #723, 2026-10-27, owner-ordered governed code change): the Saleor root-signatures guard now reads the registered-divergence CSV — an APPROVED row naming the failing surface is honored and reported in the step summary, unapproved rows never excuse anything, and an APPROVED row with a PENDING decision record fails the gate (registry integrity, fail-closed). The failure messages now describe the real remedy. Other guards keep no general register; rule D8 stays documented for them.
- **Prevention:** rule D8 (guard-message fidelity).

## E-22 · High-churn sentinel: SYSTEM.md ×232 / PROJECT_MAP.md ×187 — churn measured **[VERIFIED]** · causal reading **[HYPOTHESIS]**

- **Observed fact:** the two truth files are the most-modified paths in history (232/187 changes) — truth-sync runs after every wave, as designed.
- **Hypothesis (not promoted to a rule):** churn volume correlates with the truth-sync debt incidents documented in the regression list ("PROJECT_MAP/SYSTEM sections lagging merged waves"). The *measured* debt incidents are already covered by E-12's rule family; the churn itself is the *cost* of the design, not a defect.
- **Status:** recorded to prevent misreading churn as pathology.

## E-23 · Single-line sources defeat the fail-closed recipe publication guard **[VERIFIED]**

- **Observed failure:** OpenRewrite Auto Apply red on three consecutive main pushes — runs `40285298597` (`dfd011bc`, 2026-10-26), `40479098312` (`7836c157`, 2026-10-27), `40626928859` (`a908e114`, 2026-10-28) — each failing at the "Verify patch content and exact planned paths before publication" step with `Non-import source change in …automatic-rewrite.patch; refusing publication.`
- **Original assumption:** carried-in sources (the security carries at `7836c157` and `a908e114`) are ordinary Java — compile gates cover them.
- **Triggering conditions:** thirteen files arrived with package, imports, and declarations packed onto single lines (nine in `marketplace-delivery`, two in `marketplace-ledger` main plus one ledger test, one in `marketplace-provider`; twelve measured by code search and confirmed file-by-file, the thirteenth — `DeliveryModule.java`, whose `; import` spacing escaped the search patterns — caught by `SourceFormatGuardTest`'s own first CI run). `RemoveUnusedImports` — the approved baseline recipe — replaces partially-used wildcard imports with explicit ones; in a single-line source every such change rewrites the declaration line too, so the patch carries non-import source changes and the publication guard refuses the whole batch.
- **Causal chain:** carried/generated code bypassed layout conventions → no guard owned the format dimension → the line-based machinery inherits the blindness → the fail-closed guard refuses correctly → the workflow stays red while the root persists.
- **Violated invariant:** every source file's physical layout stays processable by the repo's line-based automated machinery.
- **Root cause:** unguarded format dimension on carried-in sources — not a recipe defect and not a guard defect; both behaved exactly as designed.
- **Correction:** the thirteen files reformatted to standard layout (tokens preserved — no semantic change; wildcards kept for the automation to expand as designed) plus `SourceFormatGuardTest` (D9) rejecting packed package/import lines repo-wide.
- **Verification evidence:** the guard runs in CI from this commit; the next Auto Apply run over a touched delivery/ledger/provider module produces an import-only patch the publication guard accepts.
- **Recurrence risk:** medium — every carry pasting generated sources re-opens the surface; the guard converts the recurrence into a test failure on the carrying PR itself.

## E-24 · Opposite lock order creates an order/delivery ABBA deadlock [VERIFIED]

- **Observed defect (issue #749, 2026-10-28):** delivery paths acquire the order row and delivery-request rows in opposite orders. addLegForOrder takes the order row first; cancellation takes a delivery-request row first and then the order row. Two concurrent transactions can form an ABBA cycle; PostgreSQL aborts one transaction, surfacing a retryable user-visible failure.
- **Violated invariant:** all paths touching the same shared rows must acquire locks in one global order.
- **Root cause:** lock order was reasoned about per method rather than across the complete call graph.
- **Required correction:** resolve the leg/order identity without a lock, acquire the order lock, re-read/revalidate the leg under the global order, then write. Add a real-PostgreSQL interleaving test.
- **Status:** OPEN — issue #749 remains open in the measured snapshot; no repair PR was verified.
- **Prevention:** A3/B2 + P11; sibling-path lock-order table and two-transaction integration proof.

## E-25 · Outbound routing HTTP call holds a database transaction and order lock [VERIFIED]

- **Observed defect (issue #749, 2026-10-28):** routes.route calls the OSRM provider after the order row's FOR UPDATE lock is taken, inside a class-level transactional method. The order lock and DB connection remain occupied for the network round-trip. A related fulfillment listener also performs route lookup while a transaction is open.
- **Violated invariant:** slow external I/O must not extend a shared-row lock or database transaction unless an explicitly measured consistency requirement demands it.
- **Root cause:** provider route computation and persistence were combined in one transactional operation.
- **Required correction:** calculate the route before entering the persistence transaction; keep transaction scope to local reads/writes and validate the snapshot used.
- **Status:** OPEN — issue #749 remains open in the measured snapshot.
- **Prevention:** A3/B2 + P11; test that route lookup happens before transaction/lock acquisition.

## E-26 · CodeRabbit review poll and review-coverage verifier disagree [VERIFIED]

- **Observed defect (PR #826 follow-up, 2026-11-12):** the CodeRabbit review poll accepted an approval with an empty review body, while the exact processed-file coverage verifier rejected the same review because it contained no processed-file evidence. The composed gate could remain blocked after review threads had been resolved.
- **Violated invariant:** a composed release gate must use one coherent acceptance predicate for the same review artifact.
- **Root cause:** the poll and coverage verifier independently interpreted the review submission with different acceptance rules.
- **Correction/status:** an operational workaround is recorded in #826; **structural repair remains OPEN**. Do not treat the workaround as closure.
- **Prevention:** P13; share one parser/verdict contract and run the same negative corpus through both gate consumers.

## E-27 · OpenRewrite uses an ambient Maven version instead of the pinned wrapper [VERIFIED]

- **Observed defect (PR #813, merged 2026-11-12):** OpenRewrite catalog automation invoked system Maven 3.9.15 while the repository's Maven Enforcer required Maven 3.10 or later; this produced automation failure unrelated to source correctness.
- **Violated invariant:** repository automation must execute through the repository's declared and pinned toolchain.
- **Correction:** PR #813 changed the OpenRewrite workflow to use Maven Wrapper. Merge commit 445f421d67ad58bd533d42be9d5bbf6d268d9161 is the current main tip.
- **Status:** FIXED ON MAIN; the commerce candidate must still be checked for the same workflow path before release.
- **Prevention:** P14; use the wrapper and fail if automation silently falls back to ambient Maven.

## E-28 · Purge version-advance proof required real PostgreSQL [VERIFIED]

- **Observed defect (PRs #761/#762/#764, 2026-10-29):** a purge adapter's optimistic-version advancement had a test pin, but the concurrency claim required proving that a stale writer is rejected by the real database and that a repeated purge is idempotent. The first test pin did not provide that complete proof.
- **Violated invariant:** a concurrency test must run the database behavior it claims to protect, against the real dialect/transaction semantics.
- **Correction:** #764 added Testcontainers PostgreSQL evidence for version advancement, stale-write rejection and idempotent replay. The PR merged to main.
- **Status:** FIXED ON MAIN as a test-evidence strengthening; the adapter family's further coverage is recorded separately in #817/#824.
- **Prevention:** P12; test the stale concurrent write and replay path against PostgreSQL rather than relying only on SQL-shape assertions.

## E-29 · OpenRewrite catalog job turns Dependabot PRs into manual-only failures [VERIFIED]

- **Observed defect (PR #770, merged 2026-10-30):** the catalog audit ran on Dependabot dependency PRs matching its path filters, but the bot lacked the required secret-backed execution path. Those PRs became permanently red until manual administrator intervention.
- **Violated invariant:** an automated workflow must explicitly handle every actor/event class that its trigger admits.
- **Correction:** #770 added a Dependabot skip for the catalog audit; the PR merged to main.
- **Status:** FIXED ON MAIN.
- **Prevention:** P14; exercise Dependabot, human PR, push and manual-dispatch event classes in workflow tests.

## E-30 · Vendored agent skills lacked reviewed provenance pins [VERIFIED]

- **Observed defect (PR #771, merged 2026-10-30):** nine vendored skills were present without a reviewed pin/provenance registry, allowing agent behavior to change without the repository's ordinary reviewed dependency record.
- **Violated invariant:** agent instructions and downloaded automation resources are versioned dependencies and must have reviewed provenance.
- **Correction:** #771 introduced the pinned skills surface and adopted saleor-core under governance.
- **Status:** FIXED ON MAIN.
- **Prevention:** P14; skill updates move only through the reviewed pin-and-registry process.

## E-31 · BCrypt reset limit measured in characters while implementation limit is bytes [VERIFIED]

- **Observed defect (PR #758, merged 2026-10-29):** password-reset validation measured UTF-16 string length, while the BCrypt implementation rejects inputs beyond its 72-byte UTF-8 boundary. Multi-byte input could therefore pass validation and fail later in the encoder.
- **Violated invariant:** validation must use the representation boundary enforced by the downstream cryptographic implementation.
- **Correction:** #758 added a UTF-8 byte bound before consuming the one-time reset token; tests cover multi-byte input and token preservation on rejection.
- **Status:** FIXED ON MAIN.
- **Prevention:** P12/B7; test encoded byte lengths, not only Java character counts.

## E-32 · JPEG marker scanner treated legal fill bytes as a new marker [VERIFIED]

- **Observed defect (PR #758, merged 2026-10-29):** the JPEG scanner interpreted repeated 0xFF fill bytes permitted by ITU-T T.81 as a marker boundary, causing a false length jump and loss of the actual SOS marker.
- **Violated invariant:** parsers must implement the input standard's legal framing forms, including padding/fill bytes, rather than treating a canonical sample as the only valid encoding.
- **Correction:** #758 updated marker scanning and added regression coverage for legal fill bytes.
- **Status:** FIXED ON MAIN.
- **Prevention:** P3/P12; include standards-permitted edge encodings in parser tests.


## E-33 · Refund reversal must read the commission actually posted, not recompute a live rate [VERIFIED]

- **Observed defect (PR #829 review, 2026-11-12/13):** legacy payment intents can have no frozen commission rate. Recomputing the reversal from the current configuration may credit a different amount from the original COMMISSION_DEBIT if the live rate changed.
- **Violated invariant:** a reversal mirrors the original posted ledger entry byte-for-byte in amount/currency/provider; it does not re-price a completed transaction.
- **Correction:** #829 reads the posted commission debit for the intent and reverses that amount. The merge commit is f92873c.
- **Status:** FIXED ON MAIN.
- **Prevention:** B2/C2 + P4/P12; tests must cover legacy rows with no frozen rate and a configuration change after settlement.

## E-34 · Asynchronous refund event can precede settlement commission posting [VERIFIED]

- **Observed defect (PR #829 review):** both COMPLETED and REFUNDED are handled asynchronously. If REFUNDED runs first, a missing commission row can be treated as zero, allowing the refund event to complete without its commission reversal. The later settlement event then leaves an unreversed debit.
- **Violated invariant:** absence of a posted commission is not evidence that the correct commission is zero; externally consequential event processing must remain replayable until prerequisites are durable.
- **Correction:** #829 added a durable settlement-credit check and fails closed/retries when settlement has not posted; test covers a refund arriving first.
- **Status:** FIXED ON MAIN.
- **Prevention:** B2 + P4/P12; test both event orders and prove retry/publication recovery.

## E-35 · Zero commission must be distinguished from an unposted settlement [VERIFIED]

- **Observed defect (PR #829 review):** a legitimate zero commission (zero configured rate or rounding to zero) creates no COMMISSION_DEBIT row. A guard that equates this absence with an unposted settlement can make the refund retry forever.
- **Violated invariant:** zero-valued business outcomes and not-yet-committed outcomes are different states and require separate durable evidence.
- **Correction:** #829 uses the posted payment-credit signal to distinguish a completed zero-commission settlement from one whose settlement event has not yet landed; the reversal is a no-op when the posted amount is zero.
- **Status:** FIXED ON MAIN.
- **Prevention:** B2/C2 + P4; test configured zero, rounded zero, and not-yet-posted cases independently.

## E-36 · Historical full refunds require reconciliation, including rolling-deploy overlap [VERIFIED]

- **Observed defect (PR #829 review):** adding a new reversal-entry enum does not repair full refunds already completed before the runtime fix, because completed Modulith publications are not replayed. A backfill run while an old application instance is still processing refunds can also miss a refund committed after the migration's snapshot.
- **Violated invariant:** data migrations and event-processing deployments must converge both historical rows and writes that overlap the rollout; a successful Flyway migration alone is not proof of financial convergence.
- **Correction:** #829 added an idempotent historical backfill and a post-stop convergence sweep for stragglers after old instances are stopped.
- **Status:** FIXED ON MAIN through #829. Operational execution of the migration/sweep must still be evidenced by deployment reconciliation; the code merge alone is not proof that production data was reconciled.
- **Prevention:** B1/B2 + P1/P4/P8/P12; define pre-deploy, overlap and post-stop reconciliation phases for money migrations.

## E-37 · One PostgreSQL UPSERT cannot update the same balance row twice in one statement [VERIFIED]

- **Observed defect (PR #829 review):** a backfill that emits two commission reversals for the same provider/currency can return two source rows with the same conflict key. PostgreSQL ON CONFLICT DO UPDATE rejects a single command that would affect one target row more than once.
- **Violated invariant:** a set-based upsert must reduce its source to at most one row per unique conflict key before updating the target.
- **Correction:** #829 aggregates inserted reversal amounts by provider and currency before the provider_balances UPSERT; review evidence includes the two-deserving-refunds same-balance scenario.
- **Status:** FIXED ON MAIN.
- **Prevention:** B2 + P12; every data-modifying CTE with ON CONFLICT must test repeated source keys for the same target row.

## E-38 · Raw SQL balance reconciliation must advance the JPA optimistic version [VERIFIED]

- **Observed defect (PR #829 review):** a raw UPSERT changed available_cents but not the ProviderBalance @Version column. A concurrent payment could save its stale entity with the unchanged version and overwrite the reconciliation amount; the reversal row would already exist, so a later replay would not repair the balance.
- **Violated invariant:** every writer of a versioned row, including native SQL and migrations, participates in the same optimistic-lock protocol.
- **Correction:** #829 increments version on conflict updates and initializes it for inserted balance rows; the runtime reconciliation query was updated consistently.
- **Status:** FIXED ON MAIN.
- **Prevention:** B2 + P4/P12; concurrency tests must interleave native reconciliation with an ORM writer.

## E-39 · Net-ledger aggregation must classify a reversal credit as positive [VERIFIED]

- **Observed defect (PR #829 review):** the net-by-currency query treated every entry except PAYMENT_CREDIT as a debit. A new COMMISSION_REVERSAL_CREDIT was therefore subtracted, returning a non-zero net after a correctly refunded payment.
- **Violated invariant:** ledger read models must classify each entry type by its accounting sign, not by a single positive-type exception.
- **Correction:** #829 updated the aggregate classification and its real query-level test; the merge is on main.
- **Status:** FIXED ON MAIN.
- **Prevention:** C2 + P3/P12; every ledger enum addition triggers a search of all sums, balances, DTOs and export/read models.

## E-40 · Ledger response documentation must classify reversal credits as positive [VERIFIED]

- **Observed defect (PR #829 review):** the ledger response description grouped COMMISSION_REVERSAL_CREDIT with negative entries although the API returns it as a positive credit. A downstream statement consumer following the description would calculate a wrong balance.
- **Violated invariant:** API descriptions and consumer-facing metadata must agree with the actual sign and semantics of the wire value.
- **Correction:** #829 updated the response description and kept commission/refund debits negative.
- **Status:** FIXED ON MAIN.
- **Prevention:** B4/C2 + P3/P6; contract review includes field meaning and arithmetic sign, not only type/name.

## E-41 · Shared sequential mocks are not deterministic across asynchronous event consumers [VERIFIED]

- **Observed defect (PR #829 review):** the integration test's PaymentIntentLookupPort mock used sequential answers. Delivery and ledger listeners both consumed the same mock, so the expected response depended on which event consumer called first.
- **Violated invariant:** a test double must model the dependency by input/phase, not incidental invocation count when multiple consumers share it.
- **Correction:** #829 changed fixture setup to phase-specific state and deterministic lookups.
- **Status:** FIXED ON MAIN.
- **Prevention:** A2/P12; multi-listener integration tests use state keyed by intent/event and assert independent consumer outcomes.

## E-42 · Scheduled reconciliation must not scan every payment intent indefinitely [VERIFIED]

- **Observed defect (PR #829 review):** a periodic commission-reversal reconciliation began from all payment_intents and computed multiple UUID hashes per row on each run, making work scale with the entire table rather than the outstanding reconciliation set.
- **Violated invariant:** recurring repair jobs must bound their scan to eligible work and measure database cost as the source table grows.
- **Correction:** #829 narrowed the reconciliation query to eligible intents and addressed the repeated-scan performance concern before merge.
- **Status:** FIXED ON MAIN; production query cost still requires measured EXPLAIN/operational telemetry at realistic cardinality.
- **Prevention:** B6/C2 + P1/P12; periodic database repair jobs require a bounded predicate and an explainable access path.

## E-43 · GraphQL error builder must not receive a null message [VERIFIED]

- **Observed defect (PR #819 review):** the shared problem-detail GraphQL resolver passed exception.getMessage() directly to GraphqlErrorBuilder.message. A null detail can cause the error resolver itself to fail, losing the canonical taxonomy.
- **Violated invariant:** error translation must be total for every valid exception state; the error handler must not fail while handling an error.
- **Correction/status:** a fallback message was requested and the review thread resolved on PR #819. The PR remains open in the measured snapshot; **not fixed on main yet**.
- **Prevention:** B4/P3/P12; include null detail and malformed optional fields in exception-mapping tests.

## E-44 · Validation field errors must be localized using the request locale [VERIFIED]

- **Observed defect (PR #821 review):** VAL-001 fieldErrors[].message was constructed directly in English even when the request locale was Arabic.
- **Violated invariant:** every user-facing part of a localized API error follows the resolved request locale.
- **Correction/status:** #821 moved field-error rendering into message bundles and added Arabic/English assertions; its review thread is resolved, but the PR remains open.
- **Prevention:** B4/P3; test the entire error payload under every supported locale, not only the top-level detail.

## E-45 · Type-mismatch ProblemDetail must preserve rejected field/value/type [VERIFIED]

- **Observed defect (PR #821 review):** the generic VAL-001 detail key rendered only "Validation failed" and discarded the field, rejected value and expected type from the detail field, even though fieldErrors carried part of the information.
- **Violated invariant:** the canonical error contract preserves the diagnostic data promised to clients without exposing secrets.
- **Correction/status:** #821 added a type-mismatch-specific detail message and tests against the real MessageSource; the review thread is resolved but the PR remains open.
- **Prevention:** B4/P3; assert the rendered ProblemDetail and fieldErrors together using the actual message source.

## E-46 · MessageFormat can turn fallback validation text into a runtime exception [VERIFIED]

- **Observed defect (PR #821 review):** AbstractMessageSource applies MessageFormat when arguments are supplied. An unresolved fallback containing a rejected value such as a brace/format expression can be interpreted as a pattern and throw IllegalArgumentException, preventing the VAL-001 response.
- **Violated invariant:** untrusted rejected input is data, never a message template; fallback rendering must be safe when a translation key is absent.
- **Correction/status:** #821 changed the fallback lookup/rendering path and added coverage; review thread resolved but the PR remains open.
- **Prevention:** B4/B3 + P3/P12; test missing translation keys with adversarial braces, apostrophes and format tokens.

## E-47 · Spring Boot can retain an unresolved placeholder as literal text [VERIFIED]

- **Observed defect (PR #822 review):** removing a default from the mail base URL did not guarantee startup failure under the pinned Spring Boot 4.1.2 placeholder resolver; an unresolved placeholder could remain as a literal String and produce invalid customer-facing links.
- **Violated invariant:** deployable customer-facing origins are validated as real absolute URLs at startup; unresolved template syntax must never be served as a link.
- **Correction/status:** #822 added explicit properties validation and real binding tests. Review thread resolved; PR remains open in the measured snapshot.
- **Prevention:** B6/P1/P12; test actual Spring binding/startup failure, not only YAML text.

## E-48 · Mail origin with query or fragment produces malformed recovery links [VERIFIED]

- **Observed defect (PR #822 review):** mailBaseUrl accepted query/fragment components (including empty query/fragment delimiters), while reset/verification paths append URI paths after the origin.
- **Violated invariant:** the configured application origin is a path-safe absolute base URI, without query or fragment.
- **Correction/status:** #822 added constructor rejection for query and fragment; review thread resolved, with explicit empty-delimiter tests requested in the follow-up. PR remains open.
- **Prevention:** B6/P1/P3; URI validation tests include empty query/fragment delimiters and the actual URL composition.

## E-49 · Repair-plan summary and counts drifted from the individual gate records [VERIFIED]

- **Observed defect (PR #823 review):** the fix-plan summary said owner-gate items were still awaiting documentation while lower sections already documented them; it also retained an old count (17) although the current set was 15 and included E1/E4.
- **Violated invariant:** derived document summaries and counts must match their detailed authoritative rows.
- **Correction/status:** PR #823 updated the summary and counts; its review thread is resolved, but the PR remains open.
- **Prevention:** A6/P6; extend guards to every load-bearing count/summary or date it with a command that derives the value.

## E-50 · Review-intake issue creation raced between event delivery and scheduled sweep [VERIFIED]

- **Observed defect (PR #834 adversarial implementation review, 2026-11-13):** the intake workflow grouped concurrency by `github.event.pull_request.number`, `inputs.pr_number`, or `github.run_id`. A `issue_comment` event carries the PR number under `github.event.issue.number`, so each comment run received a unique group. It could race the scheduled all-open-PR sweep: both executions could observe no intake issue and create two issues for one source PR.
- **Violated invariant:** there is exactly one active writer for the intake issue registry; every event and recovery sweep for a source PR must converge to the same issue identity.
- **Root cause:** concurrency identity was derived from only one webhook event shape and used the run ID as a fallback, so logically identical source PR operations did not serialize.
- **Correction:** PR #834 uses a repository-wide single-writer concurrency group with `cancel-in-progress: false`; the collector fetches a complete current snapshot, and the scheduled sweep recovers any pending event replaced by GitHub's concurrency queue. A unit test pins the single-writer workflow setting and issue-comment PR-number extraction.
- **Status:** FIX IMPLEMENTED ON PR #834; CI verification pending. Do not mark fixed-main until the regression test passes on the final head and the change is merged.
- **Prevention:** B2 + P4/P12; webhook and scheduled recovery paths share a single-writer key, and tests must model both event payloads.

## E-51 · D11 accepted a declared workflow URL without proving the workflow run existed or passed [VERIFIED]

- **Observed defect (PR #834 adversarial implementation review, 2026-11-13):** the first D11 validator accepted `status: passed` plus any URL beginning `https://github.com/`. It did not fetch the Actions run, check its actual conclusion, verify its repository, or bind its `head_sha` to the current PR head. A fabricated URL or a failed/stale run could therefore be recorded as fixed.
- **Violated invariant:** a fixed disposition is accepted only when GitHub itself confirms that the linked workflow run completed successfully on the exact PR head and in this repository.
- **Root cause:** the receipt's self-reported status and URL were treated as evidence rather than as identifiers to resolve against the authoritative GitHub Actions API.
- **Correction:** PR #834 now parses only the canonical repository Actions-run URL, fetches that run through the authenticated GitHub API, and verifies `status=completed`, `conclusion=success`, and the exact current PR `head_sha`; the gate has read-only Actions permission. Unit tests cover malformed/cross-repository links, in-progress/failed runs, wrong heads and the success payload.
- **Status:** FIX IMPLEMENTED ON PR #834; CI verification pending. Do not mark fixed-main until the validator tests pass and this commit is merged.
- **Prevention:** D11 + P12; never trust a status label or URL in a receipt without resolving the authoritative check/run object and binding it to the exact head.
