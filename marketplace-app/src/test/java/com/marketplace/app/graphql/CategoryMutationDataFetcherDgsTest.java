package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryMutationError;
import com.marketplace.catalog.spi.CategoryMutationErrorCode;
import com.marketplace.catalog.spi.CategoryMutationResult;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryView;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.shared.security.CurrentUserProvider;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code categoryCreate},
 * {@code categoryUpdate}, {@code categoryDelete} and
 * {@code categoryBulkDelete} mutation roots, the agent-2 packet): the
 * GraphQL adapter's DGS proof — REAL mutation documents against the REAL
 * SDL (the agent-2 SDL fragment's own declared roots) and the REAL DGS
 * wiring with no web layer and no containers. The owning write use case
 * is mocked at the declared {@code CategoryWriteSpi} seam; the
 * full-stack proof (the real registry writes over the Flyway-migrated
 * schema, the real {@code @PreAuthorize} ADMIN gate and the real
 * soft-delete SQL) lives in {@code CategoryGraphQlIntegrationTest}'s
 * mutation legs.
 *
 * <p>What this proves: the SDL's own mutation roots load and execute
 * through the real argument binding (the generated {@code CategoryInput}
 * binding by name); the payload shapes serialize exactly as Saleor
 * declares them ({@code errors} with field/message/code plus the
 * category projection carrying the typed global ID); the domain error
 * entries map onto the Saleor {@code ProductError} vocabulary; the
 * transport-level negatives (a malformed or mistyped global ID, a
 * keyless call, the declared-but-unowned {@code parent} argument) answer
 * the classified VAL-001 GraphQL error family the read roots
 * established — never a silent no-op; and the bulk delete's count rides
 * the owner's own honest number.</p>
 */
@SpringBootTest(classes = CategoryMutationDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class CategoryMutationDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the UserDataFetcherDgsTest/AttributeDataFetcherDgsTest precedent.
    @MockitoBean
    private com.marketplace.catalog.spi.CatalogSpi catalogSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductAdminSpi productAdminSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeReadSpi productTypeReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CategoryReadSpi categoryReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CategoryWriteSpi categoryWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductReadSpi productReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantReadSpi variantReadSpi;

    @MockitoBean
    private com.marketplace.discovery.spi.DiscoverySpi discoverySpi;

    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodFeedPort neighborhoodFeedPort;

    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodPostDetailPort postDetailPort;

    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    @MockitoBean
    private com.marketplace.identity.spi.IdentitySpi identitySpi;

    @MockitoBean
    private com.marketplace.orders.spi.OrderReadSpi orderReadSpi;

    @MockitoBean
    private com.marketplace.pricing.spi.DiscountReadSpi discountReadSpi;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("roles", List.of(roles))
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt, List.of(java.util.Arrays.stream(roles).map(r -> "ROLE_" + r)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .<org.springframework.security.core.GrantedAuthority>toList()
                        .toArray(new org.springframework.security.core.GrantedAuthority[0])),
                subject);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static CategoryView categoryView(UUID id, String name, String slug) {
        return new CategoryView(id, name, null, slug, 1);
    }

    /** The typed global ID the codec emits — base64("Category:<uuid>"). */
    private static String categoryGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Category:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The happy create through the real wiring: the input binds by name,
     * the command reaches the owner's seam, and the payload serializes
     * the empty error list with the created projection's typed global ID.
     */
    @Test
    void categoryCreateExecutesTheOwnerAndSerializesTheSaleorPayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID createdId = UUID.randomUUID();
        when(categoryWriteSpi.createCategory(any())).thenReturn(
                CategoryMutationResult.success(categoryView(createdId, "Fresh", "fresh")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryCreate(input: {name: "Fresh", slug: "fresh"}) {
                    errors { field message code }
                    category { id name slug nameAr position }
                } }
                """, "data.categoryCreate");

        verify(categoryWriteSpi).createCategory(argThat(command ->
                "Fresh".equals(command.name()) && "fresh".equals(command.slug())));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> category = (Map<String, Object>) payload.get("category");
        assertThat(category.get("id")).isEqualTo(categoryGlobalId(createdId));
        assertThat(category.get("name")).isEqualTo("Fresh");
        assertThat(category.get("slug")).isEqualTo("fresh");
        assertThat(category.get("nameAr")).isNull();
        assertThat(category.get("position")).isEqualTo(1);
    }

    /**
     * The domain validation outcome rides the payload's own errors field
     * — the registered "Mutation payload errors" ADAPT divergence: the
     * domain error entries map onto the Saleor ProductError vocabulary.
     */
    @Test
    void categoryCreateAnswersTheDomainErrorsInThePayload() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(categoryWriteSpi.createCategory(any())).thenReturn(
                CategoryMutationResult.failure(List.of(
                        new CategoryMutationError("slug", CategoryMutationErrorCode.ALREADY_EXISTS,
                                "A category with the slug already exists"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryCreate(input: {name: "Fresh", slug: "taken"}) {
                    errors { field message code }
                    category { id }
                } }
                """, "data.categoryCreate");

        assertThat(payload.get("category")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("field")).isEqualTo("slug");
        assertThat(errors.get(0).get("message")).isEqualTo("A category with the slug already exists");
        assertThat(errors.get(0).get("code")).isEqualTo("ALREADY_EXISTS");
    }

    /**
     * The declared-but-unowned {@code parent} argument (no nesting-tree
     * owner — the flat registry's recorded divergence) answers the
     * classified GraphQL error family, never a silently ignored argument.
     */
    @Test
    void categoryCreateWithAParentAnswersTheClassifiedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryCreate(input: {name: "Fresh", slug: "fresh"}, parent: "%s") {
                    errors { field }
                    category { id }
                } }
                """.formatted(categoryGlobalId(UUID.randomUUID())), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("parent is not implemented");
        org.mockito.Mockito.verifyNoInteractions(categoryWriteSpi);
    }

    /**
     * The happy update through the real wiring: the typed global ID
     * decodes at the boundary and the partial command reaches the seam.
     */
    @Test
    void categoryUpdateDecodesTheTypedIdAndExecutesTheOwner() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(categoryWriteSpi.updateCategory(any(UUID.class), any())).thenReturn(
                CategoryMutationResult.success(categoryView(rowId, "Refreshed", "kept")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { code }
                    category { id name slug }
                } }
                """.formatted(categoryGlobalId(rowId)), "data.categoryUpdate");

        verify(categoryWriteSpi).updateCategory(argThat((UUID id) -> id.equals(rowId)),
                argThat(command -> "Refreshed".equals(command.name()) && command.slug() == null));
        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> category = (Map<String, Object>) payload.get("category");
        assertThat(category.get("id")).isEqualTo(categoryGlobalId(rowId));
        assertThat(category.get("name")).isEqualTo("Refreshed");
        assertThat(category.get("slug")).isEqualTo("kept");
    }

    /**
     * The NOT_FOUND domain outcome on update answers the payload's own
     * error entry — the code mapped onto the Saleor vocabulary.
     */
    @Test
    void categoryUpdateOfAnUnknownRowAnswersThePayloadError() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(categoryWriteSpi.updateCategory(any(UUID.class), any())).thenReturn(
                CategoryMutationResult.failure(List.of(new CategoryMutationError(
                        "id", CategoryMutationErrorCode.NOT_FOUND, "No category found for the id"))));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryUpdate(id: "%s", input: {name: "Refreshed"}) {
                    errors { field code }
                    category { id }
                } }
                """.formatted(categoryGlobalId(UUID.randomUUID())), "data.categoryUpdate");

        assertThat(payload.get("category")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors.get(0).get("code")).isEqualTo("NOT_FOUND");
        assertThat(errors.get(0).get("field")).isEqualTo("id");
    }

    /**
     * A malformed global ID answers the codec's classified VAL-001
     * GraphQL error family — never a silent null and never a raw UUID
     * fallback (the read roots' own codec contract).
     */
    @Test
    void categoryUpdateWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryUpdate(id: "not-a-global-id", input: {name: "X"}) {
                    errors { code }
                    category { id }
                } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(categoryWriteSpi);
    }

    /**
     * A mistyped but well-formed global ID (ANOTHER GraphQL type's tag)
     * answers the same classified family — the codec's per-callsite
     * expected-type check.
     */
    @Test
    void categoryUpdateWithAMistypedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String attributeTaggedId = java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryUpdate(id: "%s", input: {name: "X"}) {
                    errors { code }
                    category { id }
                } }
                """.formatted(attributeTaggedId), "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(categoryWriteSpi);
    }

    /**
     * The happy delete through the real wiring: the soft-deleted row's
     * last-known projection answers in the payload.
     */
    @Test
    void categoryDeleteSerializesTheLastKnownProjection() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID rowId = UUID.randomUUID();
        when(categoryWriteSpi.deleteCategory(rowId)).thenReturn(
                CategoryMutationResult.success(categoryView(rowId, "Gone", "gone")));

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryDelete(id: "%s") {
                    errors { code }
                    category { id name slug }
                } }
                """.formatted(categoryGlobalId(rowId)), "data.categoryDelete");

        assertThat(payload.get("errors")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> category = (Map<String, Object>) payload.get("category");
        assertThat(category.get("id")).isEqualTo(categoryGlobalId(rowId));
        assertThat(category.get("name")).isEqualTo("Gone");
    }

    /**
     * The bulk delete's count rides the owner's own honest number, and
     * every id decodes through the typed codec.
     */
    @Test
    void categoryBulkDeleteAnswersTheOwnersHonestCount() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(categoryWriteSpi.deleteCategories(anyList())).thenReturn(1);

        Map<String, Object> payload = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryBulkDelete(ids: ["%s", "%s"]) {
                    count
                    errors { code }
                } }
                """.formatted(categoryGlobalId(first), categoryGlobalId(second)),
                "data.categoryBulkDelete");

        verify(categoryWriteSpi).deleteCategories(argThat((List<UUID> ids) ->
                ids.size() == 2 && ids.contains(first) && ids.contains(second)));
        assertThat(payload.get("count")).isEqualTo(1);
        assertThat(payload.get("errors")).isEqualTo(List.of());
    }

    /**
     * One malformed id in the bulk list fails the whole call with the
     * classified family — never a partial silent skip at the boundary.
     */
    @Test
    void categoryBulkDeleteWithAMalformedIdAnswersTheClassifiedGraphQlError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation { categoryBulkDelete(ids: ["not-a-global-id"]) { count errors { code } } }
                """, "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
        org.mockito.Mockito.verifyNoInteractions(categoryWriteSpi);
    }
}
