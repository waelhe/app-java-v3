package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the DGS CodeGen output used by the GraphQL data fetcher is
 * derived from the canonical SDL rather than duplicated string
 * literals (target architecture §11 Phase 3: an SDL rename or removal
 * must break compilation and this assertion, never drift silently).
 *
 * <p>The asserted values are the schema's own contract strings — the
 * same names {@code ServiceDataFetcher} binds through
 * {@code @DgsQuery(field = DgsConstants.QUERY.*)} and
 * {@code @DgsMutation(field = DgsConstants.MUTATION.*)}. The input
 * constant additionally pins the by-name binding contract: the SDL
 * {@code ServiceInput.priceCents} field must equal the record
 * component DGS's {@code DefaultInputObjectMapper} binds by name.</p>
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
