# HANDOFF → A0 — D-ADDR-001: the explicit address-family decision (PR #696, Agent 1)

**Status: DECISION DELIVERED — A0 is asked to apply it to the central
registries (the agent may not edit them). The governance gate is
currently BLOCKED on this on every campaign branch:
`ERROR: unapproved divergence Address read roots
(address/addressValidationRules) — owner decision D-ADDR-001 must
identify its pending ADR`
(`.ci/check-saleor-canonical-plan.py` line 163: an `OWNER_ADR_REQUIRED`
row whose `decision_record` does not start with `PENDING` fails the
gate).**

## 1) The explicit decision (two rows, not one)

The single legacy divergence row conflates two unrelated capabilities.
The decision splits them:

**(a) The account address book — SUPPORTED, implemented in this PR.**
The user-account address family (`address(id)`, the
`accountAddress*` mutation family, `Account.addresses`): the platform
NOW owns the store — the V248 `user_addresses` table (BaseEntity +
Envers + the two one-default-per-type partial unique indexes), the
`AccountAddressService` use case (self-service IDOR wall +
ADMIN/MANAGE_USERS staff legs), the `AddressManagementSpi` seam, the
`AddressDataFetcher` adapter, and the mutation/query/IDOR test legs
(`AgentOneMutationDgsTest` 21/21 locally; the Testcontainers legs in
`AgentOneMutationIntegrationTest` are CI-pending). The full measured
basis and the alternatives analysis live in
`docs/agent-handoffs/adr-addr-001.md` (this PR).

**(b) `addressValidationRules` — UNSUPPORTED, stays blocked.** A
per-country address-FORMAT/validation-rules engine (Saleor's
shop-configurable rules: which fields are required, which city areas
are valid, regexes per country) does not exist on this platform — the
geo module owns a location hierarchy, not address form metadata. The
root stays undeclared on the wire: no fake rules, no silent no-op, no
invented configuration surface. Proposed future owner IF a real need
appears: the geo module, behind its own ADR.

## 2) The proposed central registry updates (A0's assembly pass)

### `docs/governance/plans/saleor-contract-divergences.csv`

REPLACE the single failing row

```csv
Address read roots (address/addressValidationRules) — owner decision D-ADDR-001,ADAPT,...,OWNER_ADR_REQUIRED,D-ADDR-001,...,P1 completion gate and the P7 release gate
```

WITH two rows (gate-format compliant — the `OWNER_ADR_REQUIRED` row's
`decision_record` starts with `PENDING`):

```csv
Account address book roots (address / accountAddress* / Account.addresses),ADAPT,"Preserve Saleor's address field shapes for the account's own address book operations","The identity owner's V248 user_addresses store + AccountAddressService (self-service IDOR wall; ADMIN/MANAGE_USERS staff legs) + AddressManagementSpi; one-default-per-type backstopped by the partial unique indexes",identity; marketplace-app GraphQL adapter,Dashboard customer address book; checkout address pickers,address CRUD round trip; default-shipping/billing swap; IDOR denials; soft-delete FK behavior; malformed global-ID rejection,IMPLEMENTATION_REQUIRED,D-SALEOR-FULL-001,The identity store owns the user-account address book (V248); the wire contract is Saleor's address shapes,P1 identity routes and the P7 gate
AddressValidationRules read root (addressValidationRules),ADAPT,"Saleor's per-country address format validation rules surface","No validation-rules engine or form-metadata capability exists on this platform; the root stays undeclared on the wire (no fake rules, no silent no-op). Proposed future owner: the geo module, behind its own ADR when a real need appears",geo (proposed future owner); marketplace-app GraphQL adapter (would-be adapter),Dashboard shop address-form configuration,blocked root never answers fake data; the undeclared-on-the-wire behavior IS the contract,OWNER_ADR_REQUIRED,PENDING-ADR (D-ADDR-001-b, blocked — see docs/agent-handoffs/adr-addr-001.md §3),No per-country address-format rules engine exists; the frozen contracts forbid fabricated configuration surfaces,Before any checkout address-form automation lands
```

### `docs/governance/campaign/saleor-458-root-inventory.csv`

The address roots' statuses flip with the PR's merge (A0's merge-time
assembly, not this handoff): `address` → the implemented/INTEGRATED
disposition per the merge flow; `addressValidationRules` → the
BLOCKED_BY_DECISION disposition with the D-ADDR-001-b reference.

### The gate, after the split

- The account-address row carries `IMPLEMENTATION_REQUIRED` +
  `D-SALEOR-FULL-001` — the standing approved wire-compatibility
  decision record every implemented ADAPT row uses; the gate's
  `OWNER_ADR_REQUIRED`-must-be-`PENDING` rule never fires on it.
- The `addressValidationRules` row carries `OWNER_ADR_REQUIRED` +
  `PENDING-ADR (D-ADDR-001-b, ...)` — the gate's format rule is
  satisfied (the record starts with `PENDING`), and the blocked root's
  honest reason travels in the same row.

## 3) The evidence A0's decision rides on

- `docs/agent-handoffs/adr-addr-001.md` — the full measured data facts
  (no pre-V248 table carried a user-account address; the listing /
  provider-profile / booking address families are NOT user-account
  addresses), the owner rationale (identity, not a new module / not
  console / not order-scoped), and the roots table.
- The implementation itself: `V248__user_account_addresses.sql`,
  `AccountAddress(.Repository)`, `AccountAddressService`,
  `AddressManagementSpi`, `AddressDataFetcher`, and the test legs
  (`AccountAddressServiceTest` 11/11 locally; DGS + integration legs
  per the completion report's matrix).
- The blocked root's honesty: `addressValidationRules` appears NOWHERE
  in the agent-1 SDL fragment — undeclared on the wire is the
  documented contract for blocked roots.

## 4) What this handoff explicitly does NOT ask for

- No central SDL change (nothing declares `addressValidationRules`).
- No `application.yml` change, no CI change, no new module.
- No invention of a validation-rules capability to "complete the
  458" — the root's honest disposition is BLOCKED, exactly like the
  campaign's other documented no-owner rows.
