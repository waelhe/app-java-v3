# ADR-0005: the console planning record — the versioned planning vocabulary, the closed payload gate, and the append-only trail (Plan D-11)

- **Status:** Accepted (owner-ordered execution of the unified plan, stage 9)
- **Decision owners:** Owner + console execution (the plan's D-11 row: «مخطط سجل مكونات معتمدة — لا كود مخزن»)
- **Governing references (official documentation exclusively):**
  - Spring Modulith reference — Events (`CacheInvalidationRequested` rides AFTER_COMMIT: a rolled-back publish never evicts)
  - Spring Data JPA / Hibernate — the `@JdbcType(JsonJdbcType.class)` JSONB mapping (the official type descriptor)
  - Jackson — tree-model validation (the closed shape: unknown fields refused)
  - The measured modules — `PlatformRelease*` (V117, the D-12 release contract) already stand: this ADR adds ONLY the planning record; the release surface is reused, not rebuilt

## Decisions

1. **D-11 (the planning vocabulary):** the supported components are the **§1.4 discovery rows** (`URGENT`, `FOLLOWED_SOURCES`, `LOST_FOUND`, `RECOMMENDATIONS`, `EVENTS`, `FOR_YOU`) — the surfaces the client's renderer actually knows. A section code outside the set is the plan's «مكون مجهول» and is refused at the gate, never stored.
2. **The payload shape is closed by construction:** `sections[]` with `code/visible/position/title/categoryRefs` — an unknown field is refused (no executable code, no free HTML, no query fragments can ride in), titles are capped, the visibility/position are typed. Category references are validated against the catalog's own dictionary through the `CategoryVocabularyPort` seam BEFORE any write.
3. **The trail is append-only:** publish validates then appends the next revision; the current planning IS the highest revision; the rollback REPUBLISHES the target payload as a new revision (the trail never rewrites, every change keeps its author and timestamp — the plan's «طرح/تراجع آمن» + «آخر تخطيط صالح قابل للاستعادة»).
4. **The cache contract:** every publish/rollback publishes `CacheInvalidationRequested("consolePlanning")` — the plan's «تعديل admin مرئيًا عند حد التشغيل المتوقع دون إعادة تشغيل» honored through the EXISTING invalidation event, no new cache machinery.
5. **The authority gate:** the writes ride the `@PreAuthorize("hasRole('ADMIN')")` class gate (the `ConsoleAdminController` shape); the public read exposes the validated data only.
6. **Boot vs runtime (the plan's §8.2 separation):** the planning record is RUNTIME data (read per request through the cache) — nothing here touches Spring Boot properties; the boot-side settings stay the properties' own domain.

## Consequences

- The client renders known components with validated data — the API can never ask it to execute or display arbitrary content.
- Every sensitive change is attributed (the revision's author), versioned (revision_no), audited (Envers + the V24 mirror), and reversible (the republish).
- The release surface (D-12's contract) stays exactly as measured — no second release vocabulary.
