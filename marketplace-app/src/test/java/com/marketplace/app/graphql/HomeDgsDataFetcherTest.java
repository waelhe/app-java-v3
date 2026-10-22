package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.shared.api.DiscoveryCardView;
import com.marketplace.shared.api.DiscoveryRowType;
import com.marketplace.shared.api.DiscoveryRowView;
import com.marketplace.shared.api.NeighborhoodFeedMediaView;
import com.marketplace.shared.api.NeighborhoodFeedPostView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first composite journey's data-fetcher layer test — the official
 * DGS test-starter pattern the design's Phase 2 prescribes (the
 * {@code ServiceDgsDataFetcherTest} house shape): {@code @SpringBootTest}
 * over the GraphQL adapter package slice + {@code @EnableDgsTest} +
 * {@link DgsQueryExecutor}, executing REAL GraphQL documents against the
 * REAL SDL and the REAL DGS wiring with no web layer.
 *
 * <p><b>The composite's own proof this slice carries</b> (the inventory
 * #599 §7 stage-4 gate — «اختبارات النطاق والترتيب والصلاحية والفراغ
 * والأخطاء والكلفة», at the data-fetcher layer): the ONE-document
 * composite resolves BOTH sections (the journey's round-trip collapse
 * — the REST comparison's measured win), the rails arrive in the
 * assembler's own order with empty rails absent, the feed page rides
 * the same connection envelope and bounds the services field
 * established, the membership-less caller answers the honest
 * discovery-empty plus the feed's own field-level denial, and the cost
 * is selection-driven — a discovery-only document never touches the
 * feed seam and vice versa. The transport-level HTTP proofs (the
 * anonymous 401, the cursor error taxonomy) remain the integration
 * suite's duty.</p>
 */
@SpringBootTest(classes = HomeDgsDataFetcherTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class HomeDgsDataFetcherTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    @MockitoBean
    private com.marketplace.catalog.spi.CatalogSpi catalogSpi;

    // Component scanning includes the catalog administration fetcher; mock its owning module seam.
    @MockitoBean
    private com.marketplace.catalog.spi.ProductAdminSpi productAdminSpi;

    @MockitoBean
    private DiscoverySpi discoverySpi;

    @MockitoBean
    private NeighborhoodFeedPort feedPort;

    // Stage 7 (the post/source detail composite journey): the detail
    // fetcher component-scanned into this slice consumes the detail port —
    // mocked here exactly as the feed port above (the slice wires every
    // fetcher in the package; each owning seam gets its mock).
    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodPostDetailPort postDetailPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    // P1 (the Saleor Users read roots): the package slice now scans
    // UserDataFetcher too — its owning identity seam joins the
    // outside-slice mocking set, exactly the way the seams above do.
    @MockitoBean
    private com.marketplace.identity.spi.IdentitySpi identitySpi;

    @MockitoBean
    private com.marketplace.orders.spi.OrderCommandSpi orderCommandSpi;

    @MockitoBean
    private com.marketplace.orders.spi.CheckoutSpi checkoutSpi;
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
    // The package slice wires every fetcher; every seam the slice's other
    // fetchers need gets its mock — the incomplete sibling fixes the
    // b4/b5 packets left behind (committed against a red CI) completed
    // here so the shared slice context can boot at all.
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CategoryReadSpi categoryReadSpi;

    @MockitoBean
    private com.marketplace.orders.spi.OrderReadSpi orderReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductReadSpi productReadSpi;


    // The product-media mutation roots' owning seam (agent-2 N-slice) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeReadSpi productTypeReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantReadSpi productVariantReadSpi;

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

    // The collection read/mutation roots' owning seams (agent-2 L-slice) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionReadSpi collectionReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CollectionWriteSpi collectionWriteSpi;
    /** The journey's fields read the principal from the (test-populated) security context. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static final UUID CALLER_ID = UUID.randomUUID();

    private void asCaller() {
        var auth = new TestingAuthenticationToken("member", "pass", "ROLE_USER");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(CALLER_ID);
    }

    private static DiscoveryRowView row(DiscoveryRowType type, int cards, long total) {
        List<DiscoveryCardView> views = java.util.stream.IntStream.range(0, cards)
                .mapToObj(i -> new DiscoveryCardView(
                        "NEIGHBORHOOD_POST",
                        UUID.randomUUID(),
                        Instant.parse("2026-10-17T10:00:00Z"),
                        "Card " + i,
                        "An honest bounded excerpt",
                        "ACTIVE",
                        null,
                        "في نطاق حيّك",
                        null))
                .toList();
        return new DiscoveryRowView(type, views, total);
    }

    private static NeighborhoodFeedPostView post(int ordinal) {
        return new NeighborhoodFeedPostView(
                UUID.nameUUIDFromBytes(("post-" + ordinal).getBytes()),
                UUID.nameUUIDFromBytes(("author-" + ordinal).getBytes()),
                UUID.nameUUIDFromBytes("neighborhood".getBytes()),
                "GENERAL",
                "Post " + ordinal,
                "The body " + ordinal,
                "VISIBLE",
                false,
                null,
                3L,
                false,
                List.of(new NeighborhoodFeedMediaView(
                        UUID.nameUUIDFromBytes(("media-" + ordinal).getBytes()),
                        "https://presigned/object-" + ordinal,
                        null,
                        "image/jpeg",
                        0)),
                Instant.parse("2026-10-17T09:00:00Z"),
                Instant.parse("2026-10-17T09:00:00Z"));
    }

    /** The home read's one-document composite: both sections in one operation. */
    @Test
    void theCompositeDocumentResolvesBothSectionsInOneOperation() {
        asCaller();
        when(discoverySpi.homeRows(CALLER_ID)).thenReturn(List.of(
                row(DiscoveryRowType.LOST_FOUND, 2, 7),
                row(DiscoveryRowType.FOR_YOU, 1, 1)));
        when(feedPort.feed(eq(CALLER_ID), any(PagedRequest.class))).thenAnswer(inv -> {
            PagedRequest request = inv.getArgument(1);
            return new PagedResponse<>(List.of(post(0), post(1)),
                    request.page(), request.size(), 5L, 3, false);
        });

        var document = dgsQueryExecutor.executeAndGetDocumentContext("""
                query Home($first: Int) {
                    discovery {
                        row
                        cards { sourceType sourceId updatedAt title snippet state reason paid }
                        totalEligible
                    }
                    neighborhoodFeed(first: $first) {
                        edges { node { id category title body status createdAt } cursor }
                        pageInfo { hasNextPage endCursor }
                    }
                }
                """, java.util.Map.of("first", 2));

        assertThat(document.<List<String>>read("data.discovery[*].row"))
                .containsExactly("LOST_FOUND", "FOR_YOU");
        assertThat(document.<Integer>read("data.discovery[0].totalEligible")).isEqualTo(7);
        assertThat(document.<List<String>>read("data.neighborhoodFeed.edges[*].node.title"))
                .containsExactly("Post 0", "Post 1");
        assertThat(document.<Boolean>read("data.neighborhoodFeed.pageInfo.hasNextPage")).isTrue();

        // The cost contract at the fetcher layer: ONE document, each owning
        // seam consulted exactly once — no N+1, no double assembly.
        verify(discoverySpi, times(1)).homeRows(CALLER_ID);
        verify(feedPort, times(1)).feed(eq(CALLER_ID), any(PagedRequest.class));
    }

    /**
     * The emptiness + authorization families together (the inventory's
     * own pairing): a membership-less caller answers the discovery
     * owner's honest EMPTY list while the feed owner's own denial
     * surfaces AT THE FIELD LEVEL — partial data plus one classified
     * error, never a fabricated feed and never a widened scope.
     */
    @Test
    void aMembershipLessCallerAnswersEmptyDiscoveryAndTheFeedsOwnFieldLevelDenial() {
        asCaller();
        when(discoverySpi.homeRows(CALLER_ID)).thenReturn(List.of());
        when(feedPort.feed(eq(CALLER_ID), any(PagedRequest.class)))
                .thenThrow(new AccessDeniedException(
                        "Join a neighborhood before reading its feed (PUT /api/v1/me/neighborhood)"));

        var result = dgsQueryExecutor.execute("""
                query Home {
                    discovery { row totalEligible }
                    neighborhoodFeed { edges { node { id } } }
                }
                """);

        assertThat(result.getErrors()).hasSize(1);
        var extensions = result.getErrors().getFirst().getExtensions();
        assertThat(extensions).containsEntry("errorCode", "AUTHZ-001");
        assertThat(extensions).containsEntry("category", "authz");
        // The composite's partial-data contract: discovery still resolved
        // (the honest empty list), the feed field alone is denied — one
        // classified field error, never a fabricated feed, never a
        // widened scope.
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) result.getData();
        assertThat((java.util.List<?>) data.get("discovery")).isEmpty();
        assertThat(data.get("neighborhoodFeed")).isNull();
    }

    /**
     * The cost family's selection-driven proof: a discovery-only document
     * never consults the feed seam — the composite pays only for what
     * the operation selects.
     */
    @Test
    void aDiscoveryOnlyDocumentNeverConsultsTheFeedSeam() {
        asCaller();
        when(discoverySpi.homeRows(CALLER_ID)).thenReturn(List.of(row(DiscoveryRowType.FOR_YOU, 1, 1)));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query DiscoveryOnly { discovery { row totalEligible } }",
                "data.discovery[0].row");

        verify(feedPort, never()).feed(any(), any());
    }

    /**
     * The cost family's other leg: a feed-only document never consults
     * the discovery seam.
     */
    @Test
    void aFeedOnlyDocumentNeverConsultsTheDiscoverySeam() {
        asCaller();
        when(feedPort.feed(eq(CALLER_ID), any(PagedRequest.class))).thenAnswer(inv -> {
            PagedRequest request = inv.getArgument(1);
            return new PagedResponse<>(List.of(post(0)), request.page(), request.size(), 1L, 1, true);
        });

        String title = dgsQueryExecutor.executeAndExtractJsonPath(
                "query FeedOnly { neighborhoodFeed { edges { node { title } } } }",
                "data.neighborhoodFeed.edges[0].node.title");

        assertThat(title).isEqualTo("Post 0");
        verify(discoverySpi, never()).homeRows(any());
    }

    /**
     * The bounds family at the fetcher layer: a non-positive {@code first}
     * is the relay spec's empty page — answered honestly without any
     * owning-module work.
     */
    @Test
    void aNonPositiveFirstAnswersAnEmptyPageWithoutOwningModuleWork() {
        asCaller();

        var document = dgsQueryExecutor.executeAndGetDocumentContext("""
                query Feed($first: Int) {
                    neighborhoodFeed(first: $first) {
                        edges { node { id } }
                        pageInfo { hasNextPage }
                    }
                }
                """, java.util.Map.of("first", 0));

        assertThat(document.<List<?>>read("data.neighborhoodFeed.edges")).isEmpty();
        verify(feedPort, never()).feed(any(), any());
    }

    /**
     * The bounds family's bounded request: an explicit {@code first}
     * crosses the port as the requested page size (the default only
     * when absent — the single-source rule).
     */
    @Test
    void anExplicitFirstCrossesThePortAsTheRequestedPageSize() {
        asCaller();
        when(feedPort.feed(eq(CALLER_ID), any(PagedRequest.class))).thenAnswer(inv -> {
            PagedRequest request = inv.getArgument(1);
            return new PagedResponse<>(List.of(post(0)), request.page(), request.size(), 1L, 1, true);
        });

        dgsQueryExecutor.executeAndExtractJsonPath("""
                query Feed($first: Int) {
                    neighborhoodFeed(first: $first) { edges { node { title } } }
                }
                """, "data.neighborhoodFeed.edges[0].node.title", java.util.Map.of("first", 1));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(PagedRequest.class);
        verify(feedPort).feed(eq(CALLER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().page()).isZero();
        assertThat(requestCaptor.getValue().size()).isEqualTo(1);
        assertThat(requestCaptor.getValue().sort()).isEmpty();
    }

    /**
     * The scope family at the fetcher layer: the caller the principal
     * seam resolves is EXACTLY the caller both owning seams consume —
     * there is no client-side scope vocabulary anywhere on the journey.
     */
    @Test
    void theResolvedPrincipalIsExactlyTheCallerBothSeamsConsume() {
        asCaller();
        when(discoverySpi.homeRows(CALLER_ID)).thenReturn(List.of());
        when(feedPort.feed(eq(CALLER_ID), any(PagedRequest.class))).thenAnswer(inv -> {
            PagedRequest request = inv.getArgument(1);
            return new PagedResponse<>(List.of(), request.page(), request.size(), 0L, 0, true);
        });

        dgsQueryExecutor.execute("query Home { discovery { row } neighborhoodFeed { edges { node { id } } } }");

        verify(discoverySpi).homeRows(CALLER_ID);
        verify(feedPort).feed(eq(CALLER_ID), any(PagedRequest.class));
    }
}
