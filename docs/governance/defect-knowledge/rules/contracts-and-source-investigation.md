# Contracts, nullability and source investigation

**Priority:** high; critical where wire shape changes affect money, identity, authorization or compatibility.  
**Triggers:** GraphQL/REST/OpenAPI, SDL, generated clients, DTOs, pagination, error mapping, nullable fields, enums, search/filtering and external API behavior.

**Canonical rules:** B4, C1, D5, D7. **Patterns:** P3, P6, P12. **Evidence:** E-11, E-19, E-31, E-32, E-43…E-46.

## Evidence ladder for an API question

1. Inspect the exact pinned schema/OpenAPI and generated types for the target version.
2. Read official documentation for that exact version and operation/permission.
3. If the documentation specifies shape but not behavior, inspect official implementation source at the same pinned tag/commit.
4. Inspect this repository's resolver/controller, adapter, mapper, callers and tests.
5. State the difference between contract guarantee, upstream implementation behavior, local implementation behavior and proposed design.

A schema tells you which fields and types exist. It does not by itself prove authorization, filtering, side effects, event conditions, transaction behavior or runtime nullability at every call path.

## Invariants to prove

- Handle null intentionally. If null means not configured/absent yet, model it as such; if it violates an invariant, fail clearly. Do not turn every null into a convenient empty/default value.
- Preserve canonical error shape, machine-readable code, trace/correlation context, field-level details and locale consistently across GraphQL and REST.
- Treat money as integer minor units or the domain's explicit precision model; do not pass through floating point without a reviewed contract.
- When adding enum values, search every exhaustive switch, sum, filter, serializer, query and export. Read models must understand the new value.
- Pagination must preserve ordering and continuation semantics. Do not silently truncate or discard requested sort/filter arguments.
- A success response from an API surface does not prove the downstream operation completed; inspect the linked domain state and event/transaction lifecycle.
- A derived display or attribution not present in the source contract must be labelled as reconstruction, not represented as provider/API truth.

## Review questions

- What is exact wire shape at the pinned version?
- Which fields are nullable and why?
- What is the behavior for missing/invalid enum, type mismatch, unknown field, no result, and partial result?
- Are GraphQL and REST errors equivalent where they are intended to be?
- Does the generated client reflect the actual schema from this commit?
- Do resolver/handler permission gates and filters align with the documented contract?
- Are we inventing decomposition, order, attribution or availability not actually returned by the API?

## Required proof

Use contract/schema compatibility gates plus resolver/consumer tests. Include null, missing, malformed, empty, boundary, locale and unknown-enum cases. For numeric/money contracts, test currency precision and rounding boundaries. Test every consumer of a changed schema/enum, not only the producer.

## Saleor reference — what transfers, what does not

The Saleor api-investigation and api-data-model rules demonstrate a useful sequence: generated types/schema → official docs → upstream source for behavior → local consumers. Transfer that investigation method and deliberate null handling. Do not transfer Saleor's particular field types, token filtering or per-channel price semantics into app-java-v3 unless the pinned canonical contract requires them. See [saleor-ai-adoption-map.md](../saleor-ai-adoption-map.md).

## Anti-patterns

- Guessing behavior from a field name or schema type alone.
- Using the live upstream main schema to interpret a pinned historical contract.
- Treating absent data as zero without a domain distinction.
- Changing an error response in one API surface while leaving its sibling surface inconsistent.
- Calling a reconstructed value exact API data when the contract does not expose it.
