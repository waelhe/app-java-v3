# Saleor Agent-Skills Source-Provenance Evidence and Adoption Proposal — the Five Official Skills

**Status (measured 2026-10-31):** The source-discovery and archive-verification spike is complete. The proposed `saleor-core` provenance migration is implemented by PR #773, which is open against the integration branch at the time of this record. This document preserves the evidence and adoption rationale; it is not itself the file migration. PR #773 updates the existing `saleor-core` README/provenance registry/policy/governance test and resource-surface record; it does not bulk-install the other four skills.
**Measured:** 2026-10-31 (all evidence below was captured live on this date; SHA-pinned, never a symbolic `main`).
**A0 role:** Contract Steward / provenance spike author.

## 1. Why this proposal exists

The promotion plan §3 records the upstream change: Saleor's official agent-skills distribution moved from `saleor/agent-skills` to `saleor/saleor-docs` (skills live with the documentation), with a machine-readable discovery index. At the time of the spike, the registry from PR #771 pinned `saleor-core` to `saleor/agent-skills@fb1a57255f58950962f6e474ccba1cade40f5163`. This spike measured the new official source, verified every advertised bundle digest and proposed the source migration. PR #773 carries that migration into the repository through the reviewed pin/policy/test update; the remaining four skills stay unadopted.

## 2. Discovery index — measured response

- URL: `https://docs.saleor.io/.well-known/agent-skills/index.json` (fetched 2026-10-31).
- Schema: `https://schemas.agentskills.io/discovery/0.2.0/schema.json`.
- Advertises exactly **five skills**, each as a `type: archive` tar.gz with a sha256 digest:

| Skill | Advertised digest (sha256) | Archive verified (2026-10-31) | Bytes |
|---|---|---|---|
| `saleor-app` | `6d59d82871be13ff661a377b0d727c706bb7d5ee0f924900700e583db78d993f` | **MATCH** | 22,117 |
| `saleor-configurator` | `060bd86bed738043064c79279c657095d85434a88c2a148532e46604679b1c48` | **MATCH** | 10,760 |
| `saleor-core` | `257525713919a5f7490e65cbfad7e1d99fc6f93378c957b11289275ae545c6af` | **MATCH** | 13,146 |
| `saleor-storefront` | `f4949c787717a549a42ee2ff6289e77796296c522366eb11882bace242c7661d` | **MATCH** | 24,826 |
| `storefront-builder` | `e6cafd7686b459a5b268c876a3a428a963bd99b80acaaaf3ac2761f8ab341b50` | **MATCH** | 8,278 |

The developer-facing page `https://docs.saleor.io/developer/ai` is the human entry point for the same index.

## 3. Source snapshot — exact capture

- Repository: `saleor/saleor-docs` (public).
- Snapshot commit: **`2cdf6590e838929c3f74c16e982cfdc11073c2d8`** (committer date 2026-10-30T11:50:24Z, "fix(build): re-add deleted socket.yml (#1874)").
- Any future pin must reference this exact SHA (or a later reviewed one), never the moving `main`.

## 4. Per-skill file inventory at the snapshot

Measured from the git tree at `2cdf6590` (blob sizes in bytes):

- **`saleor-core` (6 files)** — `LICENSE` 1,766 · `README.md` 1,644 · `SKILL.md` 3,375 · `rules/discount-precedence.md` 7,531 · `rules/order-discount-allocation.md` 9,847 · `rules/stock-availability-modes.md` 14,535
- **`saleor-app` (16 files)** — `SKILL.md`, `README.md`, `LICENSE`, `references/apl-implementations.md`, `references/app-manifest-schema.md`, `rules/dashboard-appbridge.md`, `rules/data-graphql.md`, `rules/data-settings.md`, `rules/dev-debug.md`, `rules/payment-app.md`, `rules/permissions-access-scopes.md`, `rules/protocol-auth.md`, `rules/protocol-manifest.md`, `rules/webhook-async.md`, `rules/webhook-external.md`, `rules/webhook-sync.md`
- **`saleor-configurator` (9 files)** — `AGENTS.md` 11,950 · `SKILL.md`, `README.md`, `LICENSE`, `rules/cli-commands.md`, `rules/config-schema.md`, `rules/deploy-pipeline.md`, `rules/diff-behavior.md`, `rules/identity-strategies.md`
- **`saleor-storefront` (12 files)** — `AGENTS.md` 36,701 · `SKILL.md`, `README.md`, `LICENSE`, `references/saleor-key-directories.md`, `rules/api-data-model.md`, `rules/api-graphql-patterns.md`, `rules/api-investigation.md`, `rules/api-permissions.md`, `rules/channels-purchasability.md`, `rules/checkout-lifecycle.md`, `rules/products-variants.md`
- **`storefront-builder` (2 files)** — `SKILL.md` 18,732 · `LICENSE`
- Plus the shared `skills/LICENSE` (1,766 bytes) and `skills/README.md` (the distribution note).

## 5. `saleor-core` behavior-level comparison — vendored vs new source

Compared file-by-file: the vendored `.agents/skills/saleor-core` (pinned `saleor/agent-skills@fb1a5725`, 49/49 registry hashes green in the reconciliation merge `6608a869`) against the digest-verified archive from `saleor/saleor-docs@2cdf6590`:

| File | Result |
|---|---|
| `LICENSE` | **byte-identical** |
| `SKILL.md` | **byte-identical** |
| `rules/discount-precedence.md` | **byte-identical** |
| `rules/order-discount-allocation.md` | **byte-identical** |
| `rules/stock-availability-modes.md` | **byte-identical** |
| `README.md` | differs in exactly **one line**: the install command changed from `npx skills add saleor/agent-skills --skill saleor-core` to `npx skills add . --skill saleor-core` |

