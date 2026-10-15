# PAOS — End-to-End Journey Recipes and Acceptance Contracts

**Authority:** Product detail companion to [screen specification](platform-admin-detailed-screen-specification.md) and [product charter](platform-admin-operating-system-product-charter.md).  
**Status:** Target requirements; not evidence of implemented behavior.

A journey passes only when the manager understands the result, performs the authorized task, and the system proves the effect in the source system or a trusted receipt. A rendered page or successful HTTP response does not qualify.

## 1. Required journey record

Every implemented journey must track:
- Entry point and operator role/scope.
- Goal in the operator’s words.
- Records/evidence needed to decide.
- Exact user steps, decisions, transitions and alternative paths.
- Commands, source owner and authorization/approval requirements.
- Success definition and final-state evidence.
- Failure handling, safe recovery and next owner.
- E2E checks for access, scope, version conflict, timeout, duplicate request, partial success, audit and recovery.
- Arabic/RTL and English/LTR text/accessibility.

## 2. The Super Manager’s full incident-to-recovery journey

This is the reference journey that all other workflows reuse.

### Stage 1 — Orient
1. Sign in through the dedicated administrator boundary; satisfy MFA/step-up if asked.
2. See environment, current scope, effective authority, last fresh data, assigned work, approvals and shift handover.
3. Open the highest-impact signal. The page explains why it matters and separates confirmed facts from suspicions.
4. If the data is stale, create/assign a data-collection task instead of treating the system as healthy.

### Stage 2 — Understand
5. Open “تفاصيل الأثر” to see affected member journeys, places and time interval.
6. Follow relationships to member/business/content/payment/release/notification objects and recent changes.
7. Review source timestamps, related cases, operation history and evidence. Contradictory sources remain visible as a conflict.
8. Create a case with a one-sentence objective, scope, severity, owner and explicit exit criteria.
9. Assign investigations with owners, dependencies and deadlines.

### Stage 3 — Contain
10. Open “راجع إجراء الاحتواء” and choose only an available, source-owned mitigation.
11. Review exact affected scope, audience, dependencies, fallback, reversibility and evidence freshness.
12. The platform proves the control reaches the claimed consumers. If not, block an unsafe broad action and offer safe manual mitigation/escalation.
13. Complete policy check, independent approval and step-up authentication as required.
14. Submit a typed command; receive a durable operation reference.
15. Observe the real effect. If the result is unknown, leave the incident open and inspect the operation/receipt instead of repeating the command blindly.

### Stage 4 — Fix the cause
16. Follow case tasks to the owning domain team.
17. Perform domain repair, reconciliation, content decision, delivery recovery or release fix through the source owner.
18. Use dry-run and per-item results for bulk work.
19. Compare observed effects with the case’s stated goal; update the diagnosis if the fix did not work.

### Stage 5 — Restore and close
20. Restore or widen traffic in stages only after agreed guardrails pass.
21. Re-check affected member journeys with fresh data over the agreed stable window.
22. Record root cause, contributing factors, decisions, unresolved risk and owned follow-up.
23. Send approved stakeholder/member updates and verify delivery where required.
24. Close only after exit criteria are satisfied. Produce a handover if any risk remains.

**Definition of success:** containment and recovery are verified; every important action has actor, scope, rationale, approval, result and evidence; all unresolved risks have an owner. “Incident acknowledged” is not recovery.

## 3. Twenty-four detailed task recipes

### J01 — Start the shift
**Entry:** sign-in → “اليوم”.  
**Steps:** review environment/scope/freshness → rank “يتطلب تدخلك” → open signal evidence → check assigned/overdue work → review approvals → review ongoing operations → claim or assign work → record acknowledgement/defer reason and deadline.  
**Alternate paths:** no active incidents: confirm data freshness and inspect routine work; missing telemetry: open a data-quality task; missing permission: delegate/request access.  
**Success evidence:** every critical item is assigned, acknowledged or deferred with a reason; nothing critical disappears from the work queue.

### J02 — Investigate any problem
**Entry:** search or signal → “افهم المشكلة”.  
**Steps:** identify impacted journey and time range → follow entity relationships → distinguish confirmed observations from hypotheses → add canonical records/evidence → create case with objective/owner/scope/deadline → create dependent tasks → share the case context.  
**Alternate paths:** ambiguous results need human disambiguation; contradictory sources show both; unavailable evidence becomes an owned task.  
**Success:** one case links canonical records and evidence with a named owner, completion criteria and timeline.

