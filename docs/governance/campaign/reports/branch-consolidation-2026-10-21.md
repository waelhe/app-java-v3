# Branch Consolidation Record — 2026-10-21 (A0 stewardship)

Owner directive: review ALL work-related branches and deal with them; the campaign
runs SEQUENTIALLY (one active writer at a time) — dangling parallel-work branches
are failure residue, not evidence.

## Dispositions (live API verification before every action)

| Branch | Verified state | Disposition |
|---|---|---|
| `agent/saleor-p1-identity-customers-read` | behind candidate, 0 unique commits | DELETED — fully merged into candidate (ledger rows: customers) |
| `agent/saleor-p1-identity-permission-groups-read` | behind candidate, 0 unique commits | DELETED — fully merged (permissionGroup(s)) |
| `agent/saleor-p2-catalog-attributes-read` | behind candidate, 0 unique commits | DELETED — fully merged (attribute(s)) |
| `agent/saleor-p2-catalog-categories-read` | 1 unique commit 6e70621c9e (2 test files, +858 lines) | MERGED into candidate as ca2a6baff6 (packet campaign-b4-a JOB 1 — restores the marketplace-catalog JaCoCo 0.70 gate), then DELETED |
| `agent/saleor-p2-catalog-typed-attributes-read` | behind candidate, 0 unique commits | DELETED — fully merged |
| `agent/saleor-p2-catalog-typed-attributes-read-r2` | diverged; 12-file draft superseded by the refined b3 wave on candidate | DELETED — superseded draft; final versions of all 12 files live on candidate |
| `agent/saleor-p3-pricing-discounts-read` | behind candidate, 0 unique commits | DELETED — fully merged (sale(s)/voucher(s)/promotion(s) read roots) |
| `agent/saleor-p3-pricing-vouchers-promotions-read` | behind candidate, 0 unique commits | DELETED — fully merged |
| `feat/android-discovery-rails` | behind main, 0 unique commits | DELETED — long merged into main |
| `feat/saleor-returns-refunds-graphql` | 83 unique commits, pre-contract | DELETED — superseded pre-campaign attempt; its 5 sibling chain branches (feat/saleor-catalog-categories-product-media, feat/saleor-cart-checkout-orders-graphql, feat/saleor-seller-fulfillment-graphql, feat/saleor-order-returns-refunds-graphql, docs/saleor-canonical-truth-sync) were already deleted by the owner; evidence preserved in closed PRs #679/#680/#681/#683/#688; P4 re-implements returns/refunds roots under the frozen contract pack |

## Retained (deliberate)

| Branch | Reason |
|---|---|
| `integration/saleor-full-commerce-20261021` | THE campaign candidate lane (head ca2a6baff6 after this consolidation) |
| `main` | frozen release lane; no merges during build phase |
| `graphql-contract` | auto-managed GraphQL contract publication snapshot ("v6 from ea0031a97d…" = current main) — steward-owned automation artifact, not a work branch |
| `feat/direct-delivery-e2e` | 7 unique commits, NON-campaign delivery feature — left untouched, out of campaign scope |
| `waelhe-patch-1` / `waelhe-patch-2` | non-campaign historical patches — left untouched, out of campaign scope |

## Failure-mode correction (owner-directed)

Parallel subagent dispatch repeatedly failed (timeouts/deadlocks: "several agents
launched, most fail"). For the 2026-10-21 campaign the execution mode was therefore strictly
sequential — an operational exception specific to that campaign, recorded here
as history, not a change to the approved execution protocol (the playbook and
the frozen contracts remain the binding reference): A0 (this session) implemented
packets directly on the candidate lane with packet-local commits, or dispatched
ONE agent at a time only when a packet was fully specified.
No shared-file races are possible with a single writer; A0 stewardship invariants
(SDL additivity, ledger CSV via csv module, divergence registry, Flyway allocation)
are enforced per packet as before.
