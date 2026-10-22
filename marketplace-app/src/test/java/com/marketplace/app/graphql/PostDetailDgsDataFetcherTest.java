package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.shared.api.CmsNavigationPort;
import com.marketplace.shared.api.CmsPagePort;
import com.marketplace.identity.spi.UserManagementSpi;
import com.marketplace.identity.spi.CustomerTypeSpi;
import com.marketplace.identity.spi.AddressManagementSpi;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.shared.api.NeighborhoodFeedMediaView;
import com.marketplace.shared.api.NeighborhoodFeedPostView;
import com.marketplace.shared.api.NeighborhoodPostCommentView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ResourceNotFoundException;
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
 * The post/source detail composite journey's data-fetcher layer test (the
 * {@code HomeDgsDataFetcherTest} house shape verbatim — the official DGS
 * test-starter pattern): {@code @SpringBootTest} over the GraphQL adapter
 * package slice + {@code @EnableDgsTest} + {@link DgsQueryExecutor},
 * executing REAL GraphQL documents against the REAL SDL and the REAL DGS
 * wiring with no web layer.
 *
 * <p><b>The journey's own proofs this slice carries</b> (the readiness
 * doc §4.4 — the inventory #599 §7 stage-4 gate's six families applied to
 * the detail journey, at the data-fetcher layer): the ONE-document
 * composite resolves BOTH legs (the journey's round-trip collapse — the
 * measured REST comparison's win: the same journey over REST is two HTTP
 * requests), the comments leg rides the same connection envelope and
 * bounds the services/neighborhoodFeed fields established, the
 * membership-less caller answers the field-level denial (the honest 404
 * for an unknown post AND the owner's 403 both travel the seam — one
 * classified error per denied field, partial data preserved), and the
 * cost is selection-driven — a post-only document never touches the
 * comments seam and vice versa. The transport-level HTTP proofs (the
 * anonymous 401, the cursor error taxonomy over the wire) remain the
 * integration suite's duty.</p>
 */
