# Yelp-Level Plan — Wave W1 Deviation Register

**Status:** declarations for `feat/yelp-w1-dual-reviews-verify` (W0 + W1 on top of `8da6f8af`).
**Governing document:** `docs/governance/plans/yelp-level-plan.html` (§1.4 execution rules; W1 acceptance).
**Rule applied:** `SYSTEM.md` §14.1 — "لا ديون مخفية: كل فجوة تُصرَّح كتابةً وتحمل نقطة إغلاق محددة — لا ترقيع صامت"
and "والانحراف عن القاعدة يُسجَّل صراحةً لا يُدفن".
**Basis for every entry:** an official reference (per `docs/governance/version-policy.md` §2 source-of-truth list)
plus a measurement from this branch. Nothing below is closed by opinion.

---

## D-W1-1 — The plan's literal "no modified line" criterion is not reachable

**The plan says (W1 acceptance):** «اختبارات VERIFIED_ONLY الحالية تمر دون تعديل حرفي».

**Measured** (`git diff 8da6f8af..HEAD -- '*/src/test/*'`):

| file | + | − |
|---|---|---|
| `marketplace-reviews/.../ReviewsServiceTest.java` | 345 | 5 |
| `marketplace-reviews/.../ReviewsControllerTest.java` | 51 | 27 |

**Classification of all 32 removed lines: zero assertions.** They are the stubbing lines whose
*derived query name* changed and the fixture lines whose *record arity* changed. No `assert*` /
`verify*` statement was removed, relaxed, or inverted — the pre-W1 VERIFIED_ONLY assertions still run
(the seed block in `ReviewsServiceTest` pins the booking path to `VERIFIED_ONLY` for the whole
pre-existing suite).

**Why this is forced by the plan itself, not chosen:**

1. **§4.4 mandates the response to gain the reviewer-identity block** → `ReviewResponse` grew from 9
   to 15 components. A Java record has *exactly one* canonical constructor and no default
   (JLS §8.10 Records — implicitly final, canonical constructor implicitly declared), so **every
   construction site must be updated**. The plan cannot require the field and, in the same breath,
   require the fixture constructing it to stay byte-identical.
2. **§4.5 makes the public list the PUBLISHED gate** → `ReviewRepository.findByProviderIdAndDirection`
   became `findByProviderIdAndDirectionAndModerationStatus`. Spring Data JPA derives a query from the
   *method name* (Spring Data JPA Reference › Querying for Data › Query Creation), so a stub naming the
   old method would stub nothing and the test would silently weaken — the alternative is keeping the
   pre-gate behaviour.
3. **§4.5 adds the visibility gate** → `ReviewsController.getById(id)` became
   `getVisible(id, Authentication)`; the call site gains the principal.
4. **`ReviewsService` gained 7 collaborators** and the project codifies constructor-based injection
   (Spring Framework Reference › Core Technologies › Dependency Injection and Autowiring — the
   constructor is the recommended approach), so the unit test must pass them.

**Closing point:** the criterion is restated as the *measurable* thing it protects, and made verifiable
instead of promised:

> The pre-W1 VERIFIED_ONLY assertion set is preserved and green; the only edits are mechanical plumbing
> the plan's own W1 spec mandates, and **no assertion was removed or weakened** — enforced by the suite
> itself, not by a claim.

Status: **closed as declared.** The literal reading would require dropping §4.4's identity block from
the response — a *plan* decision, not an execution one, so it stays with the owner.

---

## D-W1-2 — The plan says "zero `pom.xml` change"; W0 touched two

**The plan says (§1.4):** «صفر تغيير في أي `pom.xml` وصفر اعتمادية جديدة: الموجات كلها تركب المفاعل القائم».
**W1 changed no `pom.xml` at all.** W0 changed two — declared here:

**(a) `marketplace-app/pom.xml` — declared `spring-data-envers` with no `<version>`.**
- *Declare-what-you-use* for the module's first `@Audited` entity (`SystemSetting`), and the project rule
  is unconditional: `AGENTS.md` — "Envers: All domain entities MUST have `@Audited`". The same declaration
  already exists at `marketplace-reviews/pom.xml` and `marketplace-search/pom.xml`.
- No `<version>` → resolved from the parent's managed set, which is exactly `version-policy.md` §6:
  "Do not pin versions already managed by Spring Boot BOM unless a documented exception exists".
  **No exception is needed; none is used.**
- Measured: **zero new third-party coordinate, zero version pin, zero plugin/model change.** The plan's
  intent ("stacks onto the existing reactor") holds: no module, no artifact, no dependency-management entry.

