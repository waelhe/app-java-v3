package com.marketplace.app.graphql;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding for the GraphQL surface's operational contract
 * ({@code marketplace.graphql.*}) — the module-local {@code @ConfigurationProperties}
 * record pattern of {@code SearchProperties}/{@code PaymentsProperties}
 * (SYSTEM.md §8: the typed-binding layer), primed with empty
 * {@code @DefaultValue} sections per the constructor-binding house rule
 * (an absent nested section binds to {@code null} otherwise — the Boot
 * reference's own documented binding behavior).
 *
 * <p>Sections:</p>
 * <ul>
 *   <li>{@code errors} — the pre-existing {@code include-trace-id} flag
 *       (same property key the {@code @Value} binding read before; the
 *       typed record is the migration target, not a new contract).</li>
 *   <li>{@code pagination} — the bounded-collection contract of the
 *       GraphQL target design Phase 1: a default page size and a hard
 *       maximum every collection field resolves against (design §6:
 *       "define a bounded default page size and hard maximum").</li>
 *   <li>{@code limits} — query depth and weighted-complexity budgets
 *       enforced by graphql-java's own {@code MaxQueryDepthInstrumentation}
 *       and {@code MaxQueryComplexityInstrumentation} (design §5.7/§6:
 *       resource limits verified under the exact resolved version —
 *       graphql-java 25.0, resolved by the Boot 4.1.1 BOM).</li>
 * </ul>
 *
 * <p>All values are env-tunable without rebuild (the G-R6 calibration
 * model: conservative until measured traffic). Validation is ACTIVE the
 * house way — {@code @Validated} on the record plus {@code @Valid} on
 * every constrained section, the official recipe that makes the JSR-303
 * constraints load-bearing at binding time: «Spring Boot attempts to
 * validate @ConfigurationProperties classes whenever they are annotated
 * with Spring's @Validated annotation … To cascade validation to nested
 * properties the associated field must be annotated with @Valid»
 * (Spring Boot reference — Type-safe Configuration Properties —
 * Validation; the SearchProperties pattern). A non-deployable budget
 * therefore fails STARTUP, never surfaces as an unbounded read or an
 * unenforced limit at runtime.</p>
 */
@Validated
@ConfigurationProperties(prefix = "marketplace.graphql")
public record GraphQlProperties(
        @DefaultValue Errors errors,
        @Valid @DefaultValue Pagination pagination,
        @Valid @DefaultValue Limits limits
) {

    /**
     * The error-extension policy of the GraphQL surface. The trace-id flag
     * is opt-in (default false) so correlation identifiers never leak to
     * external clients by accident — the documented leakage policy
     * (docs/api/error-contract.md).
     */
    public record Errors(
            @DefaultValue("false") boolean includeTraceId
    ) {
    }

    /**
     * The bounded-collection contract: every list/connection field resolves
     * against {@code defaultPageSize} when the client omits {@code first}
     * and clamps its request to {@code maxPageSize} — a hard ceiling that
     * bounds database work, heap and payload regardless of what a client
     * asks for (design §6: "a hard maximum"). The compact constructor keeps
     * the pair internally consistent: a deployment where the default widens
     * past the ceiling would make the default itself the unbounded path,
     * so it fails at binding time instead.
     */
    public record Pagination(
            @Min(1) @DefaultValue("20") int defaultPageSize,
            @Min(1) @DefaultValue("100") int maxPageSize
    ) {
        public Pagination {
            if (defaultPageSize > maxPageSize) {
                throw new IllegalArgumentException(
                        "marketplace.graphql.pagination.default-page-size (" + defaultPageSize
                                + ") must not exceed max-page-size (" + maxPageSize + ")");
            }
        }
    }

    /**
     * Query-cost budgets. Depth counts query-tree levels (the current
     * schema's deepest valid query is 4 — Query.services → edges → node →
     * leaf — so 10 is forward-defense for the nested types of later
     * phases, not a today-reachable bound; the honest today-reachable bound
     * is complexity, which multiplies with aliases). Complexity uses
     * graphql-java's documented default calculator {@code 1 + childComplexity}
     * per field — the full {@code services} connection selection
     * (edges → node → 7 fields + cursor + pageInfo) measures ~14, so 200
     * admits roughly a dozen full parallel selections and rejects the
     * aliased-fan-out abuse shape measured in the limit test.
     */
    public record Limits(
            @Min(1) @Max(100) @DefaultValue("10") int maxQueryDepth,
            @Min(1) @Max(10_000) @DefaultValue("200") int maxQueryComplexity
    ) {
    }
}
