# ADR Proposal — D-ADDR-001: the address read roots' owner (Agent 1, PR #696)

**Decision:** ADOPTED (implementation landed in this packet) — `marketplace-identity` owns the
user-account address book; the `address` read root and the `addressValidationRules` root split
paths (adopted vs. blocked) below.

## 1. The measured data facts (the exploration this decision required)

The store was searched for every address-bearing fact before this decision:

| Table / column family | Module | What it actually holds | User-account address? |
|---|---|---|---|
| `listings` / property details (`rooms`, address columns behind `uq_property_details_listing_id`) | catalog / real-estate | A listing's (property's) physical location | **No** — a listing is not a user account |
| `provider_profiles` (+ V96 `business_hours` etc.) | provider | A provider business profile's service geography | **No** — a provider profile is not a user account |
| booking delivery/installation addresses | booking | An order-scoped event location | **No** — an order line is not a user account |
| `users` | identity | The single identity store's account rows (subject/email/display_name/role) | Holds no address columns |

**Fact: no table in the store carries a USER-account address.** Saleor's `address` /
`addressValidationRules` roots are user-account-scoped (the account's own address book with
default shipping/billing pointers) — the platform had no owner at all.

## 2. The adopted owner

`marketplace-identity` — the single identity store's own module — owns a NEW bounded table
`user_addresses` (V248, BaseEntity columns + Envers mirror + partial unique read indexes), the
`AccountAddress` entity, `AccountAddressService` (the owning use case with the IDOR wall for the
self-service legs and the ADMIN/MANAGE_USERS enforcement for the staff-managed legs), and the
`AddressManagementSpi` seam inside the existing `identity-spi` named interface (additive — the
app layer's existing allowlist entry covers it; no shared-file change).

Rationale against the alternatives:
- **A new module** — forbidden for an agent (root/app POMs are A0-owned) and unjustified for one
  bounded table on the identity store.
- **Console** — console is the platform-administration owner (feature flags, remote config,
  planning, geo settings); it is not the users-domain data owner, and the address rows' FK target
  is `users`.
- **Order/booking-scoped owners** — mixing a user account address with an order/property address
  is exactly the conflation the assignment's priority 1 warned against.

## 3. The roots

| Root | Disposition | Evidence |
|---|---|---|
| `address(id: ID!): Address` | **ADOPTED — implemented** | `AddressDataFetcher.address` over `AddressManagementSpi.find` (ADMIN-gated at the owner), typed global-ID codec, malformed/mistyped-ID VAL-001 legs in `AgentOneMutationDgsTest` |
| `addressValidationRules(...)` | **BLOCKED_BY_DECISION (D-ADDR-001-b)** | No per-country address-format/validation-rules engine exists on this platform (the geo module owns a location hierarchy, not address form metadata); the root stays undeclared on the wire — no fake rules, no silent no-op. Proposed future owner: the geo module, when a form-metadata capability is actually needed |

## 4. The central registry update (for A0's assembly pass — not applied by the agent)

The agent must not edit `saleor-contract-divergences.csv` or the central ledger; the requested
rows:
- `D-ADDR-001`: owner decision = identity (ADOPTED); the pending-ADR flag may now be cleared with
  the implementation evidence above (`V248`, `AccountAddressService`, `AddressManagementSpi`,
  `AgentOneMutationDgsTest`, `AgentOneMutationIntegrationTest`).
- `D-ADDR-001-b` (new row): `addressValidationRules` blocked — no validation-rules engine;
  proposed owner geo; safe behavior: undeclared.
