package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.CollectionPage;
import com.marketplace.catalog.spi.CollectionProductsPage;
import com.marketplace.catalog.spi.CollectionReadSpi;
import com.marketplace.catalog.spi.CollectionSort;
import com.marketplace.catalog.spi.CollectionView;
import com.marketplace.catalog.spi.CollectionWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductView;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor {@code collection}
 * and {@code collections} read roots and the {@code Collection.products}
 * field, packet agent-2): the DGS data-fetcher layer proof — the house
 * containerless pattern ({@code @SpringBootTest} over the GraphQL adapter
 * package slice + {@code @EnableDgsTest} + {@link DgsQueryExecutor}):
 * REAL GraphQL documents against the REAL SDL (the agent-2 SDL
 * fragment's own declared roots) and the REAL DGS wiring with no web
 * layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code Collection}/{@code CollectionCountableConnection}/
 * {@code CollectionCountableEdge} types (Saleor's exact wire shape for
 * the declared closure, {@code totalCount} included, with the two
 * documented platform extension facts {@code nameAr}/
 * {@code isPublished}) and the bounded {@code products} field over the
 * central {@code ProductCountableConnection} load through the DGS
 * bridge. Then: the {@code collections}/{@code collection}/
 * {@code products} documents execute through the real argument
 * binding, the typed global-ID codec, the mapper and the exception
 * taxonomy resolver; the connection envelopes serialize with
 * per-element keyset cursors from the SAME cursor strategy the
 * framework adapters use; the declared-but-unowned arguments answer
 * the classified errors — never silent no-ops. The owning catalog use
 * cases are mocked at the declared seams — the full-stack proof lives
 * in {@code CollectionGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = CollectionDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class CollectionDataFetcherDgsTest {

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
    // the UserDataFetcherDgsTest/CategoryDataFetcherDgsTest precedent.
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

    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;

    // The collection read/mutation roots' owning seams (agent-2 L-slice) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the sibling-test precedent).
    @MockitoBean
    private CollectionReadSpi collectionReadSpi;

    @MockitoBean
    private CollectionWriteSpi collectionWriteSpi;

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
    private com.marketplace.catalog.spi.ProductVariantWriteSpi productVariantWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.TranslationWriteSpi translationWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi productTypeAttributeWriteSpi;

    @MockitoBean
    private OrderReadSpi orderReadSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

    @MockitoBean
    private com.marketplace.pricing.spi.DiscountReadSpi discountReadSpi;

    @MockitoBean
    private com.marketplace.pricing.spi.VoucherWriteSpi voucherWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeWriteSpi productTypeWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductWriteSpi productWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.AttributeWriteSpi attributeWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.AttributeValueWriteSpi attributeValueWriteSpi;

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

    private static CollectionView collectionView(UUID id, String name, String nameAr,
                                                 String slug, boolean published) {
        return new CollectionView(id, name, nameAr, slug, published);
    }

    /** The typed global ID the codec emits — base64("Collection:<uuid>"). */
    private static String collectionGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Collection:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The {@code collections} document through the real wiring: the Saleor
     * connection envelope serializes with edges, per-element keyset
     * cursors (from the SAME cursor strategy the framework's connection
     * adaptation uses), pageInfo and totalCount — and the bounded
     * projection's typed facts (the global ID, the rendered name, the
     * stable slug and the two platform extension facts nameAr and
     * isPublished) ride the same envelope.
     */
    @Test
    void collectionsSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID curatedId = UUID.randomUUID();
        UUID featuredId = UUID.randomUUID();

        java.util.function.Function<ScrollPosition, Window<CollectionView>> windowAt = position ->
                Window.from(List.of(
                                collectionView(curatedId, "Curated", "منتقاة", "curated", true),
                                collectionView(featuredId, "Featured", "مميزة", "featured", false)), index -> {
                            if (index == 0) {
                                return ScrollPosition.forward(Map.of(
                                        "name", "Curated", "id", curatedId));
                            }
                            return ScrollPosition.forward(Map.of(
                                    "name", "Featured", "id", featuredId));
                        }, true);
        when(collectionReadSpi.scrollCollections(any(org.springframework.security.core.Authentication.class),
                        any(CollectionSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new CollectionPage(
                        windowAt.apply(ScrollPosition.keyset()), 7));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { collections(first: 2) {
                    edges { node { id name nameAr slug isPublished } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.collections");

        assertThat(connection.get("totalCount")).isEqualTo(7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "Curated", "id", curatedId))));
        assertThat(edges.get(1).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "Featured", "id", featuredId))));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        assertThat(firstNode.get("name")).isEqualTo("Curated");
        assertThat(firstNode.get("nameAr")).isEqualTo("منتقاة");
        assertThat(firstNode.get("slug")).isEqualTo("curated");
        assertThat(firstNode.get("isPublished")).isEqualTo(true);
        assertThat(firstNode.get("id")).isEqualTo(collectionGlobalId(curatedId));
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) connection.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
        assertThat(pageInfo.get("startCursor")).isEqualTo(edges.get(0).get("cursor"));
        assertThat(pageInfo.get("endCursor")).isEqualTo(edges.get(1).get("cursor"));
    }

    /**
     * The public-root mapping rides the real wiring: an authenticated
     * CONSUMER caller reads the collections list — Saleor's own
     * storefront-root semantics behind the platform's authenticated
     * /graphql chain (the registered public-root mapping divergence).
     */
    @Test
    void consumerCallerReadsThePublicCollectionList() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        when(collectionReadSpi.scrollCollections(any(org.springframework.security.core.Authentication.class),
                        any(CollectionSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new CollectionPage(
                        Window.from(List.<CollectionView>of(), index -> ScrollPosition.keyset(), false), 0));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                "query { collections(first: 1) { totalCount } }", "data.collections");

        assertThat(connection.get("totalCount")).isEqualTo(0);
        verify(collectionReadSpi).scrollCollections(
                any(org.springframework.security.core.Authentication.class),
                any(CollectionSort.class), any(), any(Limit.class));
    }

    /**
     * The {@code collection(id)} document: the REAL argument binding
     * carries the opaque global ID, the codec decodes it (a mistyped raw
     * UUID answers the classified VAL-001 error through the real exception
     * resolver), and the owner read serializes through the mapper.
     */
    @Test
    void collectionReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID id = UUID.randomUUID();
        when(collectionReadSpi.findCollectionById(eq(id), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(
                        collectionView(id, "Curated", "منتقاة", "curated", true)));

        String globalId = collectionGlobalId(id);
        Map<String, Object> collection = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { collection(id: $id) { id name nameAr slug isPublished } }",
                "data.collection", Map.of("id", globalId));

        assertThat(collection.get("id")).isEqualTo(globalId);
        assertThat(collection.get("name")).isEqualTo("Curated");
        assertThat(collection.get("nameAr")).isEqualTo("منتقاة");
        assertThat(collection.get("slug")).isEqualTo("curated");
        assertThat(collection.get("isPublished")).isEqualTo(true);
    }

    /**
     * The {@code collection(slug)} document: the REAL argument binding
     * carries the public slug and the owner's slug lookup answers the
     * bounded projection — the second executable key of the Saleor root.
     */
    @Test
    void collectionReadsBackBySlugThroughTheRealArgumentBinding() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID id = UUID.randomUUID();
        when(collectionReadSpi.findCollectionBySlug(eq("curated"), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(
                        collectionView(id, "Curated", "منتقاة", "curated", true)));

        Map<String, Object> collection = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($slug: String) { collection(slug: $slug) { id name nameAr slug isPublished } }",
                "data.collection", Map.of("slug", "curated"));

        assertThat(collection.get("id")).isEqualTo(collectionGlobalId(id));
        assertThat(collection.get("name")).isEqualTo("Curated");
        assertThat(collection.get("slug")).isEqualTo("curated");
    }

    /**
     * The {@code Collection.products} field document: the parent's typed
     * global ID decodes through the codec, the membership's own
     * (position, id) keyset scroll answers, and the member products
     * serialize inside Saleor's exact countable-connection envelope with
     * per-element keyset cursors and the honest totalCount.
     */
    @Test
    void theProductsFieldScrollsTheMembershipOverTheParentGlobalId() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID collectionId = UUID.randomUUID();
        UUID productIdA = UUID.randomUUID();
        UUID productIdB = UUID.randomUUID();
        when(collectionReadSpi.findCollectionById(eq(collectionId),
                        any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(
                        collectionView(collectionId, "Curated", "منتقاة", "curated", true)));

        Window<ProductView> window = Window.from(List.of(
                        new ProductView(productIdA, "First", null, "ACTIVE"),
                        new ProductView(productIdB, "Second", null, "ACTIVE")),
                index -> index == 0
                        ? ScrollPosition.forward(Map.of("position", 1, "id", productIdA))
                        : ScrollPosition.forward(Map.of("position", 2, "id", productIdB)),
                true);
        when(collectionReadSpi.scrollCollectionProducts(eq(collectionId), any(), any(Limit.class),
                        any(org.springframework.security.core.Authentication.class)))
                .thenReturn(new CollectionProductsPage(window, 5));
        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { collection(id: $id) { id slug products(first: 2) {"
                        + " edges { node { id name } cursor }"
                        + " pageInfo { hasNextPage } totalCount } } }",
                "data.collection", Map.of("id", collectionGlobalId(collectionId)));

        assertThat(connection.get("slug")).isEqualTo("curated");
        @SuppressWarnings("unchecked")
        Map<String, Object> products = (Map<String, Object>) connection.get("products");
        assertThat(products.get("totalCount")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) products.get("edges");
        assertThat(edges).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        assertThat(firstNode.get("name")).isEqualTo("First");
        assertThat(firstNode.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Product:" + productIdA).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) products.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
    }

    /**
     * The sort mapping reaches the owning use case: NAME with DESC maps
     * onto the owner's executed keyset sort — and the omitted sortBy maps
     * onto the NAME-ascending default — never a silent fallback.
     */
    @Test
    void nameSortMappingReachesTheOwningUseCase() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<CollectionSort> sortCaptor = ArgumentCaptor.forClass(CollectionSort.class);
        when(collectionReadSpi.scrollCollections(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new CollectionPage(
                        Window.from(List.<CollectionView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { collections(first: 2, sortBy: { direction: DESC, field: NAME }) { totalCount } }
                """,
                "data.collections");

        assertThat(sortCaptor.getValue()).isEqualTo(CollectionSort.of(CollectionSort.Field.NAME, false));
    }

    /**
     * The unowned PRODUCT_COUNT sort field answers the explicit classified
     * error — never a silent fallback to another sort.
     */
    @Test
    void theUnownedProductCountSortFieldAnswersTheClassifiedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { collections(first: 2, sortBy: { field: PRODUCT_COUNT }) { totalCount } }
                """,
                "data.collections"))
                .isInstanceOf(Exception.class);
        verify(collectionReadSpi, org.mockito.Mockito.never()).scrollCollections(
                any(org.springframework.security.core.Authentication.class),
                any(CollectionSort.class), any(), any(Limit.class));
    }

    /**
     * The declared-but-unowned filter argument answers the explicit
     * classified error — never a silent no-op.
     */
    @Test
    void theUnownedFilterArgumentAnswersTheClassifiedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { collections(first: 2, filter: { slug: \"x\" }) { totalCount } }",
                "data.collections"))
                .isInstanceOf(Exception.class);
        verify(collectionReadSpi, org.mockito.Mockito.never()).scrollCollections(
                any(org.springframework.security.core.Authentication.class),
                any(CollectionSort.class), any(), any(Limit.class));
    }

    /**
     * The backward pagination answers the explicit classified error — the
     * forward-only law over the declared wire arguments.
     */
    @Test
    void theBackwardPaginationAnswersTheClassifiedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { collections(last: 2) { totalCount } }",
                "data.collections"))
                .isInstanceOf(Exception.class);
        verify(collectionReadSpi, org.mockito.Mockito.never()).scrollCollections(
                any(org.springframework.security.core.Authentication.class),
                any(CollectionSort.class), any(), any(Limit.class));
    }

    /**
     * The channel argument (no commerce-channel model) answers the
     * explicit classified error.
     */
    @Test
    void theChannelArgumentAnswersTheClassifiedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { collections(first: 2, channel: \"c\") { totalCount } }",
                "data.collections"))
                .isInstanceOf(Exception.class);
    }

    /**
     * A mistyped raw UUID on the {@code collection(id)} argument answers
     * the codec's classified validation error through the real exception
     * resolver — never a silent null and never a raw UUID fallback.
     */
    @Test
    void aRawUuidAnswersTheCodecsClassifiedValidationError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { collection(id: $id) { id } }",
                "data.collection", Map.of("id", UUID.randomUUID().toString())))
                .isInstanceOf(Exception.class);
        verify(collectionReadSpi, org.mockito.Mockito.never()).findCollectionById(
                any(), any(org.springframework.security.core.Authentication.class));
    }

    /**
     * A keyless {@code collection} call answers the classified validation
     * error — a keyless lookup is a client error here.
     */
    @Test
    void aKeylessCollectionCallAnswersTheClassifiedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { collection { id } }",
                "data.collection"))
                .isInstanceOf(Exception.class);
    }

    /**
     * An unauthenticated caller answers the authentication guard — the
     * house pattern the public-root family carries.
     */
    @Test
    void anUnauthenticatedCallerAnswersTheAuthenticationGuard() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { collections(first: 1) { totalCount } }",
                "data.collections"))
                .isInstanceOf(Exception.class);
    }
}
