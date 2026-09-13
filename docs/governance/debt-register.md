# Debt Register

> Every deviation `[debt: yes - documented]` is recorded here. Reviewed at session entry (Governance Codex §7). Closed when repaid or formally rejected.
> Severity: **critical** (security/data risk, must repay current PR) | **data** (consistency, repay ≤1 week) | **arch** (architectural, repay ≤1 month or reject) | **style** (cosmetic, repay at next touch or reject)

---

## Open

| ID | Description | File:Line | Reason | Date | Severity | Owner | Repay plan | Status |
|----|-------------|-----------|--------|------|----------|-------|------------|--------|

> _No open debts yet. New entries are added at the bottom of this table._

---

## Closed

| ID | Description | Closed date | How |
|----|-------------|-------------|-----|
| D-009 | Legacy webhook HMAC binds only `eventId + eventType`; `provider`, `paymentIntentId`, `externalId` unsigned — authenticated replay/field-tamper across providers (latent: secret unbound, gate rejects all) | 2026-09-13 | **Repaid by the user's command «عالج الديون، نفذ» — PR #304 → main `e000260`** (squash, CI 6/6 on head `4fd9e93`, CodeRabbit j1: 3/3 notes adopted from the root + threads confirmed). `PaymentWebhookSecurity` rebuilt on the official Stripe scheme measured from the stripe-java 33.4.1 artifact bytecode: header `t=<epoch>,v1=<base64-mac>` (multiple v1 = rotation), signed payload `<timestamp>.<length-prefixed injective five-field envelope with explicit presence markers (CWE-345 adoption)>`, constant-time compare before the window, past-only rejection `timestamp < now-300s`. Retired format rejected; 21 real-MAC guards; Clock from the house `ClockConfig`. Channel latent by design — no live caller broke. |
| D-010 | Flyway `V35` silent gap — sequence V34→V36; records say "V1..V35 clean" but no `V35__*.sql` exists in any commit | 2026-09-13 | **Repaid by the same command/PR #304**: the two historical references corrected to V1..V34 (the measured count: 34 versioned) + SYSTEM.md §7 now pins V35 as a permanently retired number (allocated to a dropped B2 draft; never in any commit; never applied; reuse would be out-of-order vs the applied history and require the disabled `outOfOrder=true`). |

---

## Rejected (formally accepted as standard)

| ID | Description | Rejected date | Reason |
|----|-------------|---------------|--------|

---

## How to use

1. **Record**: when rule §9E (governance codex) applies, add a row to Open with: `D-{next}` | description | `file:line` | reason | date | severity | owner | repay plan | `open`
2. **Review**: at session entry (Governance Codex §7), scan Open for overdue items → escalate if critical/data
3. **Repay**: when the fix ships, move row to Closed with: how it was fixed
4. **Reject**: when you decide the debt is now the accepted standard, move to Rejected with reason
5. **Never**: leave a debt unrecorded — silent debt = system failure (Constitution §2.6)