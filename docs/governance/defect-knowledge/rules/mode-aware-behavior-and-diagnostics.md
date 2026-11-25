# Mode-aware behavior and diagnostics

**Priority:** high when a mode changes customer-visible, financial, security or fulfillment semantics.  
**Triggers:** feature flags, per-tenant settings, legacy/current modes, inventory availability, shipping/purchasability, channel/region differences, diagnostic tools and UI copy.

**Canonical rules:** B4, B6, C1/C3. **Patterns:** P3, P12. **Evidence:** E-02, E-19, E-47, E-48.

## Invariants to prove

1. Enumerate every supported mode and its default for new versus existing installations. Do not assume a migration changed existing rows to the new mode.
2. Separate concepts that happen to be coupled in one mode. For example, listed, purchasable, bookable, shippable, available and visible are different predicates unless the active mode explicitly combines them.
3. Dispatch mode-specific behavior at a clear boundary, not in scattered deep model methods.
4. Make the current mode visible to operators/admin diagnostics when it changes interpretation.
5. Classify diagnostic findings by the concern they actually block. A shipping defect must not be reported as a purchase-visibility defect in a mode where shipping and purchase are orthogonal.
6. Test each mode explicitly. Do not rely on DB defaults, test fixture creation dates or undocumented environment inheritance.
7. Event subscriptions and API acceptance are not proof of event emission in every mode. Test actual trigger conditions.

## Review questions

- What is the flag scope: global, tenant, shop, channel, resource or request?
- What is defaulted for new records and what is preserved for existing records?
- Which public query/read-model/diagnostic path changes across modes?
- Which event triggers are mode-conditional?
- Does UI wording overstate what the mode proves?
- What migration/cache invalidation/reconciliation follows a mode flip?

## Saleor reference — strict boundary

Saleor 3.23 introduced a specific stock availability mode and mode-dependent webhook behavior. The transferable lesson is to map both modes, separate purchasability from shippability where the business model supports that distinction, place dispatch at the boundary, and test both modes. **Do not import Saleor's flag, warehouse rules, shipping-zone semantics or event names into this platform.** Verify the app-java-v3 domain contract independently.

## Anti-patterns

- Testing only the current default mode.
- Assuming upgrades and new installations share the same default.
- Labeling a product/listing/bookable slot as available without defining the predicate.
- Hiding a mode precondition in a background event path without an operator-visible diagnostic.