**(b) root `pom.xml` — two JaCoCo `<exclude>` entries (`SystemSettingKeys.*`, `SystemSettingTypeException.*`).**
- Both follow the adjacent excludes in the same block (`ApiConstants.*`, `BadRequestException.*`,
  `ConflictException.*`, `ResourceNotFoundException.*`): a pure-constants holder and an exception type.
- **No exclusion was used to pass a gate.** Both coverage gates were closed by writing tests:
  `marketplace-reviews` 0.6686 → **0.8728** and `marketplace-media` 0.6754 → **0.9128**
  (+1448 covered instructions), and W1 added **no** new exclusion.

Closing point: **closed as declared.** Complies in substance (no new dependency, no version pin, no
reactor-model change) while the literal wording is not met. Closing the *literal* wording means amending
§1.4 to read "no *new* dependency and no *new* plugin"; the code has nothing to change.

---

## D-W1-3 — `reviews.mode` is policy-as-data, not an env feature flag

`docs/governance/feature-flags.md` (adopted) fixes **env-based flags** for deploy-time behaviour gating:
`marketplace.feature.<name>.enabled`, `@ConditionalOnProperty`, default OFF, kill-switch.

W0/W1 put the owner's mode switch in a **`system_settings` row read through a cached port**, flipped at
runtime with no redeploy. The plan mandates this (§1.4): «أما سياسة يقلبها المالك وقت التشغيل فمقرّها
البيانات: صف في `system_settings` يُقرأ مكاشدًا... `reviews.mode` صف بيانات لا متغير بيئة — تبديل فوري
عبر المِرحّل القائم دون إعادة نشر (متغيرات Railway الـ45 مقروءة عند الإقلاع فقط — قياس)».

Spring Boot Externalized Configuration binds properties when the context starts (Spring Boot Reference ›
Features › Externalized Configuration), so an env-var policy **cannot** be flipped without a restart —
which would break the plan's owner key ("تبديل بلا تغيير كود").

Two mechanisms, two jobs — **no rule is violated**:

| | feature flag | system setting |
|---|---|---|
| Question it answers | *which code runs* | *which policy the running code applies* |
| When it can change | at deploy/start | live, any time |
| Authority | `feature-flags.md` | plan §1.4 + `SystemSettingsService` |

It is also not "remote targeting" under `feature-flags.md` §"What this is NOT": it is a single
operator-owned global switch, not per-user or percentage bucketing.

Closing point: **closed as declared** — the boundary is written down so nobody later "converts"
`reviews.mode` into an env flag (silently destroying the owner key), and so the flags convention is not
misread as authorising a flag service.

---

## L-W1-1 — A new cross-module port breaks `@ApplicationModuleTest` contexts (measured, fixed, codified)

**What happened:** W1 added `ReviewMediaService`'s dependency on `ReviewLookupPort`. Three media tests
failed to load their context with
`NoSuchBeanDefinitionException: No qualifying bean of type com.marketplace.shared.api.ReviewLookupPort` —
`MediaModuleIntegrationTest`, `MediaThumbnailIntegrationTest`, `MediaUploadFlowIntegrationTest`.

**Cause:** `@ApplicationModuleTest` boots *only* the media module, so the reviews module's
`ReviewsLookupAdapter` is not in that context (Spring Modulith Reference › Testing Applications › Testing
an Individual Module). The boundary itself is **legal**: the port lives in `shared`, so this is the
shared-SPI channel the module law requires, not a module-to-module reach — `ArchitectureRulesTest`
stayed green throughout.

**Fix:** the form the tests already use for `CurrentUserProvider`, `ListingPriceProvider` and
`ProviderLookupPort` — a `@MockitoBean` declaration for the port the test needs, each carrying a comment
naming the rule.

Closing point: **closed.** The next cross-module port follows the pattern instead of rediscovering the
failure; the existing risk-register row "Cross-module dependency changes introduce integration
regressions" now has this concrete measured form behind it.

---

## Acceptance criteria actually measured (no criterion left unverified)

