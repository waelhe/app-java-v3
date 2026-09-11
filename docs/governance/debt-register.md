# Debt Register

> Every deviation `[debt: yes - documented]` is recorded here. Reviewed at session entry (Governance Codex §7). Closed when repaid or formally rejected.
> Severity: **critical** (security/data risk, must repay current PR) | **data** (consistency, repay ≤1 week) | **arch** (architectural, repay ≤1 month or reject) | **style** (cosmetic, repay at next touch or reject)

---

## Open

| ID | Description | File:Line | Reason | Date | Severity | Owner | Repay plan | Status |
|----|-------------|-----------|--------|------|----------|-------|------------|--------|
| D-009 | Legacy webhook HMAC binds only `eventId + eventType`; `provider`, `paymentIntentId`, `externalId` unsigned — authenticated replay/field-tamper across providers | `PaymentsService.processWebhookEvent`: `validateSignature(eventId + eventType, signature)` | **Latent gate measured:** the secret binding `marketplace.payments.webhook.shared-secret` is NOT present in `PaymentsProperties` nor application.yml — defaults empty, so `PaymentWebhookSecurity` rejects ALL legacy webhook calls today ("not configured"). The gap opens only when a deployer binds the secret explicitly. A captured valid signature would then let an attacker rotate `provider` (dedup key `(provider, event_id)` — provider unbounded by MAC) and replay `confirmIntent` against any `paymentIntentId` (`getIntent` lacks ownership check on this internal path) | 2026-09-11 | **critical** (security: payment-state tamper, latent — dormant until secret bound) | w-co | Extend signed payload to `provider+eventId+eventType+paymentIntentId+externalId` (+ timestamp window for replay protection) inside `PaymentWebhookSecurity`; add rejection tests | open |
| D-010 | Flyway `V35` silent gap — sequence V34→V36; records say "V1..V35 clean" (PROJECT_MAP + SYSTEM.md §214) but no `V35__*.sql` exists in any commit | `db/migration/` | Number evidently allocated to a B2 draft dropped before PR #232; never applied (no prod impact) | 2026-09-11 | data (documentation reference misalignment) | w-co | Correct the two historical references to V34 — **user decision** | open |

> _No open debts yet. New entries are added at the bottom of this table._

---

## Closed

| ID | Description | Closed date | How |
|----|-------------|-------------|-----|

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