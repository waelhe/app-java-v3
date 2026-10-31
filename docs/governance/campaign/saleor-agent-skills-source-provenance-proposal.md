# Saleor Agent-Skills Source-Provenance Proposal — the Five Official Skills

**Status:** Proposal (§10.5 of the promotion plan `commerce-main-promotion-and-saleor-skills-plan.md`). No skill file was vendored, updated, or removed by this document. Execution requires the dedicated, reviewable provenance PR defined in plan §3.1.
**Measured:** 2026-10-31 (all evidence below was captured live on this date; SHA-pinned, never a symbolic `main`).
**A0 role:** Contract Steward / provenance spike author.

## 1. Why this proposal exists

The promotion plan §3 records the upstream change: Saleor's official agent-skills distribution moved from `saleor/agent-skills` to `saleor/saleor-docs` (skills live with the documentation), with a machine-readable discovery index. Our current registry (`docs/governance/plans/agent-skills-pins.json`, from PR #771) pins `saleor-core` to `saleor/agent-skills@fb1a57255f58950962f6e474ccba1cade40f5163`. This spike measures the new official source exactly, verifies every advertised bundle digest, and proposes the source migration — without touching a single vendored byte.

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

- **Per-skill `LICENSE`** (all five skills, identical 1,766-byte file): BSD 3-Clause, "Copyright (c) 2026, Saleor Commerce".
- **`saleor/saleor-docs` root `LICENSE`**: BSD 3-Clause (Saleor Commerce, 2019-2024) **plus** an ISC-license notice for bundled Lucide icons (irrelevant to the skills trees). GitHub's API classifies the root as `NOASSERTION` (custom text).
- **Measured difference from the old source:** our registry's record for `saleor/agent-skills` states "BSD-3-Clause AND CC-BY-4.0 (repository root)"; the new root LICENSE text carries **no CC-BY-4.0 statement**. Both statements are recorded here as measured facts. Per plan §3.1.5, no legal reconciliation is invented; the migration PR records the new source's evidence in its own registry fields.

## 7. Proposed dispositions

| Skill | Disposition | Rationale |
|---|---|---|
| `saleor-core` | **Provenance migration now** — re-pin the registry source from `saleor/agent-skills@fb1a5725` to `saleor/saleor-docs@2cdf6590` through the reviewed §3.1 PR (registry, per-file hashes, license evidence, errata carry-over, governance test, and the 1-line README vendored update — all in the same PR) | Byte-identical authority surface; upstream itself declares the import from our pinned SHA; the old repository is no longer the official distribution channel |
| `saleor-storefront` | **Applicability catalog** (measured digest + inventory above); vendoring deferred until A2/A3/A4 request adoption through §3.1 | Consumer/API usage reference per plan §4; adoption is a workstream-triggered decision, not a blanket install |
| `saleor-app` | **Applicability catalog**; conditional reference for A4/A5 real app/webhook protocol work only | Plan §4 boundary: no app registry, no AppBridge runtime, no second integration authority |
| `saleor-configurator` | **Applicability catalog**; read-only design reference for A0/A3/A5 | Plan §4 boundary: never run against Saleor, no second configuration authority |
| `storefront-builder` | **Applicability catalog**; buyer-journey analysis only | Plan §4 boundary: never execute its bootstrap steps |

No unadopted skill becomes a fake vendored registry entry (plan §3.1.7).

## 8. Execution checklist for the migration PR (maps 1:1 to plan §3.1)

1. Pin the exact snapshot `2cdf6590…` (or a fresher reviewed capture — re-verify digests if so).
2. Record the discovery-index response and the five advertised digests (§2 above is the capture).
3. Vendor the reviewed snapshot files; per-file SHA-256, source path, repository, commit, version metadata, license/attribution (§5 hashes for `saleor-core`).
4. Publish the behavior-level change report (§5 — zero behavior change; one README line).
5. Preserve the seven known-upstream-errata entries unchanged; record the new license evidence separately (§6).
6. Update pin registry, policy/catalog, per-file hashes, license/attribution, and the fail-closed governance test in the same PR.
7. Keep the four unadopted skills in the applicability catalog with their measured digests (§2/§4).
8. Never run `npx skills add` in this repository (unchanged rule).

**Boundaries (unchanged):** this proposal changes no wire contract, no Saleor 3.23.43 source pin, no Dashboard 3.23.39 pin, no SDL, no root disposition, no approved divergence. Skills remain specialist reference material, never a wire-contract authority.
