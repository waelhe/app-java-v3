# Debt Register

> Every deviation `[debt: yes - documented]` is recorded here. Reviewed at session entry (§16). Closed when repaid or formally rejected.
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

---

## Rejected (formally accepted as standard)

| ID | Description | Rejected date | Reason |
|----|-------------|---------------|--------|

---

## How to use

1. **Record**: when rule §9E applies, add a row to Open with: `D-{next}` | description | `file:line` | reason | date | severity | owner | repay plan | `open`
2. **Review**: at session entry (§16), scan Open for overdue items → escalate if critical/data
3. **Repay**: when the fix ships, move row to Closed with: how it was fixed
4. **Reject**: when you decide the debt is now the accepted standard, move to Rejected with reason
5. **Never**: leave a debt unrecorded — silent debt = system failure (§2 overarching rule)