package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.CmsNavigationPort;
import com.marketplace.shared.api.CmsPagePort;
import com.marketplace.identity.spi.UserManagementSpi;
import com.marketplace.identity.spi.CustomerTypeSpi;
import com.marketplace.identity.spi.AddressManagementSpi;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.CategoryPage;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.CategorySort;
import com.marketplace.catalog.spi.CategoryView;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
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
 * of the Saleor category read roots — packet campaign-b4-a — the house
 * containerless pattern ({@code @SpringBootTest} over the GraphQL
 * adapter package slice + {@code @EnableDgsTest} +
 * {@link DgsQueryExecutor}): REAL GraphQL documents against the REAL
 * SDL and the REAL DGS wiring with no web layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code CategoryCountableConnection}/{@code CategoryCountableEdge} and
 * the bounded {@code Category} type (Saleor's exact wire shape for the
 * declared closure, {@code totalCount} included, with its two documented
 * platform extension facts {@code nameAr}/{@code position}) load
 * through the DGS bridge. Then: the {@code categories}/{@code category}
 * documents execute through the real argument binding, the typed
 * global-ID codec, the mapper and the exception taxonomy resolver; the
 * connection envelope serializes with per-element keyset cursors from
 * the SAME cursor strategy the framework adapters use. The owning
 * catalog use case is mocked at the declared seam — the full-stack
 * proof (the real registry scroll over the Flyway-migrated schema) lives
 * in {@code CategoryGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = CategoryDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class CategoryDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    // The token-rotation JDBC store rides the package component scan; its
    // owning seam is not under test in this slice — the interface mock
    // keeps the slice's containerless wiring intact (the seam-gets-its-mock law).
    @MockitoBean
    private RefreshTokenRotationStore refreshTokenRotationStore;

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    @Autowired
    private EncodingCursorStrategy<ScrollPosition> cursorStrategy;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the UserDataFetcherDgsTest/AttributeDataFetcherDgsTest precedent.
    @MockitoBean
    private CatalogSpi catalogSpi;

    @MockitoBean
    private ProductAdminSpi productAdminSpi;

    @MockitoBean
    private ProductTypeReadSpi productTypeReadSpi;

    @MockitoBean
    private AttributeReadSpi attributeReadSpi;

    @MockitoBean
    private CategoryReadSpi categoryReadSpi;
    // The category mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;

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

    // The product-variant mutation roots' owning write seam (agent-2
    // follow-on packet) — the package slice wires every fetcher, so
    // this seam gets its mock (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantWriteSpi productVariantWriteSpi;

    // The translation mutation roots' owning write seam (agent-2
    // follow-on packet) — the package slice wires every fetcher, so
    // this seam gets its mock (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.TranslationWriteSpi translationWriteSpi;

    // The product-type attribute schema mutation roots' owning write
    // seam (agent-2 follow-on packet) — the package slice wires every
    // fetcher, so this seam gets its mock (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi productTypeAttributeWriteSpi;

    @MockitoBean
    private OrderReadSpi orderReadSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

    // The product-media mutation roots' owning seam (agent-2 N-slice) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;
    // The collection read/mutation roots' owning seams (agent-2 L-slice) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionReadSpi collectionReadSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionWriteSpi collectionWriteSpi;
    // The promotion/voucher read roots' owning seam (b5-b packet) — the
    // sibling-test mock the packet's own precedent prescribed but did not
    // complete; the package slice context cannot boot without it.
    @MockitoBean
    private com.marketplace.pricing.spi.DiscountReadSpi discountReadSpi;
    // The voucher mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.pricing.spi.VoucherWriteSpi voucherWriteSpi;
    // The product-type and product mutation roots' owning write seams
    // (agent-2 packet) — the package slice wires every fetcher, so
    // these seams get their mocks (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeWriteSpi productTypeWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductWriteSpi productWriteSpi;
    // The attribute mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeWriteSpi attributeWriteSpi;
    // The attribute-value mutation roots' owning write seam (agent-2
    // packet) — the package slice wires every fetcher, so this seam
    // gets its mock (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeValueWriteSpi attributeValueWriteSpi;
    @MockitoBean
    private AccountLifecycleSpi accountLifecycleSpi;
    @MockitoBean
    private AddressManagementSpi addressManagementSpi;
    @MockitoBean
    private CustomerTypeSpi customerTypeSpi;
    @MockitoBean
    private UserManagementSpi userManagementSpi;
    @MockitoBean
    private CmsPagePort cmsPagePort;
    @MockitoBean
    private CmsNavigationPort cmsNavigationPort;
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

    private static CategoryView categoryView(UUID id, String name, String nameAr,
                                             String slug, Integer position) {
        return new CategoryView(id, name, nameAr, slug, position);
    }

    /**
     * The {@code categories} document through the real wiring: the Saleor
     * connection envelope serializes with edges, per-element keyset
     * cursors (from the SAME cursor strategy the framework's connection
     * adaptation uses), pageInfo and totalCount — and the bounded
     * projection's typed facts (the global ID, the rendered name, the
     * immutable slug and the two platform extension facts nameAr and
     * position) ride the same envelope.
     */
    @Test
    void categoriesSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID stayId = UUID.randomUUID();
        UUID eventsId = UUID.randomUUID();

        java.util.function.Function<ScrollPosition, Window<CategoryView>> windowAt = position ->
                Window.from(List.of(
                                categoryView(stayId, "Stay", "إقامة", "stay", 1),
                                categoryView(eventsId, "Events", "مناسبات", "events", 2)), index -> {
                            if (index == 0) {
                                return ScrollPosition.forward(Map.of(
                                        "name", "Stay", "id", stayId));
                            }
                            return ScrollPosition.forward(Map.of(
                                    "name", "Events", "id", eventsId));
                        }, true);
        when(categoryReadSpi.scrollCategories(any(org.springframework.security.core.Authentication.class),
                        any(CategorySort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new CategoryPage(
                        windowAt.apply(ScrollPosition.keyset()), 7));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { categories(first: 2) {
                    edges { node { id name nameAr slug position } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.categories");

        assertThat(connection.get("totalCount")).isEqualTo(7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "Stay", "id", stayId))));
        assertThat(edges.get(1).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "Events", "id", eventsId))));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        assertThat(firstNode.get("name")).isEqualTo("Stay");
        assertThat(firstNode.get("nameAr")).isEqualTo("إقامة");
        assertThat(firstNode.get("slug")).isEqualTo("stay");
        assertThat(firstNode.get("position")).isEqualTo(1);
        assertThat(firstNode.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Category:" + stayId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) connection.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
        assertThat(pageInfo.get("startCursor")).isEqualTo(edges.get(0).get("cursor"));
        assertThat(pageInfo.get("endCursor")).isEqualTo(edges.get(1).get("cursor"));
    }

    /**
     * The public-root mapping rides the real wiring: an authenticated
     * CONSUMER caller reads the categories list — Saleor's own
     * storefront-root semantics behind the platform's authenticated
     * /graphql chain (the registered public-root mapping divergence).
     */
    @Test
    void consumerCallerReadsThePublicCategoryList() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        when(categoryReadSpi.scrollCategories(any(org.springframework.security.core.Authentication.class),
                        any(CategorySort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new CategoryPage(
                        Window.from(List.<CategoryView>of(), index -> ScrollPosition.keyset(), false), 0));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                "query { categories(first: 1) { totalCount } }", "data.categories");

        assertThat(connection.get("totalCount")).isEqualTo(0);
        verify(categoryReadSpi).scrollCategories(
                any(org.springframework.security.core.Authentication.class),
                any(CategorySort.class), any(), any(Limit.class));
    }

    /**
     * The {@code category(id)} document: the REAL argument binding
     * carries the opaque global ID, the codec decodes it (a mistyped raw
     * UUID answers the classified VAL-001 error through the real exception
     * resolver), and the owner read serializes through the mapper.
     */
    @Test
    void categoryReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID id = UUID.randomUUID();
        when(categoryReadSpi.findCategoryById(eq(id), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(
                        categoryView(id, "Stay", "إقامة", "stay", 1)));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> category = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { category(id: $id) { id name nameAr slug position } }",
                "data.category", Map.of("id", globalId));

        assertThat(category.get("id")).isEqualTo(globalId);
        assertThat(category.get("name")).isEqualTo("Stay");
        assertThat(category.get("nameAr")).isEqualTo("إقامة");
        assertThat(category.get("slug")).isEqualTo("stay");
        assertThat(category.get("position")).isEqualTo(1);
    }

    /**
     * The {@code category(slug)} document: the REAL argument binding
     * carries the public slug and the owner's code lookup answers the
     * bounded projection — the second executable key of the Saleor root.
     */
    @Test
    void categoryReadsBackBySlugThroughTheRealArgumentBinding() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID id = UUID.randomUUID();
        when(categoryReadSpi.findCategoryBySlug(eq("stay"), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(
                        categoryView(id, "Stay", "إقامة", "stay", 1)));

        Map<String, Object> category = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($slug: String) { category(slug: $slug) { id name nameAr slug position } }",
                "data.category", Map.of("slug", "stay"));

        assertThat(category.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Category:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(category.get("name")).isEqualTo("Stay");
        assertThat(category.get("slug")).isEqualTo("stay");
    }

    /**
     * The sort mapping reaches the owning use case: NAME with DESC maps
     * onto the owner's executed keyset sort — and the omitted sortBy maps
     * onto the NAME-ascending default — never a silent fallback.
     */
    @Test
    void nameSortMappingReachesTheOwningUseCase() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<CategorySort> sortCaptor = ArgumentCaptor.forClass(CategorySort.class);
        when(categoryReadSpi.scrollCategories(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new CategoryPage(
                        Window.from(List.<CategoryView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { categories(first: 2, sortBy: { direction: DESC, field: NAME }) { totalCount } }
                """,
                "data.categories");

        assertThat(sortCaptor.getValue()).isEqualTo(CategorySort.of(CategorySort.Field.NAME, false));
    }

    /** The default sort when the caller sends none: NAME ascending — the same default the attribute slice established. */
    @Test
    void omittedSortMapsOntoTheNameAscendingDefault() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<CategorySort> sortCaptor = ArgumentCaptor.forClass(CategorySort.class);
        when(categoryReadSpi.scrollCategories(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new CategoryPage(
                        Window.from(List.<CategoryView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { categories(first: 2) { totalCount } }", "data.categories");

        assertThat(sortCaptor.getValue()).isEqualTo(CategorySort.of(CategorySort.Field.NAME, true));
    }

    /**
     * The codec's classified validation error surfaces through the REAL
     * error taxonomy resolver when a raw UUID is sent where a typed global
     * ID is expected.
     */
    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { category(id: $id) { id } }",
                "$", Map.of("id", id.toString())))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
    }

    /**
     * The declared-but-unexecuted Saleor arguments answer the explicit
     * classified error through the real resolver — never a silent no-op:
     * the deprecated filter, the where filter and the level tree filter
     * each name their own boundary.
     */
    @Test
    void categoriesFilterAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { categories(first: 2, filter: { search: \"x\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("filter is not implemented");
    }

    @Test
    void categoriesWhereAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { categories(first: 2, where: { ids: [\"x\"] }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("where filter is not implemented");
    }

    @Test
    void categoriesLevelAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { categories(first: 2, level: 1) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("level filtering is not implemented");
    }

    /** The backward-pagination boundary: before/last answer the explicit forward-only error. */
    @Test
    void categoriesBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { categories(last: 2) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("forward-only");
    }

    /**
     * The unowned sort fields (PRODUCT_COUNT, SUBCATEGORY_COUNT — a
     * products connection and a category tree the flat registry does not
     * own) answer the explicit classified not-implemented error — never
     * a silent fallback to another sort.
     */
    @Test
    void unownedSortFieldAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { categories(first: 2, sortBy: { direction: ASC, field: PRODUCT_COUNT }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("PRODUCT_COUNT is not implemented");
    }

    /**
     * The sort gate: a cursor emitted by ANOTHER vocabulary (a
     * slug-keyed cursor on the default name scroll) is rejected — the
     * keyset mismatch, classified VAL-001 through the real resolver.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        String slugCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("slug", "stay",
                        "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { categories(first: 1, after: $after) { totalCount } }",
                "$", Map.of("after", slugCursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "VAL-001"));
    }

    /**
     * The category root's declared-but-unexecuted keys:
     * slugLanguageCode (no translation owner) and externalReference (no
     * external-ID column) answer the explicit classified error whenever
     * they are sent — never silently ignored arguments.
     */
    @Test
    void categorySlugLanguageCodeLookupAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { category(slug: \"stay\", slugLanguageCode: AR) { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("slugLanguageCode lookup is not implemented");
    }

    @Test
    void categoryExternalReferenceLookupAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($externalReference: String) { category(externalReference: $externalReference) { id } }",
                "$", Map.of("externalReference", "erp-cat-1")))
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
    void keylessCategoryLookupAnswersTheClassifiedValidationError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { category { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("category requires one of id or slug")
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
                "query { categories(first: 1) { totalCount } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

    /**
     * The page-size contract rides the real properties binding: the
     * caller's {@code first} reaches the owning use case as the bounded
     * limit (clamped by the boundary's own cap — the same house contract
     * the attribute slice tests).
     */
    @Test
    void requestedPageSizeReachesTheOwningUseCaseAsTheBoundedLimit() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        when(categoryReadSpi.scrollCategories(any(org.springframework.security.core.Authentication.class),
                        any(CategorySort.class), any(), limitCaptor.capture()))
                .thenAnswer(invocation -> new CategoryPage(
                        Window.from(List.<CategoryView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { categories(first: 7) { totalCount } }", "data.categories");

        assertThat(limitCaptor.getValue().max()).isEqualTo(7);
        verify(categoryReadSpi).scrollCategories(
                any(org.springframework.security.core.Authentication.class),
                any(CategorySort.class), any(), any(Limit.class));
    }
}