### J03 — Handle a neighborhood safety event
**Entry:** neighborhood map or report spike.  
**Steps:** choose country/city/neighborhood → see boundary and affected population → review permitted reports/content/source history → create safety case → appoint incident lead → preview a scoped safety measure → obtain review where needed → apply through moderation owner → verify the actual content/account state → monitor report recurrence → provide permitted updates.  
**Alternate paths:** unknown geographic coverage blocks broad action; unavailable context blocks high-impact sanction; suspected coordinated harm creates linked investigation rather than an unbounded rule.  
**Success:** targeted scope and applied measure are proven; appeal and follow-up obligations are tracked.

### J04 — Contain platform degradation
**Entry:** critical signal on homepage.  
**Steps:** identify affected journey → inspect time-aligned changes/dependencies → label confirmed versus suspected cause → create incident → assign investigation → select a verified capability/release mitigation → preview scope/effects/fallback → approve and execute → read actual effect → monitor recovery.  
**Alternate paths:** no verified switch means no fake button; command timeout means inspect operation before retry; unexpected impact triggers a new mitigation decision.  
**Success:** measured journey improves and the containment is linked to evidence.

### J05 — Change configuration
**Entry:** “القواعد والأتمتة”.  
**Steps:** select a named setting → review purpose/current scope/owner → edit a typed form → validate dependencies/ranges → see semantic diff → simulate affected groups → request approval or schedule → publish a revision → observe defined indicators → promote or restore via a new revision.  
**Alternate paths:** stale draft requires diff/reconfirmation; missing dependency blocks publish; stale analytics blocks promotion when fresh evidence is mandatory.  
**Success:** approved and active revision agree, effective value is observed, audit/case contains the result.

### J06 — Publish a mobile-app experience
**Entry:** “تجربة الأعضاء” → “تعديل ما يظهر للأعضاء”.  
**Steps:** choose platform/version/locale/device viewport → compose using supported components → bind real content sources → preview populated/loading/empty/error/denied states → run RTL/accessibility/contrast/device compatibility checks → compare with current view → preview audience → obtain review → stage release → watch critical journeys → promote or pause/restore.  
**Alternate paths:** missing binding displays a designed empty state, not dummy content; unsupported version/device blocks compatibility claim; stale crash/usage data holds expansion.  
**Success:** the intended version renders the approved layout and rollout effects are verified.

### J07 — Verify a member and handle an appeal
**Entry:** “الأعضاء والأحياء” → “بانتظار المراجعة”.  
**Steps:** open claim and requested neighborhood → inspect evidence origin/date/validity → review relevant referral/source relations → compare policy criteria → select accept/request-more-proof/reject/escalate → preview member message → submit to membership owner → read back membership state → track delivery and appeal path.  
**Alternate paths:** conflicting evidence gets second review; unauthorized operator retains draft but cannot decide; failed message delivery is tracked separately from the membership decision.  
**Success:** authority, evidence, reason, state and required communication reconcile.

### J08 — Review harmful content or coordinated reports
**Entry:** report queue.  
**Steps:** group related reports by shared root object → inspect content/thread/policy context → review prior decisions in scope → select no violation/secondary review/temporary hide/policy removal/scoped restriction/escalation → preview target and member effect → obtain independent review for broad sanctions → apply → confirm source state → notify/track appeals.  
**Alternate paths:** duplicate report attaches to existing case; missing context blocks strong sanction; reviewer disagreement routes to independent review.  
**Success:** every target has a recorded outcome, rationale and permitted appeal history.

### J09 — Publish an official alert
**Entry:** “المجتمع والثقة” → “التواصل الرسمي” → “إنشاء تنبيه”.  
**Steps:** verify source institution/publisher authority → write message and choose urgency/timezone → select area/audience/languages/exclusions → preview phone experience in each language → inspect estimated reach/freshness → check duplicates/conflicts → obtain approval → schedule/publish exact approved payload → track per-channel delivery → retry safe failures or publish linked correction.  
**Alternate paths:** unknown audience blocks broad publication unless a narrowly defined emergency policy authorizes it; expired source authority blocks publish; schedule timezone must be explicit.  
**Success:** payload, approver, audience snapshot, publication state and delivered/failed/unknown receipts agree.

