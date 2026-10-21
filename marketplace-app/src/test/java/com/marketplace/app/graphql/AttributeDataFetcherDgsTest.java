package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogAttributeType;
import com.marketplace.catalog.spi.AttributePage;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.AttributeSort;
import com.marketplace.catalog.spi.AttributeView;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.shared.security.CurrentUserProvider;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2): the DGS data-fetcher layer proof
 * of the Saleor attribute read roots — the house containerless pattern
 * ({@code @SpringBootTest} over the GraphQL adapter package slice +
 * {@code @EnableDgsTest} + {@link DgsQueryExecutor}): REAL GraphQL
 * documents against the REAL SDL and the REAL DGS wiring with no web
 * layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code AttributeCountableConnection}/{@code AttributeCountableEdge}
 * and the bounded {@code Attribute} type (Saleor's exact wire shape for
 * the declared closure, {@code totalCount} included) load through the DGS
 * bridge. Then: the {@code attributes}/{@code attribute} documents
 * execute through the real argument binding, the typed global-ID codec,
 * the mapper (including the closed domain value shape's
 * {@code inputType}/{@code withChoices} renderings) and the exception
 * taxonomy resolver; the connection envelope serializes with
 * per-element keyset cursors from the SAME cursor strategy the framework
 * adapters use. The owning catalog use cases are mocked at the declared
 * seam — the full-stack proof (real {@code @PreAuthorize}, the real
 * keyset scroll over the reference tables) lives in
 * {@code AttributeGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = AttributeDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class AttributeDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    @Autowired
    private EncodingCursorStrategy<ScrollPosition> cursorStrategy;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the UserDataFetcherDgsTest/PermissionGroupDataFetcherDgsTest/
    // ProductTypeDataFetcherDgsTest precedent.
    @MockitoBean
    private CatalogSpi catalogSpi;

    @MockitoBean
    private ProductAdminSpi productAdminSpi;

    @MockitoBean
    private ProductTypeReadSpi productTypeReadSpi;

    @MockitoBean
    private AttributeReadSpi attributeReadSpi;

    @MockitoBean
    private DiscoverySpi discoverySpi;

    @MockitoBean
    private NeighborhoodFeedPort neighborhoodFeedPort;

    @MockitoBean
    private NeighborhoodPostDetailPort postDetailPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private IdentitySpi identitySpi;

    @MockitoBean
    private ProductVariantReadSpi variantReadSpi;

    @MockitoBean
    private OrderReadSpi orderReadSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

    @MockitoBean
    private CategoryReadSpi categoryReadSpi;
    // The category mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;
    // The promotion/voucher read roots' owning seam (b5-b packet) — the
    // sibling-test mock the packet's own precedent prescribed but did not
    // complete; the package slice context cannot boot without it.
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

    private static AttributeView attributeView(UUID id, String name, String slug,
                                               CatalogAttributeType valueType) {
        return new AttributeView(id, name, slug, valueType);
    }

    /**
     * The {@code attributes} document through the real wiring: the Saleor
     * connection envelope serializes with edges, per-element keyset
     * cursors (from the SAME cursor strategy the framework's connection
     * adaptation uses), pageInfo and totalCount — and the bounded
     * projection's typed facts (the global ID, the rendered name, the
     * immutable slug, the value shape's inputType rendering and the
     * CHOICE-typed withChoices fact) ride the same envelope.
     */
    @Test
    void attributesSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID brandId = UUID.randomUUID();
        UUID colorId = UUID.randomUUID();

        java.util.function.Function<ScrollPosition, Window<AttributeView>> windowAt = position ->
                Window.from(List.of(
                                attributeView(brandId, "Brand", "brand", CatalogAttributeType.CHOICE),
                                attributeView(colorId, "Color", "color", CatalogAttributeType.TEXT)), index -> {
                            if (index == 0) {
                                return ScrollPosition.forward(Map.of(
                                        "name", "Brand", "id", brandId));
                            }
                            return ScrollPosition.forward(Map.of(
                                    "name", "Color", "id", colorId));
                        }, true);
        when(attributeReadSpi.scrollAttributes(any(org.springframework.security.core.Authentication.class),
                        any(AttributeSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new AttributePage(
                        windowAt.apply(ScrollPosition.keyset()), 5));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { attributes(first: 2) {
                    edges { node { id name slug inputType withChoices } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.attributes");

        assertThat(connection.get("totalCount")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "Brand", "id", brandId))));
        assertThat(edges.get(1).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "Color", "id", colorId))));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        assertThat(firstNode.get("name")).isEqualTo("Brand");
        assertThat(firstNode.get("slug")).isEqualTo("brand");
        assertThat(firstNode.get("inputType")).isEqualTo("DROPDOWN");
        assertThat(firstNode.get("withChoices")).isEqualTo(true);
        assertThat(firstNode.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + brandId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        @SuppressWarnings("unchecked")
        Map<String, Object> secondNode = (Map<String, Object>) edges.get(1).get("node");
        assertThat(secondNode.get("inputType")).isEqualTo("PLAIN_TEXT");
        assertThat(secondNode.get("withChoices")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) connection.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
        assertThat(pageInfo.get("startCursor")).isEqualTo(edges.get(0).get("cursor"));
        assertThat(pageInfo.get("endCursor")).isEqualTo(edges.get(1).get("cursor"));
    }

    /**
     * The {@code attribute(id)} document: the REAL argument binding
     * carries the opaque global ID, the codec decodes it (a mistyped raw
     * UUID answers the classified VAL-001 error through the real exception
     * resolver), and the owner read serializes through the mapper.
     */
    @Test
    void attributeReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(attributeReadSpi.findAttributeById(eq(id), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(
                        attributeView(id, "Brand", "brand", CatalogAttributeType.CHOICE)));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> attribute = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { attribute(id: $id) { id name slug inputType withChoices } }",
                "data.attribute", Map.of("id", globalId));

        assertThat(attribute.get("id")).isEqualTo(globalId);
        assertThat(attribute.get("name")).isEqualTo("Brand");
        assertThat(attribute.get("slug")).isEqualTo("brand");
        assertThat(attribute.get("inputType")).isEqualTo("DROPDOWN");
        assertThat(attribute.get("withChoices")).isEqualTo(true);
    }

    /**
     * The {@code attribute(slug)} document: the REAL argument binding
     * carries the public slug and the owner's code lookup answers the
     * bounded projection — the second executable key of the Saleor root.
     */
    @Test
    void attributeReadsBackBySlugThroughTheRealArgumentBinding() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(attributeReadSpi.findAttributeBySlug(eq("brand"), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(
                        attributeView(id, "Brand", "brand", CatalogAttributeType.CHOICE)));

        Map<String, Object> attribute = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($slug: String) { attribute(slug: $slug) { id name slug inputType withChoices } }",
                "data.attribute", Map.of("slug", "brand"));

        assertThat(attribute.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(attribute.get("name")).isEqualTo("Brand");
        assertThat(attribute.get("slug")).isEqualTo("brand");
        assertThat(attribute.get("inputType")).isEqualTo("DROPDOWN");
        assertThat(attribute.get("withChoices")).isEqualTo(true);
    }

    /**
     * The sort mapping reaches the owning use case: SLUG with DESC maps
     * onto the owner's executed keyset sort — and the omitted sortBy maps
     * onto the NAME-ascending default — never a silent fallback.
     */
    @Test
    void slugSortMappingReachesTheOwningUseCase() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        ArgumentCaptor<AttributeSort> sortCaptor = ArgumentCaptor.forClass(AttributeSort.class);
        when(attributeReadSpi.scrollAttributes(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new AttributePage(
                        Window.from(List.<AttributeView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { attributes(first: 2, sortBy: { direction: DESC, field: SLUG }) { totalCount } }
                """,
                "data.attributes");

        assertThat(sortCaptor.getValue()).isEqualTo(AttributeSort.of(AttributeSort.Field.SLUG, false));
    }

    /** The default sort when the caller sends none: NAME ascending — the same default the product-type slice established. */
    @Test
    void omittedSortMapsOntoTheNameAscendingDefault() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        ArgumentCaptor<AttributeSort> sortCaptor = ArgumentCaptor.forClass(AttributeSort.class);
        when(attributeReadSpi.scrollAttributes(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new AttributePage(
                        Window.from(List.<AttributeView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attributes(first: 2) { totalCount } }", "data.attributes");

        assertThat(sortCaptor.getValue()).isEqualTo(AttributeSort.of(AttributeSort.Field.NAME, true));
    }

    /**
     * The codec's classified validation error surfaces through the REAL
     * error taxonomy resolver when a raw UUID is sent where a typed global
     * ID is expected.
     */
    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { attribute(id: $id) { id } }",
                "$", Map.of("id", id.toString())))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
    }

    /**
     * The owning use case's ADMIN denial maps to the AUTHZ-001 taxonomy
     * through the real exception resolver — the same classification the
     * full-stack integration test proves against the real
     * {@code @PreAuthorize}.
     */
    @Test
    void ownerDenialMapsToTheAuthzTaxonomyThroughTheRealResolver() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        when(attributeReadSpi.findAttributeBySlug(any(String.class), any(org.springframework.security.core.Authentication.class)))
                .thenThrow(new AccessDeniedException("Administrator role required"));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($slug: String) { attribute(slug: $slug) { id } }",
                "$", Map.of("slug", "brand")))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Administrator role required")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "AUTHZ-001")
                        .containsEntry("category", "authz"));
    }

    /**
     * The declared-but-unexecuted Saleor arguments answer the explicit
     * classified error through the real resolver — never a silent no-op:
     * the deprecated filter, the where filter, the free-text search and
     * the channel scoping each name their own boundary.
     */
    @Test
    void attributesFilterAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attributes(first: 2, filter: { search: \"x\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("filter is not implemented");
    }

    @Test
    void attributesWhereAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attributes(first: 2, where: { withChoices: true }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("where filter is not implemented");
    }

    @Test
    void attributesSearchAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attributes(first: 2, search: \"brand\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("search is not implemented");
    }

    @Test
    void attributesChannelAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attributes(first: 2, channel: \"channel-1\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("channel filtering is not implemented");
    }

    /** The backward-pagination boundary: before/last answer the explicit forward-only error. */
    @Test
    void attributesBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attributes(last: 2) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("forward-only");
    }

    /**
     * The unowned sort fields (VALUE_REQUIRED, IS_VARIANT_ONLY,
     * VISIBLE_IN_STOREFRONT, the four deprecated flags) answer the
     * explicit classified not-implemented error — never a silent fallback
     * to another sort.
     */
    @Test
    void unownedSortFieldAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attributes(first: 2, sortBy: { direction: ASC, field: VALUE_REQUIRED }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("VALUE_REQUIRED is not implemented");
    }

    /**
     * The sort gate: a cursor emitted by the OTHER sort (a slug-keyed
     * cursor on the default name scroll) is rejected — the keyset
     * mismatch, classified VAL-001 through the real resolver.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String slugCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("slug", "brand",
                        "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { attributes(first: 1, after: $after) { totalCount } }",
                "$", Map.of("after", slugCursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "VAL-001"));
    }

    /**
     * The attribute root's declared-but-unexecuted key: externalReference
     * answers the explicit classified error whenever it is sent — never a
     * silently ignored argument.
     */
    @Test
    void attributeExternalReferenceLookupAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($externalReference: String) { attribute(externalReference: $externalReference) { id } }",
                "$", Map.of("externalReference", "erp-brand-1")))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("externalReference lookup is not implemented");
    }

    /**
     * The keyless call shape: no id and no slug answers the explicit
     * classified validation error — a keyless lookup is a client error,
     * never a fabricated absence (the recorded divergence from upstream's
     * null answer).
     */
    @Test
    void keylessAttributeLookupAnswersTheClassifiedValidationError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attribute { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("attribute requires one of id or slug")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "VAL-001"));
    }

    /** The unauthenticated guard: no security context answers the credentials error, never a silent read. */
    @Test
    void unauthenticatedCallerAnswersTheCredentialsError() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attributes(first: 1) { totalCount } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

    /**
     * The page-size contract rides the real properties binding: the
     * caller's {@code first} reaches the owning use case as the bounded
     * limit (clamped by the boundary's own cap — the same house contract
     * the product-type slice tests).
     */
    @Test
    void requestedPageSizeReachesTheOwningUseCaseAsTheBoundedLimit() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        when(attributeReadSpi.scrollAttributes(any(org.springframework.security.core.Authentication.class),
                        any(AttributeSort.class), any(), limitCaptor.capture()))
                .thenAnswer(invocation -> new AttributePage(
                        Window.from(List.<AttributeView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { attributes(first: 7) { totalCount } }", "data.attributes");

        assertThat(limitCaptor.getValue().max()).isEqualTo(7);
        verify(attributeReadSpi).scrollAttributes(
                any(org.springframework.security.core.Authentication.class),
                any(AttributeSort.class), any(), any(Limit.class));
    }
}