**Behavior surface: zero change.** The three published rule families — the entire authority surface this campaign consumes — are unchanged. The README line is moot in this repository: running `npx skills add` is forbidden by policy and plan §3.1.8 regardless of its argument.

Upstream's own declaration (quoted verbatim from `skills/README.md` at the snapshot):

> "These skills are maintained with the documentation in this repository. The complete original skill directories, rules, compiled guides, references, and license were imported from commit `fb1a57255f58950962f6e474ccba1cade40f5163`. Source: saleor/agent-skills."

This is a direct upstream statement of the provenance chain our registry already pins — independently confirmed by the byte-identical comparison above.

**Known upstream errata:** all seven errata recorded in `agent-skills-pins.json` target files that are byte-identical in the new source; every entry carries over unchanged.

Per-file sha256 of the new-source `saleor-core` (for the migration PR's registry update):

```
c68109c572d21e0fdec9e5e55084b24be8208f2b6092d744127d6fac90106858  LICENSE
8519dc5e18aeda3b6dae09ab62421d5ce32daf8734647740248018642175d687  README.md
5fa2cc7f9eeb42d02e80dbd3b6a78ef3d38593d3658e5699e84d86399a4da3b5  SKILL.md
90e9e9aa793c4ec123cfd7618df498aa0daa1d9c96ea32f4b5004d3e909fbf6d  rules/discount-precedence.md
8c61d4e280a0831fa90b483897be74ed4ef571459044bb0ed6ed37842c23b411  rules/order-discount-allocation.md
9f55952873d00b14d170644caab52ef245efd2a46d5250f44f40acf622e8a2b4  rules/stock-availability-modes.md
```

## 6. License evidence — recorded separately, no reconciliation invented

- **Per-skill `LICENSE`** (all five skill directories, identical 1,766-byte file): BSD 3-Clause terms followed by a CC-BY-4.0 notice specifically for artwork; the file is byte-identical to the legacy `saleor/agent-skills` license snapshot.
- **`saleor/saleor-docs` repository-root `LICENSE`** is a different file: BSD 3-Clause plus an ISC-license notice for bundled Lucide icons. GitHub classifies that custom root license as `NOASSERTION`.
- **Skill frontmatter:** each `SKILL.md` independently self-declares MIT. These are three distinct pieces of source evidence: per-skill LICENSE, SKILL.md frontmatter, and repository-root LICENSE. The root-license difference from the old repository must not be misreported as removal of CC-BY-4.0 from the skill package, because the per-skill LICENSE still carries that artwork notice. Per plan §3.1.5, no legal precedence is inferred; the registry records the statements separately.

## 7. Proposed dispositions

| Skill | Disposition | Rationale |
|---|---|---|
| `saleor-core` | **Implemented in PR #773 (pending its integration merge)** — re-pin from `saleor/agent-skills@fb1a5725` to `saleor/saleor-docs@2cdf6590` with the registry, per-file hashes, license evidence, errata carry-over, governance test and behavior-neutral README update in one reviewed change | The three behavior rules and SKILL.md are byte-identical; upstream records the import from the historical SHA; the old repository is no longer the official distribution channel |
| `saleor-storefront` | **Applicability catalog** (measured digest + inventory above); vendoring deferred until A2/A3/A4 request adoption through §3.1 | Consumer/API usage reference per plan §4; adoption is a workstream-triggered decision, not a blanket install |
| `saleor-app` | **Applicability catalog**; conditional reference for A4/A5 real app/webhook protocol work only | Plan §4 boundary: no app registry, no AppBridge runtime, no second integration authority |
| `saleor-configurator` | **Applicability catalog**; read-only design reference for A0/A3/A5 | Plan §4 boundary: never run against Saleor, no second configuration authority |
| `storefront-builder` | **Applicability catalog**; buyer-journey analysis only | Plan §4 boundary: never execute its bootstrap steps |

No unadopted skill becomes a fake vendored registry entry (plan §3.1.7).

## 8. Implementation and verification record for PR #773 (maps 1:1 to plan §3.1)

1. **Done in the PR:** pin the exact `saleor/saleor-docs@2cdf6590e838929c3f74c16e982cfdc11073c2d8` snapshot; no symbolic `main` is used for vendored bytes.
2. **Done in the evidence record:** retain the discovery-index response and all five advertised bundle digests (§2 and `saleor-ai-resource-surface.json`).
3. **Done in the PR/registry:** retain source path, repository, commit, version metadata, per-file SHA-256 and license/attribution for the adopted `saleor-core` snapshot. Unchanged files are verified, not needlessly rewritten.
4. **Done in the evidence record:** publish the behavior-level comparison (§5 — zero change to behavior files; one behavior-neutral README line).
5. **Done in the registry:** preserve all seven known upstream errata and record the new license evidence separately (§6).
6. **Done in the PR:** update the pin registry, policy/catalog, resource-surface file and fail-closed governance test together.
7. **Done in the PR:** keep the four other skills in the applicability catalog only; do not install or register them as adopted.
8. **Unchanged guard:** never run `npx skills add` in this repository.

PR #773 must still pass its final-head CI, Integration Test, security and review gates before an owner-authorized merge into integration.

**Boundaries (unchanged):** this proposal changes no wire contract, no Saleor 3.23.43 source pin, no Dashboard 3.23.39 pin, no SDL, no root disposition, no approved divergence. Skills remain specialist reference material, never a wire-contract authority.