### J10 — Investigate payment mismatch or refund
**Entry:** “المال والنمو” → “مشكلات المدفوعات”.  
**Steps:** inspect order/booking, expected amount/currency, provider response, platform financial record and last refresh → classify mismatch → inspect operation before any retry → preview reconciliation → or request refund amount/reason → require independent approval when necessary → execute through finance/payment owner → verify provider result and ledger → notify member only when outcome is known.  
**Alternate paths:** unknown provider response blocks duplicate charge/refund; partial refund uses authoritative remaining amount; unresolved ledger mismatch stays as assigned finance work.  
**Success:** member-facing state, provider response, amount/currency, approval and ledger agree—or a named unresolved case remains open.

### J11 — Resolve a provider/listing problem
**Entry:** report, booking/order or provider lookup.  
**Steps:** open provider and listing 360 → inspect affected customers/orders/bookings → identify whether risk is safety, accuracy, fulfillment or payment → preview narrowly scoped freeze/hide/communication → approve if risk tier requires → apply source-owned restriction → assign provider case → resolve or enforce → restore only after checks → reconcile affected customers.  
**Alternate paths:** avoid a blanket provider ban if a single listing is the cause unless impact/policy supports it; unknown customer count stays visible.  
**Success:** restriction scope is justified and every impacted customer/order has a tracked next state.

### J12 — Repair notification delivery
**Entry:** delivery anomaly.  
**Steps:** view intended/accepted/delivered/failed/unknown counts → filter channel/region/provider/template → inspect representative failures and safe tracking ref → classify invalid destination/provider/platform/unknown → preview fallback and duplicate risk → run safe test/subset → retry eligible subset → verify receipts → assign unresolved cases.  
**Alternate paths:** unavailable receipt remains unknown; invalid member contact routes to data quality; retry is forbidden where duplicate delivery cannot be ruled out.  
**Success:** counts reconcile, uncertain population is explicit and each unresolved segment has an owner.

### J13 — Perform a bulk operation
**Entry:** filtered work list → “عملية جماعية”.  
**Steps:** define selection and scope → run dry-run → split eligible/excluded/conflicting/unauthorized/duplicate/irreversible rows → inspect sample and report → set reason/window/owner/cancel policy → obtain approval → start durable operation → track progress → inspect per-row result → retry only safe failures.  
**Alternate paths:** source changes during run require a stable snapshot/version or explicit warning; partial success is preserved; worker restart resumes durable progress.  
**Success:** selected count is accounted for by success/failure/skipped/unknown; unresolved results have owners.

### J14 — Run or stop an experiment
**Entry:** “تجربة الأعضاء” → “تجربة محدودة”.  
**Steps:** state hypothesis in plain language → define member journey and measurable outcome → choose eligible/excluded group and regions → set exposure cap/duration → define success and safety guardrails → simulate eligible population → review → stage smallest cohort → observe segments → auto-pause on defined harm threshold → expand/stop/restore with rationale.  
**Alternate paths:** inadequate sample or missing baseline is disclosed; failed metric collection prevents a claim of success.  
**Success:** exposure assignment/results are reproducible and approved stop criteria are enforced.

### J15 — Correct search/discovery quality
**Entry:** member outcome signal or manager complaint.  
**Steps:** inspect sample queries, language, geography, filters, zero-result rate, freshness and latency → classify missing content/index delay/language or ranking issue → create versioned evaluation set → compare old/new results → check geography/language/popularity bias → stage change → monitor by cohort → promote or restore.  
**Alternate paths:** no source data means do not tune rank based on guesses; latency regression can stop promotion despite relevance gains.  
**Success:** evaluation set, revision, affected cohorts and real discovery outcome are linked.

### J16 — Process a privacy request
**Entry:** “إعدادات المنصة والفريق” → “الخصوصية” → “طلبات بيانات الأعضاء”.  
**Steps:** verify identity/authority → identify relevant systems/data categories → explain retention/legal holds → choose export/correct/restrict/delete → preview scope/sensitive fields → obtain approval → run with per-system progress → review exceptions → deliver securely → provide receipt and audit.  
**Alternate paths:** unverified identity pauses the request; legal hold explains which data is retained; partial system failure prevents claiming completion.  
**Success:** every required system has a result or explicit exception and the output access is audited.

