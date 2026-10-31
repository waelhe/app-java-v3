# Agent-skills policy — vendored skills are reference, not an authority

> Governs the `.agents/skills/**` surface and its registry
> `docs/governance/plans/agent-skills-pins.json`. Enforcement lives in
> `PlatformGovernanceFilesTest.agentSkillsVendoredContentIsRegisteredAndDriftIsDetected`
> (fail-closed) — the same class of "config that lies" pin as the other
> governance files that test guards.

## 1. Why this policy exists

`.agents/skills/` vendors external agent skills directly into the
repository (the Neon platform-operations skills were installed first;
the Saleor behavior-reference skill followed). Until 2026-10-30 nothing
governed that surface: no registry, no version pin, no integrity check —
a silently swapped skill file, or an entire unregistered skill, would
rewire agent behavior with no review trail, against the same discipline
the repository already applies to every other external artifact
(`productionPinsMoveOnlyByReviewedChange` in `saleor-source-pins.json`).

## 2. Role and precedence — reference, not an authority

A vendored skill is specialized reference input for agents reviewing or
implementing platform work. It is reference, not an authority: on any
conflict the precedence ladder is, in order:

1. `docs/governance/plans/saleor-source-pins.json` — the pinned Saleor
   3.23.43 reference schema (blob-verified) and the Dashboard pins.
2. `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md`
   — the D-SALEOR-FULL-001 canonical plan and its domain matrix.
3. `docs/governance/plans/saleor-contract-divergences.csv` — the
   registered divergences ledger.
4. The platform code and its tests — measured facts.

A skill text never overrides any layer above. A discovered conflict is
registered where its domain keeps decisions, always through a reviewed
PR and never silently adopted into code: Saleor wire-contract conflicts
go to `saleor-contract-divergences.csv`; conflicts outside the Saleor
wire contract (for example Neon operational guidance contradicting the
platform's measured reality) go to the defect-knowledge evidence ledger
under `docs/governance/defect-knowledge/`. Reading a skill never changes
an operation's disposition in the canonical plan.

## 3. What is vendored (inventory)

Official Saleor source: `saleor/agent-skills` — five skills exist
upstream; the inventory below is complete.

| Skill | State | Reason |
| --- | --- | --- |
| `saleor-core` | ADOPTED (pinned) | Discount precedence, order-level discount allocation across lines, and stock availability modes — direct review input for the P3 wave (commercial context, promotions, tax and configuration — 85 roots) and the P4 wave (checkout, order, payment and shipping lifecycle — 111 roots) of D-SALEOR-FULL-001. |
| `saleor-storefront` | NOT ADOPTED | Storefront API patterns; this backend pins its wire contract from the Saleor reference schema, not from storefront examples. Revisit if a storefront consumer project needs it. |
| `saleor-app` | NOT ADOPTED | Saleor app protocol (manifest, appbridge, webhooks); the platform runs no Saleor app registry — `saleorBackendIsDeployed: false`. Revisit only for a real external integration task. |
| `saleor-configurator` | NOT ADOPTED | Store-configuration-as-code for a running Saleor instance; no second Saleor engine exists here by architectural decision. |
| `storefront-builder` | NOT ADOPTED | Storefront scaffolding; same reason as `saleor-storefront`. |

The Neon platform-operations skills (`neon`, `neon-postgres`,
`neon-auth`, `neon-functions`, `neon-object-storage`, `neon-realtime`,
`neon-ai-gateway`, `neon-postgres-branches`,
`neon-postgres-egress-optimizer`) remain vendored for the production
Postgres host; this policy retroactively pinned them (verified
byte-identical to `neondatabase/agent-skills@51c7d594`, 34 files).

## 4. Install and update procedure

1. Pin first: record the source repository, the exact upstream commit,
   the license, and a SHA-256 per file in the registry — in the same PR
   that vendors the files. `verification: PINNED_UPSTREAM` requires git
   blob SHA equality with the source repository at that commit.
2. Vendor the skill under `.agents/skills/<name>/` byte-for-byte; the
   source repository's root LICENSE is vendored beside the skill when
   the skill directory carries no license of its own (`attribution` in
   the registry records its origin).
3. The PR rides the full gates (CI, CodeRabbit, review) like any other
   change; nothing is installed outside a reviewed PR.
4. Updates move only by a reviewed PR that updates files and registry
   in the same change — a vendored diff without a registry update fails
   the governance test, and a registry entry without its files fails
   too.
5. Never run `npx skills add ...` (or any skill-installer command shown
   inside a vendored skill's own README) against this repository: the
   installer writes the upstream head into the install path — unpinned,
   unreviewed — and silently overwrites the pinned snapshot. The
   governance test fails the resulting diff; the change must be reverted
   or re-registered through the reviewed-PR procedure above. The install
   commands in vendored READMEs describe the upstream project's
   workflow, not this repository's.

## 5. Measured facts

- Registry created 2026-10-30: 10 skills, 49 files, all
  `PINNED_UPSTREAM` (40 upstream skill files plus 10 vendored
  attribution LICENSE copies — one per skill — since no upstream skill
  directory carries its own license file).
- `saleor-core` pinned at `saleor/agent-skills@fb1a5725` (2026-08-06).
  Its license record is factual, not interpretive: the repository root
  declares `BSD-3-Clause AND CC-BY-4.0`, while the skill's SKILL.md
  frontmatter self-declares MIT — both statements are preserved in the
  registry; reconciliation belongs upstream.
- The Neon skills pinned at `neondatabase/agent-skills@51c7d594`
  (2026-10-16); Apache-2.0, with the upstream root LICENSE vendored
  verbatim beside each skill (`attribution` in the registry).
- The registry records seven known upstream errata in the vendored
  `saleor-core` text (`knownUpstreamErrata`), each with its location and
  the verified fact or its review provenance — two measured directly
  against `saleor/saleor@3.23.43` (the undefined `unit_discount_reason`
  in the discount-precedence snippet; `StockQuerySet` living in
  `saleor/warehouse/models.py`, not the nonexistent `managers.py`).
  Errata are warnings for consumers, recorded here because vendored
  files stay byte-for-byte: fixing them belongs upstream, and a fresh
  upstream snapshot re-vendors through the reviewed-PR procedure.
