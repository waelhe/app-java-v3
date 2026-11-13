---
title: Saleor AI resource adoption map
asOf: 2026-11-13
---

# Saleor AI resources — adoption and non-adoption map

## Decision

Adopt Saleor's **agent-resource architecture and engineering methods**, not Saleor's runtime stack. The platform remains a Spring modular monolith and uses Saleor 3.23.43 as a pinned commerce contract/reference; it does not deploy a second Saleor backend or database.

- Adopt: machine-readable resource discovery, read-only documentation retrieval, small task-routing skills, focused rules loaded on demand, prioritized risk categories, source pinning and content-hash drift checks.
- Adapt: provider-operation identity, unknown outcomes, idempotent event reconciliation, server-side authorization, schema-to-source investigation, preflight/diff/idempotent release planning, and mode-aware diagnostics.
- Do not install or execute: Saleor App SDK/AppBridge, Configurator CLI, storefront scaffolding, live Saleor MCP, moving-head skill installers, or a second Saleor engine.

## Verified official sources

| Resource | Verified snapshot | Purpose |
|---|---|---|
| [Saleor Agents docs](https://docs.saleor.io/developer/ai) | Last updated 2026-11-06 | Documents Documentation MCP, Agent Skills, Saleor MCP and schema-only MCP |
| [saleor/saleor-docs](https://github.com/saleor/saleor-docs) | `9853f8fb08f6622f3d6b23b3fff2683215aaa5ff` (2026-11-12) | Current docs and AI resource-discovery source |
| [saleor/agent-skills](https://github.com/saleor/agent-skills/tree/fb1a57255f58950962f6e474ccba1cade40f5163) | `fb1a57255f58950962f6e474ccba1cade40f5163` | Skill source snapshot; current saleor-docs README says its skills were imported from this exact commit |
| [Agent Skills index](https://docs.saleor.io/.well-known/agent-skills/index.json) | SHA-256 digests per published bundle | Machine-readable list of five skills |
| [AI catalog](https://docs.saleor.io/.well-known/ai-catalog.json) | Current published manifest | Catalog of docs MCP, five skills, docs API and llms.txt |
| [Agent readability](https://docs.saleor.io/agent-readability.json) | Current manifest | Markdown, docs API, MCP, AI catalog and sitemap links |
| [llms.txt](https://docs.saleor.io/llms.txt) | Current docs index | Page discovery and exact published schema/section indexes |
| [MCP server card](https://docs.saleor.io/.well-known/mcp/server-card.json) | Server version 2.0.3 | Read-only tools and transport |
| [Skills CLI](https://github.com/vercel-labs/skills) | `b716f9dc2ba36b6aa8f0080615e5a3a6caeac63b` (2026-11-12) | Reference for lock files, hashes, drift detection and frozen-lock behavior; not executed here |

The current `llms.txt` lists the Saleor 3.23.43 schema, matching this repository's pinned reference version. It does not replace the repository's own immutable source pin.

## Tool boundaries

### Documentation MCP — read-only

Endpoint: `https://docs.saleor.io/mcp`.

The server card lists `search_docs`, `get_page`, `list_pages`, and `get_navigation`. The server is read-only. It searches and reads documentation; it does not connect to a Saleor instance or execute GraphQL operations.

Use it to discover and read official docs. Do not treat it as live runtime verification.

### Saleor MCP — privileged live data, not adopted

The official tool surface is distinct from Documentation MCP. For a real Saleor Cloud store, the documented operating sequence is:

1. `connection_info` first — establish which store is connected and which access/upgrade state applies.
2. `introspect_schema` — inspect the permitted store schema before planning an operation.
3. `run_query` — read live store data within the connection's granted scope.
4. `run_mutation` — perform a permitted write only when the caller's access allows it.
5. `run_high_impact_mutation` — use the separately governed high-impact path; the server enforces its permission/approval boundary.

The MCP server applies permission-aware access, keeps privileged store credentials server-side, and records activity. The self-hosted open-source MCP option is read-only; the live Saleor Cloud option is the privileged path. The complete tool contract is the server's current MCP `tools/list` response, not a copied or assumed schema.

Neither live option is needed for app-java-v3: this platform does not deploy a Saleor backend or second Saleor database. Do not attach a live store to the defect-knowledge workflow, and do not confuse permission to read documentation with permission to query or mutate a store.

### Identity, scopes and operation safety — live MCP reference only

The official authentication model distinguishes two identities:

- **User OAuth:** an assistant used by a staff member acts as that signed-in staff member.
- **Access key:** an agent or automation running without a person acts as the app, bounded by the key's own area-level access.

The permission levels are **Read**, **Edit**, and **Full**. Everyday mutations require Edit; high-impact mutations require Full. A requested OAuth scope can narrow existing access but cannot widen it. Access-key changes and revocation take effect on the next request; a connection can be revoked. MCP clients do not receive Saleor tokens.

Operational safeguards documented by Saleor:

1. Call `connection_info` before work and after a permission change. Request a permission upgrade only after the user explicitly agrees.
2. Use `introspect_schema` to establish the actual available operations and arguments; do not guess from a remembered schema.
3. Use `run_query` only for queries. Use the mutation tools only for mutations; Saleor checks permissions and reviewed operations before sending them.
4. Check the mutation's own `errors` in the returned `{ data, errors }` response. A returned payload alone is not proof that the requested business action succeeded.
5. Keep the mutation summary factual and minimal. Saleor says summaries are retained in its activity log for 90 days; use object IDs and values, not names, emails, addresses, phone numbers, gift-card/voucher codes, passwords or tokens.
6. Treat the advertised `tools/list` response as the live contract. Tool visibility depends on the connection's permissions.

Saleor also validates documents before execution: queries and mutations cannot be mixed; subscriptions are unsupported; credential fields such as `accessToken` and `authToken` cannot be selected; and the documented document/operation size limits apply. These are **Saleor protocol constraints**, not platform-wide limits.

Transfer only the engineering principles to app-java-v3: distinguish human actors from machine principals, enforce least privilege at each server-side door, require explicit consent for elevation, preserve revocation and auditability, avoid sensitive data in operational summaries, inspect the live contract before invocation, and verify the operation-specific result. Do not import Saleor's permission names, token lifetimes, OAuth endpoints, limits, or approval mechanics as local platform contracts.

### Schema-only GraphQL MCP — contract inspection only

Saleor also documents a GraphQL MCP server pointed at a downloaded schema. If used, it is a schema-inspection tool only: consume the exact pinned SDL from this repository (Saleor 3.23.43), never introspect upstream `main`, and never use it as a connection to a second backend.

### Skills CLI — reference only

The Skills CLI documentation describes listing skills before installation, selecting specific skills, tracking content hashes in `skills-lock.json`, checking for upstream drift/local changes, and frozen-lock behavior in CI. This repository already has a reviewed-vendoring mechanism (`agent-skills-pins.json`, per-file SHA-256, and `PlatformGovernanceFilesTest.agentSkillsVendoredContentIsRegisteredAndDriftIsDetected`). Keep that stricter policy. Do not run `npx skills add` or `npx skills update` here.

## Skill-by-skill adoption matrix

| Published skill | Decision | Transferable ideas | Not transferred |
|---|---|---|---|
| `saleor-core` v1.2.0 | Already adopted and pinned at `saleor/agent-skills@fb1a5725` | Keep as Saleor 3.23.43 behavior reference for discounts, order allocation and stock modes | Never use as platform authority; honor the known errata in `agent-skills-pins.json` |
| `saleor-app` v1.0.0 | Not installed | Stable provider operation identity, unknown outcomes, provider verification, durable webhook handling, per-route server authorization | App manifest, AppBridge, APL, Next.js SDK/runtime |
| `saleor-storefront` v1.0.0 | Not installed | Investigation order: generated schema/types → official docs → pinned source for behavior → local consumers/tests; intentional null handling | Storefront runtime, Saleor token filtering and Saleor field semantics |
| `saleor-configurator` v1.0.0 | Not installed | Preflight validation, dependency ordering, diff-before-apply, idempotent convergence, per-stage reports and partial-failure recovery | Configurator YAML/CLI or a second Saleor engine |
| `storefront-builder` v2.0.0 | Not installed | Progressive disclosure and step-scoped workflows | Frontend scaffolding and storefront UX conventions |

## Adaptation into app-java-v3

| Saleor pattern | Local adaptation | Canonical rule pack |
|---|---|---|
| Payment app: stable reference, distinct provider identities, timeout=unknown, verify provider result, reconcile retries | Stable payment/operation identity, original PSP object/account, pending/unknown state, ledger reconciliation, split-tender legs | `financial-effects-and-reconciliation.md` |
| Async webhook: signature, typed payload, retry, durable acknowledgement, out-of-order events | Signed callbacks, inbox/outbox/Publication Registry, at-least-once idempotency, no slow I/O under DB locks | `async-events-and-reconciliation.md` |
| Permission scopes: UI visibility differs from server authorization; user vs app scope | Human actor vs service principal vs tenant/org delegation; server enforcement on every API/job/read door | `authorization-and-data-scope.md` |
| API investigation: schema/types → docs → source → local behavior | Pinned Saleor SDL/OpenAPI + Spring/DGS source + local tests; explicit null/error/enum semantics | `contracts-and-source-investigation.md` |
| Configurator: validate → dependency order → diff → apply → report → reconcile | Railway/config validation, Maven/Flyway order, trusted CI and post-deploy reconciliation | `configuration-and-release-reconciliation.md` |
| Stock modes: two modes have different semantics; dispatch at boundary; mode-aware diagnostics/tests | General mode/feature-flag discipline; separate availability predicates and explicit fixtures per mode | `mode-aware-behavior-and-diagnostics.md` |
| Skills packaging: summary + focused rules + triggers/priorities | Small repository-owned defect-knowledge router with focused packs; canonical rulebook/ledger remain authoritative | `SKILL.md` and `rules/` |

## Strict non-transfer boundary

Never copy these into app-java-v3 merely because they appear in a Saleor skill: Saleor App manifest/AppBridge/APL; Saleor permission names and JWT middleware; Saleor webhook timeout/retry numbers; warehouse/channel/shipping-zone fields; Saleor discount/gift-line/GraphQL semantics; Configurator CLI or YAML schema.

A Saleor-specific behavior enters the platform contract only through D-SALEOR-FULL-001 and the pinned schema/divergence governance. A general engineering principle enters defect knowledge only after it is adapted to a local invariant and linked to local evidence.

## Future investigation procedure

1. Discover the resource from the AI catalog or `llms.txt`; do not guess URLs.
2. Read the skill summary, then only the rule(s) matching the task trigger.
3. Record repository, commit, file path, license and content digest.
4. Separate documented contract, upstream implementation, local measured behavior and proposal.
5. Verify critical claims against pinned source and tests. Record upstream errata without silently rewriting vendored files.
6. Add local evidence only for an app-java-v3 defect or reusable invariant—not merely because a Saleor-specific example exists.
7. Update source and hash registries together in a reviewed PR.