### J17 — Grant or revoke admin access
**Entry:** “إعدادات المنصة والفريق” → “صلاحيات الفريق”.  
**Steps:** select verified person → inspect existing grants → choose task permission and exact scope → simulate allowed/blocked actions → record reason/expiry → detect self-approval/conflict → obtain approval/step-up → grant/revoke → reload effective rights → test protected action → set access review/expiry reminder.  
**Alternate paths:** temporary grant without expiry cannot save; self-grant cannot bypass dual control; current operations recheck revocation at execution.  
**Success:** effective access matches request; temporary scope expires; actor/approver/reason are audited.

### J18 — Recover a failed job or background operation
**Entry:** “العمليات الجارية”.  
**Steps:** open operation → read purpose and last successful checkpoint → see completed/failed/skipped/unknown records → inspect retry safety/deduplication → choose resume/retry subset/cancel/escalate → watch durable progress → verify source state/effect → update linked case.  
**Alternate paths:** timeout with unknown final state triggers read-before-retry; unsafe partial state requires owner review; cancellation reports what already took effect.  
**Success:** no duplicate business effect, all target rows accounted for and next steps assigned.

### J19 — Use the AI assistant safely
**Entry:** task-oriented prompt from homepage or case.  
**Steps:** ask a plain-language question → review conclusion, evidence, dates, uncertainty and missing information → open source evidence → correct incorrect assumptions → ask for action proposal → inspect target/scope/effect → continue through ordinary policy/preview/approval/command process → compare actual result with prediction.  
**Alternate paths:** weak evidence yields investigation proposal rather than action; untrusted user content is treated as data, not instructions; no tool bypass is permitted.  
**Success:** sources/model/tool versions are retained under policy and no action bypasses standard controls.

### J20 — Change a cross-country policy
**Entry:** “القواعد والأتمتة” → policy with global scope.  
**Steps:** choose rule/owner/reason → compare old/new behavior by country/city/language → expose exceptions and required regional/legal reviews → simulate representative cases → estimate affected population → obtain approvals for same immutable revision → stage by region → observe local guardrails → pause/restore affected region if needed.  
**Alternate paths:** missing regional source blocks global promotion; exception with no owner/expiry remains an unresolved risk.  
**Success:** every region has a known policy revision and traceable exception.

### J21 — Resolve conflicting metrics
**Entry:** “اليوم” → “تقدّم أهدافي” → المؤشر المختلف عليه.  
**Steps:** inspect human-language definition, numerator/denominator, timezone, filters and freshness → view source lineage and last update → compare calculation versions → sample authorized source records → create data-quality case → choose wait/fix-source/versioned-definition-change → notify dashboard consumers.  
**Alternate paths:** missing denominator makes metric inconclusive; definition changes preserve old interpretation/history.  
**Success:** corrected view includes owner, definition, time window and explanation; no silent override.

### J22 — Repair an external integration
**Entry:** “إعدادات المنصة والفريق” → “صحة المنصة” → “اتصال متعثر”.  
**Steps:** identify impacted journeys/regions and provider → see start time and confidence → inspect sanitized request summaries and recent changes → choose validate/rotate secret reference/use approved fallback/pause traffic → preview replay candidates and duplicate risk → repair with provider owner → run bounded test → replay only safe items → verify downstream records and restore monitoring.  
**Alternate paths:** secrets never shown; unknown responses stay unknown; unsafe replay requires manual review.  
**Success:** connection check passes, affected records reconcile and secret rotation is auditable.

### J23 — Resolve an official-source dispute
**Entry:** institution or publisher record.  
**Steps:** inspect identity, representatives, proof, authorized geography and expiry → isolate disputed permission from unrelated privileges → preview time-limited restriction → obtain independent approval → notify source and provide appeal route → review scheduled messages separately from delivered messages → restore/revoke source permission → check future publishing queue.  
**Alternate paths:** previously delivered content cannot be erased from history; issue correction where required; expired evidence blocks new publishing.  
**Success:** authority, future schedule, past delivery and appeal states reconcile.

