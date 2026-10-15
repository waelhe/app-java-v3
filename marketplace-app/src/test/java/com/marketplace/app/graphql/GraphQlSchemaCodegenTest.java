package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the DGS CodeGen output used by the GraphQL controller is derived
 * from the canonical SDL rather than duplicated string literals.
 */
class GraphQlSchemaCodegenTest {

    @Test
    void generatedConstantsMatchTheGraphQlSchemaContract() {
        assertThat(DgsConstants.QUERY.Service).isEqualTo("service");
        assertThat(DgsConstants.QUERY.Services).isEqualTo("services");
        assertThat(DgsConstants.MUTATION.CreateService).isEqualTo("createService");
        assertThat(DgsConstants.SERVICEINPUT.PriceCents).isEqualTo("priceCents");
    }
}
