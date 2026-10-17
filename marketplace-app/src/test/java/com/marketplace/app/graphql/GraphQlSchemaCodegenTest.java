package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.Service;
import com.marketplace.app.graphql.generated.types.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the DGS CodeGen output used by the GraphQL surface is
 * derived from the canonical SDL rather than duplicated string
 * literals or hand-written twins (target architecture §11 Phase 3: an
 * SDL rename, removal or scalar change must break compilation and
 * these assertions, never drift silently).
 *
 * <p>Two contracts are pinned. The field-name constants the data
 * fetcher binds through {@code @DgsQuery(field = DgsConstants.*)} and
 * {@code @DgsMutation(field = DgsConstants.*)} — the asserted values
 * are the schema's own contract strings, and the input constant
 * additionally pins the by-name binding contract: the SDL
 * {@code ServiceInput.priceCents} field must equal the record
 * component DGS's {@code DefaultInputObjectMapper} binds by name.</p>
 *
 * <p>And the generated transport types — the adapter boundary's return
 * contract since the Phase 3.3/3.4 increment — whose field set and
 * scalar mapping must mirror the SDL exactly: the engine renders
 * {@code ID} as String and {@code Float} as double (measured from the
 * graphql-dgs-codegen 8.6.0 TypeUtils), so a schema field rename or
 * removal disappears from the generated class and fails HERE (and at
 * the fetcher's compilation site) instead of drifting. The hand-owned
 * input record is exempt by design: its Jakarta Validation contract
 * cannot be proven equivalent from SDL, so the build's typeMapping
 * keeps the generated tree free of a duplicate input type.</p>
 */
class GraphQlSchemaCodegenTest {

    /**
     * The constants half of the Phase 3 contract: the generated
     * field-name constants must equal the schema's own strings — the
     * values the data fetcher binds through the {@code field}
     * attribute.
     */
    @Test
    void generatedConstantsMatchTheGraphQlSchemaContract() {
        assertThat(DgsConstants.QUERY.Service).isEqualTo("service");
        assertThat(DgsConstants.QUERY.Services).isEqualTo("services");
        assertThat(DgsConstants.MUTATION.CreateService).isEqualTo("createService");
        assertThat(DgsConstants.SERVICEINPUT.PriceCents).isEqualTo("priceCents");
    }

    /**
     * The transport-side half of the Phase 3 contract: the generated
     * {@code Service} data type and {@code ServiceStatus} enum ARE the
     * wire contract the adapter returns. Exactly the SDL's seven fields
     * — no more, no less: a stale generated file, an SDL addition or a
     * removal shows up here — with the engine's documented scalar
     * mapping, and exactly the SDL's two enum values.
     */
    @Test
    void generatedTransportTypesMatchTheGraphQlSchemaContract() {
        assertThat(Arrays.stream(Service.class.getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("id", "name", "description", "category", "price", "currency", "status");

        assertThat(fieldType(Service.class, "id")).isEqualTo(String.class);
        assertThat(fieldType(Service.class, "name")).isEqualTo(String.class);
        assertThat(fieldType(Service.class, "description")).isEqualTo(String.class);
        assertThat(fieldType(Service.class, "category")).isEqualTo(String.class);
        assertThat(fieldType(Service.class, "price")).isEqualTo(double.class);
        assertThat(fieldType(Service.class, "currency")).isEqualTo(String.class);
        assertThat(fieldType(Service.class, "status")).isEqualTo(ServiceStatus.class);

        assertThat(ServiceStatus.values()).extracting(Enum::name)
                .containsExactly("ACTIVE", "INACTIVE");
    }


    /**
     * The Phase-6 constants half: the home journey's two root fields bind
     * through the generated constants exactly as the schema names them —
     * an SDL rename fails HERE (and at the fetcher's compilation site)
     * instead of drifting.
     */
    @Test
    void generatedConstantsMatchTheHomeJourneySchemaContract() {
        assertThat(DgsConstants.QUERY.Discovery).isEqualTo("discovery");
        assertThat(DgsConstants.QUERY.NeighborhoodFeed).isEqualTo("neighborhoodFeed");
    }

    /**
     * The Phase-6 transport-side half: the home journey's generated data
     * types ARE the wire contract the adapter returns — exactly the SDL's
     * field sets with the engine's documented scalar mapping (ID renders
     * as String, the enums as the generated enums), and exactly the
     * mirrored domain vocabularies as literal value sets (the SDL is the
     * typed boundary; the mappers convert strictly by name so a
     * vocabulary drift fails loudly, never renders as a lie).
     */
    @Test
    void generatedTransportTypesMatchTheHomeJourneySchemaContract() {
        assertThat(Arrays.stream(com.marketplace.app.graphql.generated.types.DiscoveryRow.class
                        .getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("row", "cards", "totalEligible");

        assertThat(Arrays.stream(com.marketplace.app.graphql.generated.types.DiscoveryCard.class
                        .getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("sourceType", "sourceId", "updatedAt", "title",
                        "snippet", "state", "scopeLocationId", "reason", "paid");

        assertThat(Arrays.stream(com.marketplace.app.graphql.generated.types.NeighborhoodPost.class
                        .getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("id", "authorId", "locationId", "category", "title",
                        "body", "status", "declaredCommercial", "lostFoundState", "reactionsCount",
                        "reactedByMe", "media", "createdAt", "updatedAt");

        assertThat(Arrays.stream(com.marketplace.app.graphql.generated.types.NeighborhoodPostMedia.class
                        .getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("mediaId", "url", "thumbUrl", "contentType", "position");

        // The SDL's ID fields render as String (the engine's documented
        // scalar mapping, the Service precedent).
        assertThat(fieldType(com.marketplace.app.graphql.generated.types.DiscoveryCard.class, "sourceId"))
                .isEqualTo(String.class);
        assertThat(fieldType(com.marketplace.app.graphql.generated.types.DiscoveryCard.class, "updatedAt"))
                .isEqualTo(String.class);
        assertThat(fieldType(com.marketplace.app.graphql.generated.types.NeighborhoodPost.class, "id"))
                .isEqualTo(String.class);
        assertThat(fieldType(com.marketplace.app.graphql.generated.types.NeighborhoodPost.class, "reactionsCount"))
                .isEqualTo(int.class);

        // The mirrored vocabularies, as literal value sets (the same-journey
        // closed sets the domain enums own).
        assertThat(com.marketplace.app.graphql.generated.types.DiscoveryRail.values())
                .extracting(Enum::name)
                .containsExactly("URGENT_ALERTS", "FOLLOWED_SOURCES", "LOST_FOUND",
                        "NEIGHBORHOOD_RECOMMENDATIONS", "EVENTS_AND_OPPORTUNITIES", "FOR_YOU");
        assertThat(com.marketplace.app.graphql.generated.types.DiscoverySource.values())
                .extracting(Enum::name)
                .containsExactly("NEIGHBORHOOD_POST", "NEIGHBORHOOD_EVENT", "JOB",
                        "URGENT_ALERT", "PROVIDER_LISTING");
        assertThat(com.marketplace.app.graphql.generated.types.PostCategory.values())
                .extracting(Enum::name)
                .containsExactly("GENERAL", "CLASSIFIED", "LOST_FOUND",
                        "RECOMMENDATION", "QUESTION", "REQUEST");
        assertThat(com.marketplace.app.graphql.generated.types.PostStatus.values())
                .extracting(Enum::name)
                .containsExactly("VISIBLE", "HIDDEN_BY_MODERATOR");
        assertThat(com.marketplace.app.graphql.generated.types.LostFoundState.values())
                .extracting(Enum::name)
                .containsExactly("ACTIVE", "RESOLVED", "FOUND");
    }

    /**
     * Resolves a declared field's Java type, failing with a named
     * assertion when the generated class lacks the field at all.
     */
    private static Class<?> fieldType(Class<?> type, String field) {
        try {
            return type.getDeclaredField(field).getType();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("missing generated field " + type.getSimpleName() + "." + field, e);
        }
    }
}