### J24 — Review product-journey performance
**Entry:** “اليوم” → “تقدّم أهدافي” → رحلة العضو.  
**Steps:** select period and scope → verify freshness/denominator → compare success/failure/abandonment/recovery → segment by geography/version/member cohort where permitted → inspect linked cases and recent changes → create action/experiment with an owner → set baseline and recheck date → revisit result.  
**Alternate paths:** incomplete data is labelled inconclusive; correlation is not presented as proven causation.  
**Success:** the conclusion cites metric definition and data coverage; any action has a measurable follow-up.

## 4. Alternate paths that every journey must handle

| Failure or condition | Required UX | Must never happen |
|---|---|---|
| No data | Explain what is missing, why it matters and a safe check | Show zero/success by default |
| Stale data | Show last update; block risky action where freshness is required | Pretend it is live |
| Missing permission | Explain access request/delegation route without leaking protected records | Rely on hidden buttons only |
| Wrong scope | Show current/requested scope and effective authority | Treat scope selection as access grant |
| Conflicting version | Show semantic diff and require reconfirmation | Silent overwrite |
| Timeout after potential commit | Query operation/effect before retry | Blind repeat of finance/publish/delete |
| Partial batch | Account for success/fail/skip/unknown per item | Whole-operation “success” |
| Rejected/expired approval | Explain who/what is needed next; revise and resubmit | Reuse approval after payload change |
| Dependency down | Safe fallback or owner handoff | False healthy state |
| Expired session | Preserve safe draft, reauthenticate, reconfirm high-risk intent | Execute on stale session |
| Delivery unknown | Recover receipt; keep unknown visibly unknown | Infer delivered from accepted |
| Irreversible action | State exact effect and compensation options | Promise undo without evidence |
| AI uncertainty | Label hypothesis and propose investigation | Turn guess into a command |

## 5. Required E2E test matrix

For every journey, run at least:
1. Happy path through the real adapter to source/effect verification.
2. Same action through unauthorized direct URL and direct API.
3. Valid role but wrong geographic/organizational scope.
4. Stale data and conflicting resource version.
5. Timeout after the server may have committed.
6. Duplicate click or replay with same operation key.
7. Dependency failure and only the documented safe recovery.
8. Partial batch plus retry of eligible subset.
9. Session expiry/step-up in a high-risk flow.
10. Separate approver and payload-binding check.
11. Payload change invalidates previous approval.
12. Audit and case timeline correlate actor, scope, reason, policy decision, approval, operation and actual result.
13. RTL/LTR, keyboard, screen reader and responsive layout checks.
14. Unknown/stale outcome never appears as success.

## 6. The manager's completion checklist

Before an action is confirmed, the manager can answer:
- What exactly will change?
- Where and for whom?
- How do we know the information is fresh?
- What might break or be irreversible?
- Who must approve it?
- What proves it worked?
- What is the safe next step if the result is unknown or failed?

If the interface cannot answer these questions, the action is not ready for production exposure.


## 5. Domain-depth journeys — required beyond the original 24 recipes

The original 24 recipes cover cross-platform incidents and representative tasks. They do not replace the domain-specific workflow acceptance in [Domain-Grade Administration Suites](platform-admin-domain-grade-suites.md). The following journeys are required product tests, not optional examples.

### D25 — Catalog and variant lifecycle (Saleor-depth)
Create a product/listing → select the correct type → define required attributes → add variants and identifiers → bind media → set price/currency and channel availability → configure stock/location or service capacity → preview category/search/member presentation → validate completeness and conflicts → publish → verify it is discoverable and correctly purchasable/bookable → inspect audit and result.

Alternates: missing required attribute; duplicate identifier; invalid category; unsupported channel; price conflict; low or unknown inventory; media/translation/accessibility error; permission denied; concurrent edit; publish accepted but member-visible result remains stale.

Success evidence: the published record, variant/availability/price states and member-facing detail view agree, and the revision can be traced.

### D26 — Taxonomy/attributes change with live-content impact
Create or edit a category, collection, listing type or attribute → inspect linked records and existing values → choose migrate, keep, map or remove with a reason → simulate impact on search filters, saved views, product forms, navigation and active listings → resolve invalid rows → approve → publish one revision → verify affected records and discovery → monitor no-result/search and incomplete-listing metrics.

