# Campaign Directory — Full Saleor Commerce Implementation

This directory holds the **operational artifacts of the single continuous build campaign** (playbook §2). It does not replace the governing plan; it executes it.

| File | Role |
|---|---|
| `saleor-multi-agent-execution-playbook.md` | The owner-approved execution protocol: phases A–D, agent topology A0–A7, collision rules, work-packet/completion formats, review and merge protocol |
| `A0-EXECUTION-DIRECTIVE-20261028.md` | Current A0 identity and live A1–A7 assignments. Root names are derived from the current 458-root ledger; this file supersedes the stale root-selection lists in the 2026-10-21 agent packets. |
| `saleor-agent-skills-semantic-audit-directive.md` | Mandatory supplement defining pinned Saleor skill use, source-backed semantic rules, retroactive audits of existing INTEGRATED roots, test evidence, and how A1–A7 apply it without changing ownership or campaign flow. |
| `frozen-contracts.md` | The frozen shared contract pack (Phase A item 5): base SHAs, SDL/global-ID/pagination/authorization/error/money/SPI/migration/testing rules binding every agent |
| `saleor-458-root-inventory.csv` | **The campaign ledger** (Phase A item 4 + §12): one row per exact root of the pinned Saleor 3.23.45 schema — kind, category, wave, owner agent, disposition, owning modules, permission scope, exact signature, status, evidence. Generated from the pinned schema (blob SHA verified); totals equal the checked-in domain matrix (458 = 90 Query + 342 Mutation + 26 Subscription) |
| `decisions/` | A0-owned, durable decision records for cross-cutting contract questions; each decision must also be reflected in the divergence registry and ledger where applicable. |
| `sdl/` | Proposed SDL fragments from domain agents (one file per packet), integrated into the shared SDL by A0 only |

Generation: the inventory is produced by parsing the pinned schema (`saleor/saleor@3.23.45:saleor/graphql/schema.graphql`, blob `b8fa0dd8…`) and joining the checked-in domain matrix (`docs/governance/plans/saleor-commerce-domain-matrix.csv`). Subscription roots count under wave P6 with their own `@doc` category kept in `source_category_detail`; roots without `@doc` category are the matrix's `Uncategorized` bucket.

Rules of use:
1. Read `frozen-contracts.md` before touching code; it is binding for every agent.
2. Track progress from the ledger, never from optimistic prose; a root reaches `RELEASE_READY` only with tests and owner/scope evidence.
3. A0 alone edits the ledger, the shared SDL, registries and this directory's integration outputs.
4. Apply the semantic-audit directive together with the A0 assignment and multi-agent playbook; it supplements them and does not create a second source of truth.
