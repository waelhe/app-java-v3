# Campaign packet campaign-b6-b — completion report

**Packet:** the second no-owner decision packet — 15 Query roots moved from `NOT_STARTED` to `BLOCKED_BY_DECISION` with seven recorded OWNER_ADR_REQUIRED decision rows.
**Executed by:** A0 directly, in-branch, on the single candidate lane (the sequential single-writer flow; no agent branches, no PRs).
**Base:** `edb4a725d4` (the b6-a close). Docs-only packet — one commit.

## The decisions

| Decision | Roots closed | The honest gap |
|---|---|---|
| D-CHANNEL-001 | channel, channels | The commercial-channel owner decision the governing matrix ITSELF marks PENDING-ADR — no channel model exists; every channel-scoped argument on the implemented roots already answers its classified error citing this row. |
| D-APPS-001 | app, apps, appsInstallations, appExtension, appExtensions | No extension-registry owner — the ledger's own owning_module field says "extension registry (to be decided)". |
| D-FEDN-001 | _entities, _service | No federation gateway owner — the platform is not an Apollo federation subgraph; the playbook requires explicit federation exceptions. |
| D-SHPZ-001 | shippingZone, shippingZones | No shipping-zone model — the delivery module owns courders/quotes/requests, not zone/postal-range pricing. |
| D-OEVT-001 | homepageEvents | No persisted OrderEvent log to serve a homepage activity feed. |
| D-RPT-001 | ordersTotal, reportProductSales | No reporting owner — a zero-shaped total would be a fabricated metric. |
| D-OTKN-001 | orderByToken | No guest order-token capability — an absent capability is safer than a guessed-token leak surface. |

## Where the Query surface now stands

Every one of the 90 Saleor Query roots now has a live disposition: **37 INTEGRATED** (me/user/staffUsers/customers/permissionGroups/permissionGroup/attribute(s)/productType(s)/category(ies)/product(s)/productVariant(s)/voucher(s)/promotion(s)/order(s)), **50 BLOCKED_BY_DECISION** (57 blocked minus the 7 pre-existing b4-b/b4-d decision rows), and **11 NOT_STARTED — each with a real owning module** (collections over the catalog owner, payment(s)/transaction(s) over the payments/ledger owners, checkout(s)/checkoutLines over the cart owner, exportFile(s) over the jobs owner). Those 11 are the next implementation packets — never decision shortcuts.

## Ledger

22 INTEGRATED / 57 BLOCKED_BY_DECISION / 379 NOT_STARTED (from 22/42/394). No code changed. The roots stay undeclared on the wire — no fake records, no silent no-ops, no success-shaped responses.