@SpringBootTest(classes = PostDetailDgsDataFetcherTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class PostDetailDgsDataFetcherTest {

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

    @MockitoBean
    private com.marketplace.catalog.spi.CatalogSpi catalogSpi;

    // Component scanning includes the catalog administration fetcher; mock its owning module seam.
    @MockitoBean
    private com.marketplace.catalog.spi.ProductAdminSpi productAdminSpi;

    @MockitoBean
    private com.marketplace.discovery.spi.DiscoverySpi discoverySpi;

    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodFeedPort feedPort;

    /** The detail journey's owning seam — both legs' port. */
    @MockitoBean
    private NeighborhoodPostDetailPort postDetailPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    // P1 (the Saleor Users read roots): the package slice now scans
    // UserDataFetcher too — its owning identity seam joins the
    // outside-slice mocking set, exactly the way the seams above do.
    @MockitoBean
    private com.marketplace.identity.spi.IdentitySpi identitySpi;
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
    @MockitoBean
    private ProductVariantReadSpi variantReadSpi;
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
    /** The journey's fields read the principal from the (test-populated) security context. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static final UUID CALLER_ID = UUID.randomUUID();
    private static final UUID POST_ID = UUID.nameUUIDFromBytes("detail-post".getBytes());
    private static final UUID NEIGHBORHOOD_ID = UUID.nameUUIDFromBytes("detail-neighborhood".getBytes());

    private void asCaller() {
        var auth = new TestingAuthenticationToken("member", "pass", "ROLE_USER");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(CALLER_ID);
    }

    private static NeighborhoodFeedPostView post() {
        return new NeighborhoodFeedPostView(
                POST_ID,
                UUID.nameUUIDFromBytes("detail-author".getBytes()),
                NEIGHBORHOOD_ID,
                "GENERAL",
                "The post's title",
                "The post's body",
                "VISIBLE",
                false,
                null,
                3L,
                true,
                List.of(new NeighborhoodFeedMediaView(
                        UUID.nameUUIDFromBytes("detail-media".getBytes()),
                        "https://presigned/object",
                        null,
                        "image/jpeg",
                        0)),
                Instant.parse("2026-10-18T09:00:00Z"),
                Instant.parse("2026-10-18T09:00:00Z"));
    }

    /** One comment as the owner's complete sort key orders it (createdAt ASC, id ASC). */
    private static NeighborhoodPostCommentView comment(int ordinal) {
        return new NeighborhoodPostCommentView(
                UUID.nameUUIDFromBytes(("comment-" + ordinal).getBytes()),
                POST_ID,
                UUID.nameUUIDFromBytes(("commenter-" + ordinal).getBytes()),
                "Comment " + ordinal,
                Instant.parse("2026-10-18T10:00:00Z").plusSeconds(ordinal),
                Instant.parse("2026-10-18T10:00:00Z").plusSeconds(ordinal));
    }

    /** The comments stub honors the owner's contract: the page requested IS the page returned. */
    private static PagedResponse<NeighborhoodPostCommentView> commentPage(
            PagedRequest request, int totalItems) {
        int from = (int) Math.min((long) request.page() * request.size(), totalItems);
        int to = Math.min(from + request.size(), totalItems);
        List<NeighborhoodPostCommentView> content = new java.util.ArrayList<>();
        for (int i = from; i < to; i++) {
            content.add(comment(i));
        }
        return new PagedResponse<>(content, request.page(), request.size(),
                totalItems, (totalItems + request.size() - 1) / request.size(),
                to >= totalItems);
    }

    /** The detail read's one-document composite: both legs in one operation. */
    @Test
    void theCompositeDocumentResolvesBothLegsInOneOperation() {
        asCaller();
        when(postDetailPort.post(CALLER_ID, POST_ID)).thenReturn(post());
        when(postDetailPort.comments(eq(CALLER_ID), eq(POST_ID), any(PagedRequest.class)))
                .thenAnswer(inv -> commentPage(inv.getArgument(2), 5));

        var document = dgsQueryExecutor.executeAndGetDocumentContext("""
                query PostDetail($id: ID!, $first: Int) {
                    post(id: $id) {
                        id authorId locationId category title body status declaredCommercial
                        lostFoundState reactionsCount reactedByMe media { mediaId url thumbUrl contentType position }
                        createdAt updatedAt
                    }
                    postComments(postId: $id, first: $first) {
                        edges { node { id postId authorId body createdAt updatedAt } cursor }
                        pageInfo { hasNextPage endCursor }
                    }
                }
                """, java.util.Map.of("id", POST_ID.toString(), "first", 2));

        assertThat(document.<String>read("data.post.id")).isEqualTo(POST_ID.toString());
        assertThat(document.<String>read("data.post.title")).isEqualTo("The post's title");
        assertThat(document.<Integer>read("data.post.reactionsCount")).isEqualTo(3);
        assertThat(document.<Boolean>read("data.post.reactedByMe")).isTrue();
        assertThat(document.<List<String>>read("data.post.media[*].url"))
                .containsExactly("https://presigned/object");
        assertThat(document.<List<String>>read("data.postComments.edges[*].node.body"))
                .containsExactly("Comment 0", "Comment 1");
        assertThat(document.<Boolean>read("data.postComments.pageInfo.hasNextPage")).isTrue();

        // The cost contract at the fetcher layer: ONE document, each owning
        // leg consulted exactly once — no N+1, no double assembly.
        verify(postDetailPort, times(1)).post(CALLER_ID, POST_ID);
        verify(postDetailPort, times(1)).comments(eq(CALLER_ID), eq(POST_ID), any(PagedRequest.class));
    }

    /**
     * The authorization family's post-gate leg (the P0-1 discipline's own
     * measured scenario): each leg re-validates the post's CURRENT state
     * at open time, so the detail leg resolves and the comments leg —
     * running an instant later on the same document — answers the honest
     * 404 AT THE FIELD LEVEL: the owner's
     * {@code ResourceNotFoundException} travels the seam, the resolver
     * classifies it to the NF-001 taxonomy, and the denied field resolves
     * to null while the resolved detail section survives untouched.
     */
    @Test
    void aCommentsLegDenialDoesNotDestroyTheResolvedDetailSection() {
        asCaller();
        when(postDetailPort.post(CALLER_ID, POST_ID)).thenReturn(post());
        when(postDetailPort.comments(eq(CALLER_ID), eq(POST_ID), any(PagedRequest.class)))
                .thenThrow(new ResourceNotFoundException("Post", POST_ID));

        var result = dgsQueryExecutor.execute("""
                query PostDetail($id: ID!) {
                    post(id: $id) { title reactionsCount }
                    postComments(postId: $id) { edges { node { id } } }
                }
                """, java.util.Map.of("id", POST_ID.toString()));

        assertThat(result.getErrors()).hasSize(1);
        var extensions = result.getErrors().getFirst().getExtensions();
        assertThat(extensions).containsEntry("errorCode", "NF-001");
        assertThat(extensions).containsEntry("category", "not-found");
        // The partial-data contract: the detail leg still resolved, the
        // comments field alone is null with its one classified error.
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) result.getData();
        java.util.Map<?, ?> post = (java.util.Map<?, ?>) data.get("post");
        assertThat(post).isNotNull();
        assertThat(post.get("title")).isEqualTo("The post's title");
        assertThat(data.get("postComments")).isNull();
    }

    /**
     * The authorization family's membership leg: a membership-less caller
     * answers the owner's own denial on BOTH legs — each denied field
     * carries one AUTHZ-classified error, partial data preserved (never a
     * fabricated post, never a widened scope).
     */
    @Test
    void aMembershipLessCallerAnswersTheOwnersOwnDenialOnBothLegs() {
        asCaller();
        when(postDetailPort.post(CALLER_ID, POST_ID)).thenThrow(
                new AccessDeniedException(
                        "Join a neighborhood before reading a post (PUT /api/v1/me/neighborhood)"));
        when(postDetailPort.comments(eq(CALLER_ID), eq(POST_ID), any(PagedRequest.class)))
                .thenThrow(new AccessDeniedException(
                        "Join a neighborhood before reading comments (PUT /api/v1/me/neighborhood)"));

        var result = dgsQueryExecutor.execute("""
                query PostDetail($id: ID!) {
                    post(id: $id) { id }
                    postComments(postId: $id) { edges { node { id } } }
                }
                """, java.util.Map.of("id", POST_ID.toString()));

        assertThat(result.getErrors()).hasSize(2);
        assertThat(result.getErrors().stream()
                .allMatch(error -> "AUTHZ-001".equals(error.getExtensions().get("errorCode"))))
                .isTrue();
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) result.getData();
        assertThat(data.get("post")).isNull();
        assertThat(data.get("postComments")).isNull();
    }

    /**
     * The cost family's selection-driven proof: a post-only document never
     * consults the comments seam — the composite pays only for what the
     * operation selects.
     */
    @Test
    void aPostOnlyDocumentNeverConsultsTheCommentsSeam() {
        asCaller();
        when(postDetailPort.post(CALLER_ID, POST_ID)).thenReturn(post());

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query PostOnly($id: ID!) { post(id: $id) { title reactionsCount } }",
                "data.post.title", java.util.Map.of("id", POST_ID.toString()));

        verify(postDetailPort, never()).comments(any(), any(), any());
    }

    /**
     * The cost family's other leg: a comments-only document never consults
     * the detail seam.
     */
    @Test
    void aCommentsOnlyDocumentNeverConsultsTheDetailSeam() {
        asCaller();
        when(postDetailPort.comments(eq(CALLER_ID), eq(POST_ID), any(PagedRequest.class)))
                .thenAnswer(inv -> commentPage(inv.getArgument(2), 1));

        String body = dgsQueryExecutor.executeAndExtractJsonPath("""
                query CommentsOnly($id: ID!) {
                    postComments(postId: $id) { edges { node { body } } }
                }
                """, "data.postComments.edges[0].node.body", java.util.Map.of("id", POST_ID.toString()));

        assertThat(body).isEqualTo("Comment 0");
        verify(postDetailPort, never()).post(any(), any());
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
                query Comments($id: ID!, $first: Int) {
                    postComments(postId: $id, first: $first) {
                        edges { node { id } }
                        pageInfo { hasNextPage }
                    }
                }
                """, java.util.Map.of("id", POST_ID.toString(), "first", 0));

        assertThat(document.<List<?>>read("data.postComments.edges")).isEmpty();
        assertThat(document.<Boolean>read("data.postComments.pageInfo.hasNextPage")).isFalse();
        verify(postDetailPort, never()).comments(any(), any(), any());
    }

    /**
     * The bounds family's bounded request: an explicit {@code first}
     * crosses the port as the requested page size (the default only when
     * absent — the single-source rule), and the scope family: the caller
     * the principal seam resolves is EXACTLY the caller both legs consume.
     */
    @Test
    void anExplicitFirstCrossesThePortAsTheRequestedPageSizeForTheResolvedCaller() {
        asCaller();
        when(postDetailPort.comments(eq(CALLER_ID), eq(POST_ID), any(PagedRequest.class)))
                .thenAnswer(inv -> commentPage(inv.getArgument(2), 5));

        dgsQueryExecutor.executeAndExtractJsonPath("""
                query Comments($id: ID!, $first: Int) {
                    postComments(postId: $id, first: $first) { edges { node { body } } }
                }
                """, "data.postComments.edges[0].node.body",
                java.util.Map.of("id", POST_ID.toString(), "first", 1));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(PagedRequest.class);
        verify(postDetailPort).comments(eq(CALLER_ID), eq(POST_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().page()).isZero();
        assertThat(requestCaptor.getValue().size()).isEqualTo(1);
        assertThat(requestCaptor.getValue().sort()).isEmpty();
    }
}
