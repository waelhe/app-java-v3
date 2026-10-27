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
- **Prevention:** rule B6 (placeholder resolution matrix + fail-fast discipline) — procedural; no deterministic guard yet (recorded as a gap in enforcement-map §5).

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

- **Observed failures (three independent instances):**
  1. `SYSTEM.md` stated 183 integration-selected files while the derivation said 184 (new CMS contract tests raised the count) — `DocumentationNumbersGuardTest` failed the build (closed by commit `2a13d192`, 2026-10-25/26).
  2. PR #723 body claimed inventory 107/294/57 while every head since `2e997f3` measures **163 INTEGRATED / 238 NOT_STARTED / 57 BLOCKED** (round-13 audit).
  3. Campaign reports: `campaign-b6-b` counts 98 ≠ 90; completion-map 57 ≠ 56 (CodeRabbit 2026-10-26 Major findings).
- **Original assumption:** hand-typed numbers beside evolving truth stay in sync.
- **Triggering conditions:** every merge that changes a derived count without re-deriving the documented one.
- **Causal chain:** derived truth moves; prose doesn't; readers act on the prose.
- **Violated invariant:** load-bearing numbers are derived, never hand-maintained.
- **Root cause:** missing guard coverage per document (guard exists for SYSTEM/ARCHITECTURE/README, not for campaign reports/PR bodies).
- **Correction:** guard test (D2) covers the canonical files; rulebook extends the discipline; PR-body numbers carry measured date + re-derivation command.
- **Verification evidence:** guard failure → fix measured in round-14 audit.
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
- **Prevention:** rule D6-adjacent: gate health itself is monitored (gate success rate is a watched number, not assumed).

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