| W1 criterion (plan) | Evidence | Status |
|---|---|---|
| `VERIFIED_ONLY` suite passes, no line modified | 32 lines, **0 assertions lost** — D-W1-1 | met, D-W1-1 declared |
| a test per mode: verified / organic / hybrid two-badge | `ReviewsOrganicGateIntegrationTest`, `ReviewModerationIntegrationTest.hybrid_ownTwoBadgesSeparately` | met |
| the two uniqueness rules tested negatively | `uq_review_organic_once` (`ReviewsOrganicGateIntegrationTest:366`), booking-direction (`ReviewsTwoWayIntegrationTest:169,320`) | met |
| `ck_review_origin_booking` tested negatively | `ReviewsOrganicGateIntegrationTest:343` — asserts the constraint name | met |
| a pending review is in neither the public list nor the aggregates | `ReviewModerationIntegrationTest:168` (`getGeneralStatsByProviderId` empty) → `:183` | met |
| media uploads and reads on a review with no listing | media module tests (5/5, 9/9, 4/4) | met |
| stored average regenerates with distinct user/profile ids | `ReviewsTwoWayIntegrationTest` (id-space correction; fixture fixed to V6's FK truth) | met |

**Note on how this table was produced:** an early pass of this session claimed
`ck_review_origin_booking` had no negative test. That was a **grep truncation** (the result limit cut
before `marketplace-app/src/test/.../reviews`), and it was corrected by a full-tree search before any
code was written. Recorded because a false "missing coverage" claim is exactly the kind of silent error
`SYSTEM.md` §14.1's evidence rule exists to prevent.



---

## D-W1-2 — Migration renumbering at the merge with main: V71..V74 → V84..V87

**What changed:** this branch forked before main's reconciliation chain (#484) landed. Its four
migrations were authored as `V71__system_settings.sql`, `V72__reviews_dual_mode.sql`,
`V73__content_reports_review_target.sql`, `V74__review_votes_flags_and_media.sql`; main meanwhile
allocated `V71` (`payment_webhook_events_inbox`), `V73` (`post_reactions`), `V74`
(`notification_preferences_post_reacted_type`) and reached `V82`. Two files with different names and
the same version number are not a Git conflict — they are a **Flyway duplicate-version failure at
first boot** ("Found more than one migration with version 71"). The renumbering is the only
correct resolution: **content byte-for-byte unchanged** (checksums stay identical, only the
`migration-checksums.properties` keys follow the new file names), `V83` stays reserved for #485's
events migration (safe in either merge order).

**Official basis:** Flyway's versioned-migration naming contract — one version number, exactly one
migration file; duplicate versions fail the migrate step (Flyway reference docs, "Versioned
Migrations" / "Command migrate").

**Precedent (this repository, measured):** SYSTEM.md's migration inventory records the identical
renumbering of the parallel session waves "from V72..V75 to V79..V82 at the reconciliation merge —
free renumbering because the content was never applied to production". This branch's migrations
exist only on the unmerged branch; the production Flyway ledger (Neon, index V78, measured
2026-10-01) has never seen them — the same free-renumber condition, now applied to W0/W1.

**Closing point:** the register entry itself (this file), the plan's renumbered horizon
(`yelp-level-plan.html` — V84..V87 for W0/W1, next-free numbers for W2+), and SYSTEM.md's
guard-derived inventory (84 versioned, `V1..V87`) all carry the new numbers — nothing refers to
the old ones except this declaration.

---

## D-W1-3 — The per-review media limit is a declared policy value, not a plan figure

**The plan (§4.4/§4.5)** mandates anti-abuse gates for the review surface but sets no explicit
maximum photo count per review. CodeRabbit W1 r4 (verified: `requestUpload` signed unlimited
PENDING rows, and abandoned uploads kept occupying positions) required a limit.

**The registered choice:** `marketplace.media.limits.max-assets-per-review`, default **10**
(PENDING uploads included, checked while the review's advisory allocation lock is held — the
limit holds under concurrency). Environment-tunable via `MEDIA_LIMITS_MAX_ASSETS_PER_REVIEW`
— the same "environment-tunable key" posture the feature-expansion roadmap applied to the
thumbnail width. **Scope note:** the LISTING channel (pre-W1, main behavior) keeps its
unlimited contract — changing a product policy for a surface this wave does not own is a
separate decision, recorded here rather than silently taken.

**Closing point:** `ReviewMediaServiceTest.requestUpload_rejectsAtThePerReviewLimit` pins the
rejection; the property is bound through the module's existing `@ConfigurationProperties`
record with `@DefaultValue`.

---

## Review-adoption round (post-reconciliation merge) — all ten threads closed from the root

| # | finding (author) | verification | root adoption |
|---|---|---|---|
| r1 | JSON `null` passes the setting guards (CodeRabbit, Minor) | confirmed: Jackson delivers `NullNode`, both guards tested Java `null` only; stored `'null'::jsonb` later fails typed reads as 500 | `SystemSetting.create`/`replaceValue` reject `isNull()` — `UserSummary`-level javadoc updated |
| r2 | Javadoc listed shortened observation names (CodeRabbit, Minor) | confirmed vs the `@Observed` declarations and the EXPECTED pin | `ObservationCoverageFilesTest` javadoc carries `media.review.*` |
| r3 | Cleanup hard-deletes reviews that still carry `review_flags` (CodeRabbit, Major) | confirmed: V87 FK, no cascade; `NEW_ACCOUNT_ACTIVITY` flag on the 9-day fixtures | flags deleted first in all three cleanup paths + the `provider_listings` fixture row |
| r4 | No per-review media limit (CodeRabbit, Minor) | confirmed: unlimited PENDING rows | D-W1-3 above — limit checked under the advisory lock |
| r5 | Email as public reviewer name (CodeRabbit, Major) | confirmed: `publicDisplayName()` fell back to the login email on the anonymous review surface | the email tier removed; `UserSummaryTest` pins the contract |
| r6 | Reviewer email becomes public (greptile, P1) | same defect as r5 — cross-confirmed by two reviewers | same root fix |
| r7 | Module slice tests lack the new ports (greptile, P1) | confirmed: `@ApplicationModuleTest` boots one module; the expanded constructors need cross-module beans | four `@MockitoBean` ports in `ReviewsModuleIntegrationTest`, one in `ProviderModuleIntegrationTest` |
| r8 | Public review photos answered 401 (greptile, P1) | confirmed: the security chain permitted listing-media GETs only | `/api/v1/media/reviews/by-review/*` GET permitted — the service's own moderation-visibility gate stays the authority |
| r9 | Concurrent submissions exceed the caps (greptile, P1) | confirmed: daily-cap, 1x1 uniqueness, and first-N counts are all count-then-insert | `pg_advisory_xact_lock(hashtextextended(reviewerId, 7))` — the measured #241 shape, seed-namespaced away from the media locks |
| r10 | Soft-deleted photos cause duplicate positions (greptile, P1) | confirmed: count-based allocation re-issues held positions | max-based allocation on ALL THREE channels (review/listing/post) — same defect class, same root fix |


---

## D-W1-5 — The provider public page composes the reviews block (a read surface the plan leaves to W2's "full business page")

**The plan says (§5-W2 scope):** «صفحة مزوّد كاملة: ساعات + خدمات + توزيع نجوم + إشارتا
التقييم» — the reviews LIST on the provider page reads as W2 territory, and W1's acceptance
names the three public READ PATHS as the reviews module's own endpoints.

**What this wave adds (the frontend-consumer completion):** `ProviderPublicPageResponse` gains
`reviewsMode` + a `reviews` page block (`PagedResponse<PublishedReviewView>`), served through a
new shared `PublishedReviewsPort` (the `ReviewStatsPort` pattern verbatim — the provider module
already resolves the profile-to-user mapping internally for the rating block).

**Why this is forced by the wave's own contract, not chosen:**

1. **The votes and the reviewer-identity blocks have no public home without it.** §4.5 builds
   helpful votes; §4.4 builds the identity blocks — both serve `GET /reviews/provider/{userId}`,
   a path keyed by the provider USER id. The frontend's measured, declared seam (frontend
   ARCHITECTURE §10 + `reputation-contract.ts`): no public read exposes the profile→user
   mapping, so no surface can render the rows the wave's own acceptance criteria describe.
2. **`reviewsMode` is not inferable from the rating fields.** A HYBRID page with zero published
   organic reviews is byte-identical to VERIFIED_ONLY by the aggregate pair alone — the honest
   mode-driven display (mount the organic form only when the mode admits it) needs the mode
   itself, which the service already reads.
3. **The alternative is worse than the extension.** Inferring the mode client-side is guessing;
   always-mounting the form and letting the 400 teach is a degraded surface in the DEFAULT
   (seeded) mode — the exact "لوحة إعلانات" feel the owner's re-foundation directive rejects.

**Boundaries measured:** zero new modules, zero new tables, zero new gates — one shared port +
one shared projection (`PublishedReviewView`, the field whitelist documented on the record:
no bookingId, no direction, no moderationStatus, no listingId, no user id), the
`ReviewsPublishedPageAdapter` in the reviews module's `spi` package (the `ReviewStatsAdapter`
precedent), and the provider page's own composition. `ArchitectureRulesTest` stays green (the
provider module's `shared` dependency is pre-existing). W2's full business page remains
untouched — hours, services, service areas, category attributes, JSON-LD are all still W2's.

**Closing point:** `ProviderPublicPageServiceTest.reviewsBlock_ridesThePortWithTheUserId_`
+ `ReviewsPublishedPageAdapterTest` + the WebMvc serialization pins
(`reviewsMode`, `reviews.content[0].*`).
