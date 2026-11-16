# ADR-0006: the ordering baseline's evaluation contract — the labeled set and the deterministic evidence (Plan D-13)

- **Status:** Accepted (owner-ordered execution of the unified plan, stage 10)
- **Decision owners:** Owner + data/execution (the plan's D-13 row: «قواعدي أولًا؛ ML بعد أبواب البيانات»)
- **Governing references (official documentation exclusively):**
  - The information-retrieval metrics' own definitions — NDCG (the logarithmic position discount over graded relevance) and MRR (the reciprocal of the first relevant hit), computed over the deterministic ranking's own order
  - The measured modules — the search pipeline's canonical deterministic ordering through the `MarketplaceSearchPort` shared contract (the same behavior REST and AI callers see; no second ranking is built) and the catalog's `ListingRankingFormula` (the signals' own home)

## Decisions

1. **D-13 stays behind its own data gate:** no learned model, no LTR, no model call anywhere in this stage — level 0 of the plan's AI ladder, code only. What ships is the gate's own instrument: the LOCAL labeled eval set (`ordering_eval_cases`, the graded 0–3 relevance, the tags as free text — never a mega-enum) and the runner that scores the EXISTING deterministic ranking against it (NDCG@10, MRR) through the shared search port.
2. **The baseline's evidence is measured, not claimed:** the eval report carries the ranked ids the run observed plus the metrics — a learned model enters ONLY after it beats these numbers on the SAME labeled set (the plan's «المرشح يتفوق على خط الأساس الحتمي باختبار معتمد دون إضرار مقاييس الحماية»).
3. **The asset is audited:** the eval set is a measured asset (Envers + the V24 mirror) — its changes leave revisions, so the gate's evidence stays reproducible.
4. **The surface is internal:** the ADMIN-gated read (the instrument is not a public contract); the clients never see eval numbers.

## Consequences

- The deterministic ordering's documented signals stay in the search pipeline's own formula (the existing home); this ADR adds the measurement, not a second ranking.
- The eval set starts EMPTY and is authored locally — the plan's «مجموعة تقييم محلية موسومة (صلة، حداثة، نطاق، تنوع، توازن مصادر، إخفاء/غير مهتم، سلامة)»: the tags column carries those axes per case.
- The learned-ordering decision (D-13's second half) remains the owner's, gated on the measured comparison — this ADR builds the scale, not the verdict.
