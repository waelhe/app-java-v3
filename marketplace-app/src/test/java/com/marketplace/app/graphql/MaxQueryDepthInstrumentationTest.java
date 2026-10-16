package com.marketplace.app.graphql;

import java.util.Map;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The depth-budget proof at the instrumentation layer. The honest scope
 * note (recorded in GraphQlProperties.Limits): the PRODUCTION schema is
 * flat — its deepest valid query is 4 levels (Query.services → edges →
 * node → leaf), so no valid production query can reach the depth budget
 * today; the complexity budget (the aliased fan-out, pinned end-to-end in
 * {@code GraphQlLimitsIntegrationTest}) is the today-reachable bound. The
 * depth instrumentation is forward-defense for the nested types of later
 * phases — and THIS test proves the registered instrumentation actually
 * aborts a deep query, on an in-memory schema built to be deep, executing
 * through the exact {@code MaxQueryDepthInstrumentation} graphql-java 25.0
 * ships (the same class {@link GraphQlConfig} registers as a bean).
 */
class MaxQueryDepthInstrumentationTest {

    private static final String DEEP_SCHEMA = """
            type Query { l0: Node }
            type Node { id: ID!, next: Node }
            """;

    private GraphQL graphQl(int maxDepth) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(DEEP_SCHEMA);
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", type -> type.dataFetcher("l0", env -> Map.of("id", "root")))
                .type("Node", type -> type.dataFetcher("next", env -> Map.of("id", "n")))
                .build();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, wiring);
        return GraphQL.newGraphQL(schema)
                .instrumentation(new graphql.analysis.MaxQueryDepthInstrumentation(maxDepth))
                .build();
    }

    /**
     * {@code levels} nested {@code next} selections under the root field —
     * a query-tree depth strictly growing with {@code levels}.
     */
    private static String nested(int levels) {
        StringBuilder sb = new StringBuilder("{ l0 { id");
        sb.append(" next { id".repeat(levels));
        sb.append(" }".repeat(levels));
        sb.append(" } }");
        return sb.toString();
    }

    /** A query within the depth budget executes normally — data, no
     * errors. */
    @Test
    void aQueryWithinTheBudgetExecutes() {
        var result = graphQl(4).execute(nested(1));
        assertThat(result.getErrors()).isEmpty();
        assertThat((Object) result.getData()).isNotNull();
    }

    /**
     * A query deeper than the budget is aborted with the framework's own
     * envelope: the measured "maximum query depth exceeded" message, the
     * ExecutionAborted classification (on the wire via toSpecification),
     * and no partial data — the abort happens during validation, before
     * execution.
     */
    @Test
    void aQueryBeyondTheBudgetIsAbortedWithTheFrameworkEnvelope() {
        var result = graphQl(4).execute(nested(6));
        assertThat(result.getErrors()).hasSize(1);
        var error = result.getErrors().getFirst();
        // The framework's own abort envelope: the measured message, the
        // ExecutionAborted classification (on the WIRE via toSpecification's
        // documented classification move), and no partial data.
        assertThat(error.getMessage()).startsWith("maximum query depth exceeded").contains("> 4");
        assertThat(error.getErrorType()).isEqualTo(graphql.ErrorType.ExecutionAborted);
        @SuppressWarnings("unchecked")
        Map<String, Object> wireExtensions = (Map<String, Object>) error.toSpecification()
                .getOrDefault("extensions", Map.of());
        assertThat(wireExtensions).containsEntry("classification", "ExecutionAborted");
        // Aborted before execution: no partial data.
        assertThat((Object) result.getData()).isNull();
    }
}