Alternates: overlapping taxonomy rules; migration failure for part of the set; a live campaign references the old category; permission conflict; rollback cannot undo an already completed transaction and requires a compensating mapping.

### D27 — Stock correction across locations
Find inventory discrepancies → choose locations and variants → compare on-hand/reserved/available with explicit definitions → inspect active reservations and in-flight orders → enter reason and proposed adjustments → dry-run count and conflicts → confirm within scope → process and read back every location → reconcile affected availability and orders → review actor, before/after and per-row failures.

Alternates: some locations fail; a reservation races the adjustment; a command times out; a retry may duplicate adjustment; the system lacks trustworthy source freshness.

### D28 — Pricing/promotion precedence
Choose market/channel and date range → inspect base price, price list, provider fee, platform fee and active promotions → create a bounded rule → define audience, eligible categories/providers, stacking, exclusions, budget/liability and effective dates → test representative baskets → show which rule wins and example totals → require finance approval when configured → launch limited/scheduled → monitor orders, cancellations and complaints → pause/expire/restore.

Alternates: overlapping promotion, stale market data, currency/tax mismatch, provider-specific exclusion, spend limit hit, publication succeeds but exposure cannot be verified.

### D29 — Process an order through fulfilment and refund
Open the order list using a saved work view → review customer/provider/line items/charges/status → resolve payment state → assign fulfilment or provider slot → record dispatch/pickup/completion → handle cancellation/return and reason → calculate eligible full/partial refund → preview customer and ledger effects → obtain separate approval if required → execute through the financial owner → read back provider, order and ledger state → notify member with an accurate outcome.

Success evidence: a single coherent timeline with each state independently confirmed; no unknown-state retry or false “completed” badge.

### D30 — Claim and operate a local business profile (Yelp-depth)
Open claim queue → inspect proof of business/representative authority and duplicate profiles → ask for missing evidence or approve/deny with a reason → link canonical provider identity and branch records → configure public profile, category, hours, service area, media and languages → preview as local member → publish → respond to review → inspect page visits, contact intent and qualified leads with distinct definitions → improve profile and measure downstream result.

Alternates: conflicting owner claims; duplicate branches; outdated hours; review removal appeal; a profile view is counted but the lead event is unknown.

### D31 — Moderate content and complete an appeal (Discourse-depth)
Open a saved review queue → inspect full post/thread, geography, linked reports, policy version and prior actions → assign reviewer and deadline → choose a distinct proportionate action → preview member consequence/message → submit and read back content/account state → process appeal with independent review where required → restore/replace action if overturned → examine pattern/false-positive report → decide whether to change a rule only after simulation.

Alternates: missing context; false/duplicated reports; partially applied actions; expired evidence; moderator conflict of interest; appeal service temporarily unavailable.

### D32 — Configure a community category, group or event
Edit category/tag/post type or create a group/event → set purpose, geography, privacy/admission rules, local moderators, required fields and visibility → inspect existing content and memberships affected by configuration → preview member app and notification changes → validate permissions and localization → publish → test member join/post/RSVP path → monitor participation and reports → amend/restore as needed.

### D33 — Publish official local communication with delivery proof
Select authorized institutional source → draft source-linked message and severity → define geography, eligible audience, language, scheduled/urgent route, expiry and correction owner → simulate reach and excluded audience → obtain appropriate approval → publish → track queued/attempted/provider-accepted/delivered/failed/unknown separately → inspect feedback → issue linked correction or expiry → close only when delivery and follow-up obligations are reconciled.

### D34 — Improve community discovery from unanswered questions and failed searches
Review community analytics and search-query report → select geography/time/cohort → inspect representative unanswered posts and no-result/no-click searches → classify cause as missing supply, taxonomy, eligibility, stale content or ranking issue → choose a supported change (content campaign, category, search rule or UI experience) → test sampled queries and member personas → staged release → measure useful result clicks, answers, resolved questions and safety guardrails → promote/pause/restore.

### D35 — Review a domain permission and scope
Select staff member/team → choose concrete job and domain capabilities (view/edit/import/export/publish/refund/enforce/approve) → select provider/geographic/channel scope and expiry → show a human-readable effective-access preview and potential conflicts → request independent approval → verify applied policy → test allowed and denied actions → log review date and recertification.

